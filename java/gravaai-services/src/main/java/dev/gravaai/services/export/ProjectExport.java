package dev.gravaai.services.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.ClipTransitions;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.media.export.CaptionSidecars;
import dev.gravaai.media.export.ExportAudioGraph;
import dev.gravaai.media.export.ExportAudioPlan;
import dev.gravaai.media.export.ExportAudioRouting;
import dev.gravaai.media.export.ExportAudioSegments;
import dev.gravaai.media.export.ExportDimensions;
import dev.gravaai.media.export.ExportFrameProvider;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.media.export.ExportRunner;
import dev.gravaai.media.export.GifExportSettings;
import dev.gravaai.media.export.Mp4ExportBitrate;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.media.ffmpeg.VideoStreamInfo;
import dev.gravaai.services.export.ExportCoordinator;
import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Assembles one export from an editor project (plan §5.10/P2.9 acceptance): resolves the output
 * format/size/frame rate/bitrate from {@link ExportPreferences}, builds the single-libavfilter
 * {@link ExportAudioPlan} from the project's clips/trim/speed regions/audio regions/source-track
 * settings, maps caption cues to sidecar entries, and hands everything to
 * {@link ExportCoordinator}.
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

	/** Everything one project export needs; {@code sourceDurationSeconds == 0} probes the video. */
	public record Input(ProjectEditorState editor, Path videoPath, Path webcamPath,
			List<Path> companionAudioPaths, ExportPreferences preferences, Path destination,
			boolean reveal, boolean overwrite, double sourceDurationSeconds) {
		public Input {
			companionAudioPaths = companionAudioPaths == null ? List.of()
					: List.copyOf(companionAudioPaths);
			if (editor == null || videoPath == null || preferences == null
					|| destination == null) {
				throw new IllegalArgumentException(
						"editor, videoPath, preferences and destination are required");
			}
		}

		public static Input of(ProjectEditorState editor, Path videoPath,
				ExportPreferences preferences, Path destination) {
			return new Input(editor, videoPath, null, List.of(), preferences, destination, false,
					false, 0);
		}
	}

	/** The assembled export, exposed for tests and for the frame-provider construction. */
	public record Prepared(ExportCoordinator.Request request, ExportAudioPlan audioPlan,
			CaptionSidecars.Payload captionPayload, ExportFormat format, int outputWidth,
			int outputHeight, int frameRate, int bitrate, double durationSeconds,
			Path videoPath, Path webcamPath, List<Path> audioInputs) {}

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
		return ExportRunner.run(prepared.request().toSpec(), provider,
				dev.gravaai.media.export.ExportProgressListener.NONE,
				dev.gravaai.media.export.ExportCancellation.NONE, publisher);
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
				tracks.add(new ExportAudioGraph.TrackInput(track.path(), track.gain()));
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
		return ExportAudioGraph.build(new ExportAudioGraph.GraphInput(tracks, regions, segments,
				fades, timelineDurationMs, resolved.masterGain(), muted));
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
			settings.put(key, new ExportAudioRouting.TrackSetting(volume, normalize));
		}
		return settings;
	}

	private static CaptionSidecars.Payload buildCaptionPayload(ProjectEditorState editor,
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
