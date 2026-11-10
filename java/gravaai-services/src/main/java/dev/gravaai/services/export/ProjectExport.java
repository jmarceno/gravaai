package dev.gravaai.services.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.project.AudioCleanup;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.ClipTransitions;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.SourceAudioSettings;
import dev.gravaai.core.project.SourceAudioTrackSettings;
import dev.gravaai.core.project.ClickSoundStyle;
import dev.gravaai.core.project.KeySoundSettings;
import dev.gravaai.media.audio.AudioCleanupFilters;
import dev.gravaai.media.audio.ClickSoundHits;
import dev.gravaai.media.audio.ClickSounds;
import dev.gravaai.media.audio.KeySoundHits;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.media.export.CaptionSidecars;
import dev.gravaai.media.export.ClickSoundWav;
import dev.gravaai.media.export.ExportAudioGraph;
import dev.gravaai.media.export.ExportAudioLoudness;
import dev.gravaai.media.export.ExportAudioPlan;
import dev.gravaai.media.export.ExportAudioRouting;
import dev.gravaai.media.export.ExportAudioSegments;
import dev.gravaai.media.export.ExportDimensions;
import dev.gravaai.media.export.ExportFrameProvider;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.media.export.ExportRunner;
import dev.gravaai.media.export.FfmpegCli;
import dev.gravaai.media.export.GifExportSettings;
import dev.gravaai.media.export.Mp4ExportBitrate;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.media.ffmpeg.VideoStreamInfo;
import dev.gravaai.services.export.ExportCoordinator;
import dev.gravaai.services.recording.KeystrokePresses;
import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Assembles one export from an editor project (plan §5.10/P2.9 acceptance): resolves the output
 * format/size/frame rate/bitrate from {@link ExportPreferences}, builds the single-libavfilter
 * {@link ExportAudioPlan} from the project's clips/trim/speed regions/audio regions/source-track
 * settings (gain, normalize and the edit-time cleanup with its programme loudness target), maps
 * caption cues to sidecar entries, and hands everything to {@link ExportCoordinator}.
 *
 * <p><b>Contiguous main-source projects are the supported contract</b> (for example the
 * {@code 11-export-2min.gravaai} acceptance fixture: one clip 0–120 s). The audio graph consumes
 * contiguous trim/speed segments; timelines with clip-sequence gaps/silences (split/delete gaps,
 * fade-black holds) are documented as a gap until the graph gains output-start padding.
 *
 * <p>The caller owns the frame pipeline: build a {@code CompositorExportFrameProvider} with
 * {@code width/height/frameRate} from {@link Prepared} and the production
 * {@code ProjectFrameInputFactory}, reuse it (reset() between GIF passes) and close it afterwards.
 */
public final class ProjectExport {

	private static final System.Logger LOG = System.getLogger("gravaai.services.export");

	/**
	 * Resolves the rendered denoised copy of a source audio file for a track whose noise reduction
	 * is on ({@link dev.gravaai.services.denoise.DenoiseCache}). Answering null keeps the track on
	 * the ffmpeg fallback chain. An export on a background thread passes a resolver that renders
	 * and waits; callers that only need the export's geometry pass {@link #NONE}.
	 */
	@FunctionalInterface
	public interface DenoisedSources {

		/** No rendered copies: every denoised track uses the fallback chain. */
		DenoisedSources NONE = source -> null;

		@Nullable Path forSource(Path source);
	}

