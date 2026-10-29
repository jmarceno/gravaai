package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import dev.gravaai.core.json.Json;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Appending another recording to an existing one, ported from
 * {@code electron/ipc/recording/importRecording.ts} and {@code sequenceWebcam.ts} (plan §5.6).
 *
 * <p>The editor and export share one immutable source: the current video and the appended
 * recording are normalized to one format, concatenated, mixed into a single AAC track and split
 * back into the two lossless PCM companions. Cursor and key telemetry move onto the combined
 * clock; the webcam track is composed with black spans where one side has no camera. The
 * originals are never modified. Media metadata comes from {@link MediaProbe} and ffmpeg is the
 * bundled CLI (plan §5.7).
 */
public final class RecordingImportService {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");
	private static final int WEBCAM_FPS = 30;

	/** One webcam source linked to a sequence video ({@code RecordingWebcamSource}). */
	public record RecordingWebcamSource(String sourcePath, long timeOffsetMs, @Nullable List<VisibleRange> visibleRanges) {
	}

	/** A visible webcam span on the recording clock. */
	public record VisibleRange(long startMs, long endMs) {
	}

	/**
	 * The import result ({@code RecordingImportResult}; the media URL is N/A, direct decode).
	 *
	 * @param webcam the composed webcam track, or null when neither input had a linked webcam
	 */
	public record ImportResult(
			String path,
			long sourceStartMs,
			long durationMs,
			long totalDurationMs,
			@Nullable RecordingWebcamSource webcam) {
	}

	/** The probed media values the pipeline uses. */
	record MediaMeta(int width, int height, double fps, double durationSeconds, boolean audio) {
	}

	/** Normalized video format for the sequence. */
	record Format(int width, int height, double fps) {
	}

	/** Companion audio paths and their capture start delays. */
	record NormalizeSources(@Nullable String systemPath, @Nullable String micPath, Map<String, Long> startDelayMsByPath) {
	}

	/** The ffmpeg arguments and filter graph that normalize one input. */
	record NormalizePlan(List<String> args, String filterComplex, String videoMap) {
	}

	/** The ffmpeg arguments that encode one webcam segment. */
	record WebcamSegmentPlan(List<String> args, String filter) {
	}

	private final Supplier<Path> recordingsDir;
	private final RecordingProcesses processes;
	private final RecordingEnvironment environment;
	private final RecordingLibrary library;

	public RecordingImportService(
			Supplier<Path> recordingsDir,
			RecordingProcesses processes,
			RecordingEnvironment environment,
			RecordingLibrary library) {
		this.recordingsDir = recordingsDir;
		this.processes = processes;
		this.environment = environment;
		this.library = library;
	}

	/** {@code webcamOutputs}: the composed webcam, the session manifest and the visible ranges. */
	public static List<String> sequenceWebcamOutputs(String video) {
		String webcam = video.endsWith(".mp4")
				? video.substring(0, video.length() - 4) + "-webcam.mp4"
				: video + "-webcam.mp4";
		String session = video.endsWith(".mp4")
				? video.substring(0, video.length() - 4) + ".gravaai-session.json"
				: video + ".gravaai-session.json";
		return List.of(webcam, session, video + ".webcam-ranges.json");
	}

	/** {@code recordingImportOutputs}: every file the import owns and may delete. */
	public static List<String> recordingImportOutputs(String output) {
		String stem = output.endsWith(".mp4") ? output.substring(0, output.length() - 4) : output;
		List<String> outputs = new ArrayList<>(sequenceWebcamOutputs(output));
		outputs.add(output);
		outputs.add(stem + ".system.wav");
		outputs.add(stem + ".mic.wav");
		outputs.add(output + ".cursor.json");
		outputs.add(output + ".keys.json");
		return outputs;
	}