	/**
	 * Everything one project export needs; {@code sourceDurationSeconds == 0} probes the video.
	 *
	 * @param denoised where the rendered denoised copies of the source tracks come from
	 */
	public record Input(ProjectEditorState editor, Path videoPath, @Nullable Path webcamPath,
			List<Path> companionAudioPaths, ExportPreferences preferences, Path destination,
			boolean reveal, boolean overwrite, double sourceDurationSeconds,
			List<CursorSample> cursorSamples, DenoisedSources denoised) {

		/** An input without rendered denoised copies (geometry probes, tests). */
		public Input(ProjectEditorState editor, Path videoPath, @Nullable Path webcamPath,
				List<Path> companionAudioPaths, ExportPreferences preferences, Path destination,
				boolean reveal, boolean overwrite, double sourceDurationSeconds,
				List<CursorSample> cursorSamples) {
			this(editor, videoPath, webcamPath, companionAudioPaths, preferences, destination,
					reveal, overwrite, sourceDurationSeconds, cursorSamples, DenoisedSources.NONE);
		}

		public Input {
			companionAudioPaths = companionAudioPaths == null ? List.of()
					: List.copyOf(companionAudioPaths);
			cursorSamples = cursorSamples == null ? List.of() : List.copyOf(cursorSamples);
			denoised = denoised == null ? DenoisedSources.NONE : denoised;
			if (editor == null || videoPath == null || preferences == null
					|| destination == null) {
				throw new IllegalArgumentException(
						"editor, videoPath, preferences and destination are required");
			}
		}

		/** The same export reading its denoised tracks from {@code sources}. */
		public Input withDenoised(DenoisedSources sources) {
			return new Input(editor, videoPath, webcamPath, companionAudioPaths, preferences,
					destination, reveal, overwrite, sourceDurationSeconds, cursorSamples, sources);
		}

		public static Input of(ProjectEditorState editor, Path videoPath,
				ExportPreferences preferences, Path destination) {
			return new Input(editor, videoPath, null, List.of(), preferences, destination, false,
					false, 0, List.of());
		}
	}

	/**
	 * The assembled export, exposed for tests and for the frame-provider construction.
	 *
	 * @param captionPayload the caption sidecar payload, or null when there are no captions
	 * @param webcamPath the linked webcam track, or null when the project has none
	 */
	public record Prepared(ExportCoordinator.Request request, ExportAudioPlan audioPlan,
			CaptionSidecars.@Nullable Payload captionPayload, @Nullable ExportFormat format,
			int outputWidth, int outputHeight, int frameRate, int bitrate, double durationSeconds,
			Path videoPath, @Nullable Path webcamPath, List<Path> audioInputs) {}

	private ProjectExport() {}

	/** Builds the coordinator request, audio plan and caption payload for one project. */
	public static Prepared prepare(Input input) {
		Path videoPath = input.videoPath().toAbsolutePath().normalize();
		if (!Files.isRegularFile(videoPath)) {
			throw new IllegalArgumentException("video file not found: " + videoPath);
		}
		MediaInfo info = MediaProbe.probe(videoPath);
		VideoStreamInfo video = info.video().orElseThrow(() -> new IllegalArgumentException(
				"the video has no video stream: " + videoPath));
		int sourceWidth = video.width();
		int sourceHeight = video.height();
		if (video.rotationDegrees() % 180 != 0) {
			int swap = sourceWidth;
			sourceWidth = sourceHeight;
			sourceHeight = swap;
		}
		double sourceDurationSeconds = input.sourceDurationSeconds() > 0
				? input.sourceDurationSeconds()
				: info.durationSeconds();
		if (sourceDurationSeconds <= 0) {
			throw new IllegalArgumentException("unknown source duration: " + videoPath);
		}
		ProjectEditorState editor = input.editor();
		ExportPreferences preferences = input.preferences();
		long sourceDurationMs = Math.round(sourceDurationSeconds * 1000);
		long timelineDurationMs = timelineDurationMs(editor, sourceDurationMs);
		double durationSeconds = Math.max(0.001, timelineDurationMs / 1000.0);

		CropRegion crop = editor.cropRegion();
		ExportDimensions.Size base = ExportDimensions.calculateMp4SourceDimensions(sourceWidth,
				sourceHeight, editor.aspectRatio() == null ? "native" : editor.aspectRatio(),
				new ExportDimensions.CropBounds(crop == null ? 1 : crop.width(),
						crop == null ? 1 : crop.height()));

		ExportFormat format = preferences.exportFormat();
		int frameRate;
		int outputWidth;
		int outputHeight;
		int bitrate;
		boolean gifLoop = false;
		if (format == ExportFormat.GIF) {
			frameRate = validGifFrameRate(preferences.gifFrameRate());
			// React sizes the GIF canvas from the raw (post-rotation) source dims capped by the
			// preset (exportStartSettings.ts), not from the aspect/crop-adjusted base.
			ExportDimensions.Size gif = gifDimensions(sourceWidth, sourceHeight, preferences);
			outputWidth = gif.width();
			outputHeight = gif.height();
			bitrate = 0;
			gifLoop = preferences.gifLoop();
		} else {
			frameRate = validMp4FrameRate(preferences.mp4FrameRate());
			ExportDimensions.Size dimensions = ExportDimensions.calculateMp4ExportDimensions(
					base.width(), base.height(), preferences.exportQuality());
			outputWidth = dimensions.width();
			outputHeight = dimensions.height();
			bitrate = Mp4ExportBitrate.getMp4ExportBitrate(outputWidth, outputHeight, frameRate,
					preferences.exportQuality(), preferences.exportEncodingMode());
		}

		ExportAudioPlan audioPlan = buildAudioPlan(input, videoPath, info, sourceDurationMs,
				timelineDurationMs);
		CaptionSidecars.Payload captionPayload = buildCaptionPayload(editor,
				preferences.includeCaptionSidecar());
		ExportCoordinator.Request request = new ExportCoordinator.Request(format, outputWidth,
				outputHeight, frameRate, bitrate, preferences.exportEncodingMode(), durationSeconds,
				gifLoop, input.destination(), input.reveal(), input.overwrite(), captionPayload,
				audioPlan);
		return new Prepared(request, audioPlan, captionPayload, format, outputWidth, outputHeight,
				frameRate, bitrate, durationSeconds, videoPath, input.webcamPath(),
				audioPlan.inputs());
	}

	/**
	 * Prepares and starts the export on the shared coordinator (one at a time).
	 *
	 * @return {@code false} when another export is already running
	 */
	public static boolean start(Input input, ExportFrameProvider provider,
			ExportCoordinator coordinator, ExportCoordinator.Listener listener) throws IOException {
		Prepared prepared = prepare(input);
		return coordinator.start(prepared.request(), provider, listener);
	}

	/** Runs one export synchronously through {@link ExportRunner} (tests / headless callers). */
	public static ExportRunner.Outcome run(Input input, ExportFrameProvider provider,
			ExportPublisher publisher) throws IOException {
		Prepared prepared = prepare(input);
		try {
			return ExportRunner.run(prepared.request().toSpec(), provider,
					dev.gravaai.media.export.ExportProgressListener.NONE,
					dev.gravaai.media.export.ExportCancellation.NONE, publisher);
		} finally {
			Files.deleteIfExists(clickBusFor(input.destination()));
			Files.deleteIfExists(keyBusFor(input.destination()));
		}
	}

	private static long timelineDurationMs(ProjectEditorState editor, long sourceDurationMs) {
		long clipsEnd = Clips.getTimelineDurationMs(editor.clipRegions(), sourceDurationMs);
		return clipsEnd > 0 ? clipsEnd : sourceDurationMs;
	}

	private static int validMp4FrameRate(int frameRate) {
		for (int allowed : ExportPreferences.MP4_FRAME_RATES) {
			if (allowed == frameRate) {
				return frameRate;
			}
		}
		return 30;
	}

	private static int validGifFrameRate(int frameRate) {
		return GifExportSettings.isValidGifFrameRate(frameRate) ? frameRate : 20;
	}

	private static ExportDimensions.Size gifDimensions(int sourceWidth, int sourceHeight,
			ExportPreferences preferences) {
		GifExportSettings.Size size = GifExportSettings.calculateOutputDimensions(sourceWidth,
				sourceHeight, preferences.gifSizePreset());
		return new ExportDimensions.Size(size.width(), size.height());
	}