	private static String number(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e15) {
			return Long.toString((long) value);
		}
		return Double.toString(value);
	}

	/** The {@code normalize} filter graph and argument list from importRecording.ts. */
	static NormalizePlan planNormalize(MediaMeta meta, NormalizeSources sources, Format format, boolean copyVideo) {
		List<String> args = new ArrayList<>();
		args.add("-i");
		args.add("placeholder"); // replaced by the caller with the real input path
		List<String> filters = new ArrayList<>();
		int input = 1;
		for (String kind : List.of("system", "mic")) {
			String source = "system".equals(kind) ? sources.systemPath() : sources.micPath();
			String label;
			long delay = 0;
			if (source != null) {
				args.add("-i");
				args.add(source);
				label = input++ + ":a:0";
				delay = sources.startDelayMsByPath().getOrDefault(source, 0L);
			} else if ("system".equals(kind) && meta.audio()) {
				label = "0:a:0";
			} else {
				args.add("-f");
				args.add("lavfi");
				args.add("-i");
				args.add("anullsrc=r=48000:cl=stereo");
				label = input++ + ":a:0";
			}
			filters.add("[" + label + "]aresample=48000,aformat=sample_fmts=s16:channel_layouts=stereo,"
					+ "asetpts=PTS-STARTPTS,adelay=" + Math.round(delay) + ":all=1,apad,atrim=duration="
					+ number(meta.durationSeconds()) + "[" + kind + "]");
		}
		String videoMap = copyVideo ? "0:v:0" : "[v]";
		if (!copyVideo) {
			filters.add("[0:v:0]setpts=PTS-STARTPTS,scale=" + format.width() + ":" + format.height()
					+ ":force_original_aspect_ratio=decrease,pad=" + format.width() + ":" + format.height()
					+ ":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=" + number(format.fps()) + ",format=yuv420p[v]");
		}
		List<String> complete = new ArrayList<>(args);
		complete.add("-filter_complex");
		complete.add(String.join(";", filters));
		complete.add("-map");
		complete.add(videoMap);
		complete.add("-map");
		complete.add("[system]");
		complete.add("-map");
		complete.add("[mic]");
		complete.add("-c:v");
		complete.add(copyVideo ? "copy" : "libx264");
		if (!copyVideo) {
			complete.add("-preset");
			complete.add("fast");
			complete.add("-crf");
			complete.add("18");
		}
		complete.add("-c:a");
		complete.add("pcm_s16le");
		complete.add("-t");
		complete.add(number(meta.durationSeconds()));
		return new NormalizePlan(List.copyOf(complete), String.join(";", filters), videoMap);
	}

	/** The filter and arguments for one webcam segment (a black span when the source is absent). */
	static WebcamSegmentPlan planWebcamSegment(
			@Nullable String sourcePath,
			int width,
			int height,
			long delayMs,
			double durationSeconds,
			long timeOffsetMs,
			double durationMs,
			double sourceDurationSeconds) {
		String baseFilter = "scale=" + width + ":" + height
				+ ":force_original_aspect_ratio=decrease,pad=" + width + ":" + height
				+ ":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=" + WEBCAM_FPS + ",format=yuv420p";
		List<String> args = new ArrayList<>();
		String filter;
		if (sourcePath != null) {
			args.add("-i");
			args.add(sourcePath);
			filter = "trim=start=" + number(Math.max(0, -delayMs) / 1000.0)
					+ ",setpts=PTS-STARTPTS," + baseFilter
					+ ",tpad=start_mode=add:start_duration=" + number(Math.max(0, delayMs) / 1000.0)
					+ ":stop_mode=add:stop_duration=" + number(durationSeconds)
					+ ",trim=duration=" + number(durationSeconds);
		} else {
			args.add("-f");
			args.add("lavfi");
			args.add("-i");
			args.add("color=c=black:s=" + width + "x" + height + ":r=" + WEBCAM_FPS + ":d="
					+ number(durationSeconds));
			filter = baseFilter;
		}
		args.add("-an");
		args.add("-vf");
		args.add(filter);
		args.add("-t");
		args.add(number(durationSeconds));
		args.add("-c:v");
		args.add("libx264");
		args.add("-preset");
		args.add("fast");
		args.add("-crf");
		args.add("18");
		return new WebcamSegmentPlan(List.copyOf(args), filter);
	}

	/** Merges cursor telemetry onto the combined clock, rescaling positions into the output format. */
	static List<CursorTelemetryPoint> mergeCursorSamples(
			List<List<CursorTelemetryPoint>> chunks,
			List<Long> offsetsMs,
			List<MediaMeta> metas,
			Format format) {
		List<CursorTelemetryPoint> samples = new ArrayList<>();
		for (int index = 0; index < chunks.size(); index++) {
			MediaMeta meta = metas.get(index);
			long offset = offsetsMs.get(index);
			double scale = Math.min(
					meta.width() == 0 ? format.width() : (double) format.width() / meta.width(),
					meta.height() == 0 ? format.height() : (double) format.height() / meta.height());
			double scaledWidth = meta.width() * scale;
			double scaledHeight = meta.height() * scale;
			for (CursorTelemetryPoint point : chunks.get(index)) {
				double cx = (point.cx() * scaledWidth + (format.width() - scaledWidth) / 2) / format.width();
				double cy = (point.cy() * scaledHeight + (format.height() - scaledHeight) / 2) / format.height();
				samples.add(new CursorTelemetryPoint(
						point.timeMs() + offset, cx, cy, point.interactionType(), point.cursorType()));
			}
		}
		return samples;
	}

	/** Merges key-press telemetry onto the combined clock. */
	static List<KeystrokeEvent> mergeKeystrokeEvents(
			List<List<KeystrokeEvent>> chunks, List<Long> offsetsMs) {
		List<KeystrokeEvent> events = new ArrayList<>();
		for (int index = 0; index < chunks.size(); index++) {
			long offset = offsetsMs.get(index);
			for (KeystrokeEvent event : chunks.get(index)) {
				events.add(new KeystrokeEvent(event.timeMs() + offset, event.key(), event.modifiers()));
			}
		}
		return events;
	}

	private MediaMeta probe(String file) {
		try {
			MediaInfo info = MediaProbe.probe(Path.of(file));
			var video = info.video();
			int width = video.map(value -> value.width()).orElse(0);
			int height = video.map(value -> value.height()).orElse(0);
			double fps = video.map(value -> value.fps()).orElse(0.0);
			return new MediaMeta(width, height, fps, info.durationSeconds(), info.hasAudio());
		} catch (RuntimeException error) {
			throw new IllegalStateException("Could not probe " + file + ": " + error, error);
		}
	}

	private void runFfmpeg(List<String> args) throws IOException {
		runFfmpeg(args, null);
	}

	/**
	 * Runs one ffmpeg command; when {@code cancelled} is set (may be {@code null}) it is checked
	 * before the spawn and a watchdog destroys the running process while waiting.
	 *
	 * @param cancelled the cancellation token, or null when the run cannot be cancelled
	 */
	private void runFfmpeg(List<String> args, @Nullable BooleanSupplier cancelled) throws IOException {
		if (cancelled != null && cancelled.getAsBoolean()) {
			throw new IOException("Import canceled");
		}
		List<String> command = new ArrayList<>();
		command.add(environment.ffmpeg().toString());
		command.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-y"));
		command.addAll(args);
		SpawnedProcess process;
		try {
			process = processes.spawn(command, new SpawnOptions(false, false, true));
		} catch (IOException error) {
			throw new IOException("Could not start ffmpeg", error);
		}
		Thread watchdog = null;
		if (cancelled != null) {
			watchdog = Thread.ofVirtual().name("gravaai-import-cancel").start(() -> {
				while (process.isAlive()) {
					if (cancelled.getAsBoolean()) {
						process.destroy();
						return;
					}
					try {
						Thread.sleep(200);
					} catch (InterruptedException interrupted) {
						return;
					}
				}
			});
		}
		InputStream errorStream = process.stderr();
		byte[] stderr = errorStream == null ? new byte[0] : errorStream.readAllBytes();
		int code;
		try {
			code = process.waitFor();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IOException("ffmpeg was interrupted", error);
		} finally {
			if (watchdog != null) {
				watchdog.interrupt();
			}
		}
		if (cancelled != null && cancelled.getAsBoolean()) {
			throw new IOException("Import canceled");
		}
		if (code != 0) {
			throw new IOException(new String(stderr, StandardCharsets.UTF_8).trim()
					.isEmpty() ? "ffmpeg exited with code " + code : new String(stderr, StandardCharsets.UTF_8).trim());
		}
	}

	private NormalizeSources companionSources(String file) {
		List<RecordingDiagnostics.CompanionAudioCandidate> candidates =
				RecordingDiagnostics.getUsableCompanionAudioCandidates(file);
		if (candidates.isEmpty()) {
			return new NormalizeSources(null, null, Map.of());
		}
		RecordingDiagnostics.CompanionAudioCandidate companion = candidates.get(0);
		String system = companion.usablePaths().contains(companion.systemPath())
				? companion.systemPath()
				: null;
		String mic = companion.usablePaths().contains(companion.micPath())
				? companion.micPath()
				: null;
		Map<String, Long> delays = new java.util.LinkedHashMap<>();
		for (String path : List.of(companion.systemPath(), companion.micPath())) {
			Long delay = RecordingDiagnostics.getCompanionAudioStartDelayMs(path);
			if (delay != null) {
				delays.put(path, delay);
			}
		}
		return new NormalizeSources(system, mic, delays);
	}

	private void normalize(String file, Path out, Format format, boolean copyVideo,
			BooleanSupplier cancelled) throws IOException {
		MediaMeta meta = probe(file);
		NormalizeSources sources = companionSources(file);
		NormalizePlan plan = planNormalize(meta, sources, format, copyVideo);
		List<String> args = new ArrayList<>(plan.args());
		// The placeholder stands in for the input path in the pure plan builder.
		args.set(args.indexOf("placeholder"), file);
		args.add(out.toString());
		runFfmpeg(args, cancelled);
	}

	private @Nullable RecordingWebcamSource linked(String video) throws IOException {
		RecordingSessionManifest.RecordingSessionData session =
				RecordingSessionManifest.resolveRecordingSession(video);
		if (session == null || session.webcamPath() == null) {
			return null;
		}
		Path webcamPath = Path.of(session.webcamPath()).toRealPath();
		Path videoParent = Path.of(video).toAbsolutePath().normalize().getParent();
		if (videoParent != null && webcamPath.getParent() != null
				&& !webcamPath.getParent().equals(videoParent)) {
			throw new IOException("Linked webcam is outside the recording folder");
		}
		List<VisibleRange> visibleRanges = null;
		try {
			JsonNode parsed = Json.parseJsonWithByteOrderMark(
					Files.readString(Path.of(video + ".webcam-ranges.json"), StandardCharsets.UTF_8));
			if (parsed.isArray()) {
				List<VisibleRange> ranges = new ArrayList<>();
				for (JsonNode range : parsed) {
					ranges.add(new VisibleRange(
							Math.round(range.get("startMs").asDouble()),
							Math.round(range.get("endMs").asDouble())));
				}
				visibleRanges = List.copyOf(ranges);
			}
		} catch (IOException | RuntimeException missing) {
			// Original recordings have no range file.
		}
		return new RecordingWebcamSource(
				webcamPath.toString(), session.timeOffsetMs(), visibleRanges);
	}

	private @Nullable RecordingWebcamSource composeSequenceWebcam(
			String current,
			String added,
			String output,
			Path work,
			long baseDurationMs,
			long addedDurationMs,
			@Nullable RecordingWebcamSource currentWebcam,
			BooleanSupplier cancelled) throws IOException {
		RecordingWebcamSource base = currentWebcam != null ? currentWebcam : linked(current);
		if (currentWebcam != null && currentWebcam.sourcePath() != null
				&& !Files.isReadable(Path.of(currentWebcam.sourcePath()))) {
			throw new IOException("Current webcam is no longer available");
		}
		RecordingWebcamSource next = linked(added);
		String firstPath = base != null && base.sourcePath() != null
				? base.sourcePath()
				: (next != null ? next.sourcePath() : null);
		if (firstPath == null) {
			return null;
		}
		MediaMeta meta = probe(firstPath);
		int width = (int) Math.ceil(meta.width() / 2.0) * 2;
		int height = (int) Math.ceil(meta.height() / 2.0) * 2;
		List<VisibleRange> ranges = new ArrayList<>();
		List<Object[]> segments = List.of(
				new Object[] {0, base, baseDurationMs, 0L},
				new Object[] {1, next, addedDurationMs, baseDurationMs});
		for (Object[] segment : segments) {
			int index = (int) segment[0];
			RecordingWebcamSource source = (RecordingWebcamSource) segment[1];
			long durationMs = (long) segment[2];
			long offsetMs = (long) segment[3];
			double duration = durationMs / 1000.0;
			long delayMs = source == null ? 0 : source.timeOffsetMs();
			MediaMeta info = source != null && source.sourcePath() != null
					? probe(source.sourcePath())
					: new MediaMeta(0, 0, 0, 0, false);
			WebcamSegmentPlan plan = planWebcamSegment(
					source == null ? null : source.sourcePath(),
					width,
					height,
					delayMs,
					duration,
					offsetMs,
					durationMs,
					info.durationSeconds());
			List<String> args = new ArrayList<>(plan.args());
			args.add(work.resolve("webcam-" + index + ".mp4").toString());
			runFfmpeg(args, cancelled);
			if (source != null && source.sourcePath() != null) {
				List<VisibleRange> active = source.visibleRanges() != null
						? source.visibleRanges()
						: List.of(new VisibleRange(
								Math.max(0, delayMs),
								Math.round(Math.min(durationMs, info.durationSeconds() * 1000 + delayMs))));
				for (VisibleRange range : active) {
					long startMs = Math.max(0, range.startMs());
					long endMs = Math.min(durationMs, range.endMs());
					if (endMs > startMs) {
						ranges.add(new VisibleRange(offsetMs + startMs, offsetMs + endMs));
					}
				}
			}
		}
		Files.writeString(work.resolve("webcam-list.txt"),
				"file 'webcam-0.mp4'\nfile 'webcam-1.mp4'\n", StandardCharsets.UTF_8);
		String webcamPath = sequenceWebcamOutputs(output).get(0);
		runFfmpeg(List.of(
				"-f", "concat",
				"-safe", "1",
				"-i", work.resolve("webcam-list.txt").toString(),
				"-an",
				"-c:v", "copy",
				"-movflags", "+faststart",
				webcamPath), cancelled);
		RecordingSessionManifest.persistRecordingSessionManifest(
				new RecordingSessionManifest.RecordingSessionData(output, webcamPath, 0));
		ArrayNode rangesJson = Json.nodeFactory().arrayNode();
		for (VisibleRange range : ranges) {
			ObjectNode node = rangesJson.addObject();
			node.put("startMs", range.startMs());
			node.put("endMs", range.endMs());
		}
		Json.writeFile(Path.of(output + ".webcam-ranges.json"), rangesJson);
		return new RecordingWebcamSource(webcamPath, 0, List.copyOf(ranges));
	}

	/**
	 * {@code importRecording}: appends one library recording to the current video.
	 *
	 * @param currentWebcam the webcam source linked to the current video, or null when it has none
	 */
	public ImportResult importRecording(
			String currentPath, String recordingPath,
			@Nullable RecordingWebcamSource currentWebcam) throws IOException {
		return importRecording(currentPath, recordingPath, currentWebcam, null);
	}

	/**
	 * {@code importRecording} with cancellation: {@code cancelled} is polled before every ffmpeg
	 * spawn and a watchdog destroys the running process when it flips. The partial outputs are
	 * cleaned up like any other failure.
	 */
	public ImportResult importRecording(
			String currentPath, String recordingPath,
			@Nullable RecordingWebcamSource currentWebcam,
			@Nullable BooleanSupplier cancelled) throws IOException {
		BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
		if (cancellation.getAsBoolean()) {
			throw new IOException("Import canceled");
		}
		RecordingLibrary.RecordingLibraryEntry entry = library.listRecordings(false).stream()
				.filter(candidate -> candidate.path().equals(recordingPath))
				.findFirst()
				.orElseThrow(() -> new IOException("Recording is no longer in Videos"));
		Path current = Path.of(currentPath).toAbsolutePath().normalize();
		if (!Files.isReadable(current)) {
			throw new IOException("Current video is no longer available");
		}
		Path root = recordingsDir.get().resolve(".gravaai-media");
		Files.createDirectories(root);
		MediaMeta base = probe(current.toString());
		Format format = new Format(
				(int) Math.ceil(base.width() / 2.0) * 2,
				(int) Math.ceil(base.height() / 2.0) * 2,
				base.fps());
		String id = UUID.randomUUID().toString();
		Path work = Files.createTempDirectory(root, "import-");
		String output = root.resolve(id + ".mp4").toString();
		String stem = output.substring(0, output.length() - 4);
		List<String> outputs = recordingImportOutputs(output);
		try {
			Path normalizedBase = work.resolve("base.mkv");
			Path normalizedNew = work.resolve("new.mkv");
			boolean copyVideo = SequenceSource.isLibrarySequenceSource(current.toString());
			normalize(current.toString(), normalizedBase, format, copyVideo, cancellation);
			normalize(entry.path(), normalizedNew, format, false, cancellation);
			MediaMeta baseMeta = probe(normalizedBase.toString());
			MediaMeta newMeta = probe(normalizedNew.toString());
			Files.writeString(work.resolve("list.txt"), "file 'base.mkv'\nfile 'new.mkv'\n",
					StandardCharsets.UTF_8);
			Path combined = work.resolve("combined.mkv");
			runFfmpeg(List.of(
					"-f", "concat",
					"-safe", "1",
					"-i", work.resolve("list.txt").toString(),
					"-map", "0",
					"-c", "copy",
					combined.toString()), cancellation);
			runFfmpeg(List.of(
					"-i", combined.toString(),
					"-filter_complex", "[0:a:0][0:a:1]amix=inputs=2:normalize=0[mix]",
					"-map", "0:v:0",
					"-map", "[mix]",
					"-c:v", "copy",
					"-c:a", "aac",
					"-b:a", "192k",
					"-movflags", "+faststart",
					output,
					"-map", "0:a:0",
					"-c:a", "pcm_s16le",
					stem + ".system.wav",
					"-map", "0:a:1",
					"-c:a", "pcm_s16le",
					stem + ".mic.wav"), cancellation);
			long sourceStartMs = Math.round(baseMeta.durationSeconds() * 1000);
			MediaMeta entryMeta = probe(entry.path());
			List<List<CursorTelemetryPoint>> cursorChunks = new ArrayList<>();
			List<Long> cursorOffsets = new ArrayList<>();
			List<MediaMeta> cursorMetas = new ArrayList<>();
			for (Object[] chunk : List.of(
					new Object[] {current.toString(), 0L, base},
					new Object[] {entry.path(), sourceStartMs, entryMeta})) {
				cursorChunks.add(readCursorTelemetry((String) chunk[0]));
				cursorOffsets.add((Long) chunk[1]);
				cursorMetas.add((MediaMeta) chunk[2]);
			}
			CursorTelemetry.writeCursorTelemetry(
					output, mergeCursorSamples(cursorChunks, cursorOffsets, cursorMetas, format));
			List<List<KeystrokeEvent>> keystrokeChunks = new ArrayList<>();
			List<Long> keystrokeOffsets = new ArrayList<>();
			for (Object[] chunk : List.of(
					new Object[] {current.toString(), 0L},
					new Object[] {entry.path(), sourceStartMs})) {
				keystrokeChunks.add(readKeystrokeTelemetry((String) chunk[0]));
				keystrokeOffsets.add((Long) chunk[1]);
			}
			KeystrokeTelemetry.writeKeystrokeTelemetry(
					output, mergeKeystrokeEvents(keystrokeChunks, keystrokeOffsets));
			RecordingWebcamSource webcam = composeSequenceWebcam(
					current.toString(),
					entry.path(),
					output,
					work,
					sourceStartMs,
					Math.round(newMeta.durationSeconds() * 1000),
					currentWebcam,
					cancellation);
			return new ImportResult(
					output,
					sourceStartMs,
					Math.round(newMeta.durationSeconds() * 1000),
					sourceStartMs + Math.round(newMeta.durationSeconds() * 1000),
					webcam);
		} catch (IOException | RuntimeException error) {
			for (String file : outputs) {
				try {
					Files.deleteIfExists(Path.of(file));
				} catch (IOException ignored) {
					// Best-effort cleanup.
				}
			}
			throw error;
		} finally {
			deleteRecursively(work);
		}
	}

	private List<CursorTelemetryPoint> readCursorTelemetry(String videoPath) {
		try {
			String content = Files.readString(
					Path.of(RecordingPaths.getTelemetryPathForVideo(videoPath)), StandardCharsets.UTF_8);
			return CursorTelemetry.normalizeCursorTelemetrySamples(Json.parseJsonWithByteOrderMark(content));
		} catch (IOException | RuntimeException missing) {
			return List.of();
		}
	}

	private List<KeystrokeEvent> readKeystrokeTelemetry(String videoPath) {
		try {
			return KeystrokeTelemetry.readKeystrokeTelemetry(videoPath);
		} catch (IOException | RuntimeException missing) {
			return List.of();
		}
	}

	/** {@code discardRecordingImport}: removes every file one import produced. */
	public void discardRecordingImport(String output) throws IOException {
		for (String file : recordingImportOutputs(output)) {
			Files.deleteIfExists(Path.of(file));
		}
	}

	private static void deleteRecursively(Path root) {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Best-effort cleanup.
				}
			});
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not clean the import workspace: " + error);
		}
	}
}