	private static ExportAudioPlan buildAudioPlan(Input input, Path videoPath, MediaInfo info,
			long sourceDurationMs, long timelineDurationMs) {
		List<String> fallbacks = new ArrayList<>();
		if (info.hasAudio()) {
			fallbacks.add(videoPath.toString());
		}
		for (Path companion : input.companionAudioPaths()) {
			if (companion != null && !companion.toString().isBlank()) {
				fallbacks.add(companion.toAbsolutePath().normalize().toString());
			}
		}
		Map<String, ExportAudioRouting.TrackSetting> trackSettings = parseTrackSettings(
				input.editor().defaultSourceAudioTrackSettings());
		ExportAudioRouting.ResolvedPlan resolved = ExportAudioRouting.buildResolvedPlan(
				videoPath.toString(), fallbacks, input.editor().audioRegions(), trackSettings, null,
				null);
		List<ExportAudioGraph.TrackInput> tracks = new ArrayList<>();
		List<ExportAudioGraph.RegionInput> regions = new ArrayList<>();
		for (ExportAudioRouting.ResolvedTrack track : resolved.tracks()) {
			if (track.kind() == ExportAudioRouting.TrackKind.USER) {
				regions.add(new ExportAudioGraph.RegionInput(track.path(), track.timelineStartMs(),
						track.timelineEndMs(), track.gain()));
			} else {
				AudioCleanup cleanup =
						ExportAudioRouting.resolveSourceTrackCleanup(trackSettings, track.kind());
				// Noise reduction plays from the rendered DeepFilterNet copy when the caller
				// can supply one; without it the track stays on the ffmpeg fallback chain.
				Path denoised = cleanup.noiseReduction() == AudioCleanup.NoiseReduction.OFF
						? null : denoisedCopy(input, Path.of(track.path()));
				tracks.add(new ExportAudioGraph.TrackInput(track.path(), track.gain(), cleanup,
						denoised == null ? null : denoised.toString()));
			}
		}
		List<ExportAudioSegments.Segment> segments = buildSegments(input.editor(), sourceDurationMs);
		List<ClipTransitions.FadeWindow> fades = ClipTransitions.buildFadeWindows(
				input.editor().clipRegions());
		List<ExportAudioGraph.MutedRange> muted = new ArrayList<>();
		if (input.editor().clipRegions() != null) {
			for (var clip : input.editor().clipRegions()) {
				if (Boolean.TRUE.equals(clip.muted()) && clip.endMs() > clip.startMs()) {
					muted.add(new ExportAudioGraph.MutedRange(clip.startMs(), clip.endMs()));
				}
			}
		}
		appendClickBus(input, regions);
		// Loudness is a property of the deliverable, not of one track: the first enabled cleanup
		// among the tracks that are actually exported (mic, system, mixed priority; embedded audio
		// resolves as the mixed lane) sets the programme target. The RNNoise model is resolved once
		// here so the graph builder stays a pure string builder.
		SourceAudioTrackSettings typedDefaults =
				input.editor().defaultSourceAudioTrackSettingsTyped();
		List<String> exportableTrackIds = new ArrayList<>();
		for (String trackId : List.of(SourceAudioTrackSettings.MIC, SourceAudioTrackSettings.SYSTEM,
				SourceAudioTrackSettings.MIXED)) {
			for (ExportAudioRouting.ResolvedTrack track : resolved.tracks()) {
				if (track.kind() != ExportAudioRouting.TrackKind.USER
						&& track.kind().settingsTrackId().equals(trackId)) {
					exportableTrackIds.add(trackId);
					break;
				}
			}
		}
		double loudnessTargetLufs = SourceAudioSettings.resolveProgrammeLoudnessTarget(typedDefaults,
				exportableTrackIds);
		Path rnnoiseModel = AudioCleanupFilters.rnnoiseModel();
		ExportAudioGraph.GraphInput graphInput = new ExportAudioGraph.GraphInput(tracks, regions,
				segments, fades, timelineDurationMs, resolved.masterGain(), muted,
				loudnessTargetLufs, rnnoiseModel == null ? null : rnnoiseModel.toString());
		ExportAudioPlan plan = ExportAudioGraph.build(graphInput);
		return normalizeProgrammeLoudness(plan, graphInput, loudnessTargetLufs);
	}

	/**
	 * The source audio files this export would denoise: the exported source tracks whose noise
	 * reduction is on. The editor renders these before it starts an export on the UI thread, so
	 * the export itself never waits for a render there.
	 */
	public static List<Path> noiseReducedSources(Input input) {
		Path videoPath = input.videoPath().toAbsolutePath().normalize();
		if (!Files.isRegularFile(videoPath)) {
			return List.of();
		}
		Map<String, ExportAudioRouting.TrackSetting> trackSettings = parseTrackSettings(
				input.editor().defaultSourceAudioTrackSettings());
		boolean anyNoiseReduction = trackSettings.values().stream().anyMatch(setting ->
				setting.cleanup().noiseReduction() != AudioCleanup.NoiseReduction.OFF);
		if (!anyNoiseReduction) {
			// The common case: no probe, no routing.
			return List.of();
		}
		List<String> fallbacks = new ArrayList<>();
		if (MediaProbe.probe(videoPath).hasAudio()) {
			fallbacks.add(videoPath.toString());
		}
		for (Path companion : input.companionAudioPaths()) {
			if (companion != null && !companion.toString().isBlank()) {
				fallbacks.add(companion.toAbsolutePath().normalize().toString());
			}
		}
		ExportAudioRouting.ResolvedPlan resolved = ExportAudioRouting.buildResolvedPlan(
				videoPath.toString(), fallbacks, input.editor().audioRegions(), trackSettings, null,
				null);
		List<Path> sources = new ArrayList<>();
		for (ExportAudioRouting.ResolvedTrack track : resolved.tracks()) {
			if (track.kind() != ExportAudioRouting.TrackKind.USER
					&& ExportAudioRouting.resolveSourceTrackCleanup(trackSettings, track.kind())
							.noiseReduction() != AudioCleanup.NoiseReduction.OFF) {
				sources.add(Path.of(track.path()));
			}
		}
		return List.copyOf(sources);
	}

	/** The rendered denoised copy of one source track, or null (resolver failures included). */
	private static @Nullable Path denoisedCopy(Input input, Path source) {
		try {
			return input.denoised().forSource(source);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "no denoised copy of " + source.getFileName()
					+ "; using the fallback noise reduction: " + error);
			return null;
		}
	}

	/**
	 * Runs the measurement pass for the programme loudness target and rebuilds the plan with the
	 * linear two-pass stage. A failed measurement (missing ffmpeg, decode error, silent programme)
	 * keeps the single-pass dynamic stage, which still lands near the target.
	 */
	private static ExportAudioPlan normalizeProgrammeLoudness(ExportAudioPlan plan,
			ExportAudioGraph.GraphInput graphInput, double loudnessTargetLufs) {
		if (loudnessTargetLufs == 0 || !plan.hasAudio()) {
			return plan;
		}
		Path ffmpeg;
		try {
			ffmpeg = FfmpegCli.binary();
		} catch (IllegalStateException missing) {
			LOG.log(System.Logger.Level.WARNING,
					"no ffmpeg for the loudness measurement pass; using the dynamic stage: "
							+ missing.getMessage());
			return plan;
		}
		AudioCleanupFilters.MeasuredLoudness measured =
				ExportAudioLoudness.measure(ffmpeg, plan, loudnessTargetLufs);
		if (measured == null) {
			LOG.log(System.Logger.Level.WARNING,
					"the loudness measurement pass failed; using the dynamic stage");
			return plan;
		}
		return ExportAudioGraph.build(graphInput.withMeasured(measured));
	}

	/**
	 * Appends the pre-rendered click and key-press buses as regions (MP4 only; GIF has no audio).
	 * Each render is capped at the last hit plus the sound length, and muted clips do not silence
	 * them — a bus is a region, by design.
	 */
	private static void appendClickBus(Input input, List<ExportAudioGraph.RegionInput> regions) {
		if (input.preferences().exportFormat() == ExportFormat.GIF) {
			return;
		}
		long[] timesMs = ClickSoundHits.timesMs(input.editor().clickSound(),
				input.cursorSamples(), input.editor().clipRegions());
		if (timesMs.length > 0) {
			ClickSoundStyle style = input.editor().clickSound().sound();
			Path clickBus = clickBusFor(input.destination());
			if (ClickSoundWav.write(clickBus, style, input.editor().clickSound().volume(), timesMs)
					!= null) {
				regions.add(new ExportAudioGraph.RegionInput(clickBus.toString(), 0,
						lastHitEndMs(timesMs, ClickSounds.frames(style)), 1));
			}
		}
		KeySoundSettings keySound = input.editor().keySound();
		if (!keySound.enabled()) {
			return;
		}
		long[] keyTimesMs = KeySoundHits.timesMs(keySound,
				KeystrokePresses.load(input.videoPath().toString()), input.editor().clipRegions());
		if (keyTimesMs.length == 0) {
			return;
		}
		Path keyBus = keyBusFor(input.destination());
		if (ClickSoundWav.write(keyBus, keySound.sound(), keySound.volume(), keyTimesMs) != null) {
			regions.add(new ExportAudioGraph.RegionInput(keyBus.toString(), 0,
					lastHitEndMs(keyTimesMs, ClickSounds.frames(keySound.sound())), 1));
		}
	}

	private static long lastHitEndMs(long[] timesMs, int soundFrames) {
		return timesMs[timesMs.length - 1] + (soundFrames * 1000L + 47_999) / 48_000;
	}

	/** The sidecar the key bus writes next to a destination (deleted after the run). */
	private static Path keyBusFor(Path destination) {
		Path absolute = destination.toAbsolutePath().normalize();
		return absolute.resolveSibling(absolute.getFileName() + ".keys.wav");
	}

	/** The sidecar the click bus writes next to a destination (deleted after the run). */
	private static Path clickBusFor(Path destination) {
		Path absolute = destination.toAbsolutePath().normalize();
		return absolute.resolveSibling(absolute.getFileName() + ".clicks.wav");
	}

	private static List<ExportAudioSegments.Segment> buildSegments(ProjectEditorState editor,
			long sourceDurationMs) {
		if (editor.clipRegions() != null && !editor.clipRegions().isEmpty()) {
			// offlineAudioProcessor: with a clip timeline the slices are the clips themselves
			// (source span + clip speed, in sequence); trim and speed regions are not applied.
			// Building from speed regions shifted the audio against the picture, and clip speed
			// never reached the audio.
			List<ExportAudioSegments.Segment> segments = new ArrayList<>();
			for (var clip : dev.gravaai.core.project.Clips.sortClipRegions(editor.clipRegions())) {
				long start = dev.gravaai.core.project.Clips.getClipSourceStartMs(clip);
				long end = Math.min(dev.gravaai.core.project.Clips.getClipSourceEndMs(clip),
						sourceDurationMs > 0 ? sourceDurationMs : Long.MAX_VALUE);
				if (end > start) {
					segments.add(new ExportAudioSegments.Segment(start, end,
							clip.speed() > 0 ? clip.speed() : 1));
				}
			}
			return segments;
		}
		List<ExportAudioSegments.Trim> trims = new ArrayList<>();
		if (editor.trimRegions() != null) {
			for (var trim : editor.trimRegions()) {
				trims.add(ExportAudioSegments.Trim.of(trim));
			}
		}
		List<ExportAudioSegments.Speed> speeds = new ArrayList<>();
		if (editor.speedRegions() != null) {
			for (var speed : editor.speedRegions()) {
				speeds.add(ExportAudioSegments.Speed.of(speed));
			}
		}
		return ExportAudioSegments.buildEditedTrackSourceSegments(sourceDurationMs, trims, speeds);
	}

	private static Map<String, ExportAudioRouting.TrackSetting> parseTrackSettings(
			JsonNode settingsNode) {
		Map<String, ExportAudioRouting.TrackSetting> settings = new LinkedHashMap<>();
		if (settingsNode == null || !settingsNode.isObject()) {
			return settings;
		}
		for (String key : List.of("mic", "system", "mixed")) {
			JsonNode track = settingsNode.path(key);
			if (!track.isObject()) {
				continue;
			}
			double volume = track.path("volume").isNumber() ? track.path("volume").asDouble() : 1;
			boolean normalize = track.path("normalize").isBoolean()
					&& track.path("normalize").asBoolean();
			settings.put(key, new ExportAudioRouting.TrackSetting(volume, normalize,
					AudioCleanup.parse(track)));
		}
		return settings;
	}

	private static CaptionSidecars.@Nullable Payload buildCaptionPayload(ProjectEditorState editor,
			boolean include) {
		if (!include || editor.autoCaptions() == null || editor.autoCaptions().isEmpty()) {
			return null;
		}
		List<CaptionSidecars.Cue> cues = new ArrayList<>();
		for (var cue : editor.autoCaptions()) {
			double startMs = Clips.mapSourceTimeToTimelineTime(cue.startMs(),
					editor.clipRegions());
			double endMs = Clips.mapSourceTimeToTimelineTime(cue.endMs(), editor.clipRegions());
			if (endMs > startMs) {
				cues.add(new CaptionSidecars.Cue(startMs, endMs, cue.text()));
			}
		}
		return cues.isEmpty() ? null
				: new CaptionSidecars.Payload(CaptionSidecars.Format.BOTH, cues);
	}
}
