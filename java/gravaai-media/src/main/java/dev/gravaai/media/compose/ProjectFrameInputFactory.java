package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.media.ffmpeg.ColourInfo;
import dev.gravaai.media.ffmpeg.DecodedFrame;
import dev.gravaai.media.ffmpeg.FrameConverter;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.media.ffmpeg.VideoDecoder;
import dev.gravaai.media.playback.WebcamSync;

/**
 * Production {@link FrameAtTime.FrameInputFactory}: resolves the full compositor
 * {@link FrameInput} (decoded source/webcam frames plus every editor overlay) for a timeline time,
 * exactly like the preview/golden mapping. This is the single resolver preview, export and
 * filmstrip must share (plan §5.8/§5.10).
 *
 * <p>Decodes forward ({@link MediaFrameReader#decodeForward}: one decode per source frame for
 * sequential callers like export, a precise seek only for backward or long jumps) with
 * {@link FrameConverter} on the calling thread and reuses two direct RGBA buffers per media in
 * turn, so the factory is single-caller/single-threaded (export thread or filmstrip caller). The
 * decoded {@link RgbaFrame}s stay valid until the {@link #inputAtTime} call after next (the
 * export decodes frame N+1 while the GPU uploads frame N). {@code wallpaperRoot} is the
 * directory holding {@code wallpapers/} (the app's assets or the repo's {@code public/}); a
 * missing asset degrades to black.</p>
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class ProjectFrameInputFactory implements FrameAtTime.FrameInputFactory, AutoCloseable {

	private final ProjectEditorState editor;
	private final Path videoPath;
	private final Path webcamPath;
	private final List<CursorSample> cursorSamples;
	private final double sourceDurationSeconds;
	private final long webcamTimeOffsetMs;
	private final Path wallpaperRoot;
	private final int frameRate;
	private final MediaFrameReader videoFrames;
	private final @Nullable MediaFrameReader webcamFrames;
	private @Nullable Background staticBackground;
	// The editor state is fixed for this factory, so the overlay specs resolve once (the keystroke
	// spec reads the .keys.json sidecar).
	private final LayoutSpec layout;
	private final ZoomSpec zoom;
	private final FrameStyle style;
	private final CursorSpec cursorSpec;
	private final WebcamSpec webcamSpec;
	private final List<AnnotationSpec> annotations;
	private final CaptionSpec captions;
	private final KeystrokeSpec keystrokes;
	private final FadeSpec fade;
	private @Nullable MediaFrameReader wallpaperFrames;
	private boolean closed;

	/**
	 * @param editor the resolved project editor state
	 * @param videoPath the project's source recording
	 * @param webcamPath the linked webcam recording, or null
	 * @param cursorSamples resolved cursor telemetry (pruned/sorted by the caller), or empty
	 * @param sourceDurationSeconds source recording duration, 0 to probe it
	 * @param webcamTimeOffsetMs webcam start offset from the recording session manifest
	 * @param wallpaperRoot directory containing {@code wallpapers/}, or null for black fallback
	 * @param frameRate output frame rate used for the {@link FrameInput#frameIndex()}
	 */
	public ProjectFrameInputFactory(ProjectEditorState editor, Path videoPath, Path webcamPath,
			List<CursorSample> cursorSamples, double sourceDurationSeconds,
			long webcamTimeOffsetMs, Path wallpaperRoot, int frameRate) {
		this.editor = Objects.requireNonNull(editor, "editor");
		this.videoPath = Objects.requireNonNull(videoPath, "videoPath");
		this.webcamPath = webcamPath;
		List<CursorSample> samples = cursorSamples == null ? List.of() : cursorSamples;
		this.cursorSamples = samples.stream()
				.sorted(Comparator.comparingDouble(CursorSample::timeSeconds))
				.toList();
		this.sourceDurationSeconds = sourceDurationSeconds;
		this.webcamTimeOffsetMs = webcamTimeOffsetMs;
		this.wallpaperRoot = wallpaperRoot;
		this.frameRate = frameRate > 0 ? frameRate : 60;
		this.videoFrames = new MediaFrameReader(videoPath, sourceDurationSeconds, true);
		this.webcamFrames = webcamPath == null ? null : new MediaFrameReader(webcamPath, 0, false);
		this.layout = ProjectEditorInputs.layout(editor);
		this.zoom = ProjectEditorInputs.zoomSpec(editor);
		this.style = ProjectEditorInputs.frameStyle(editor);
		this.cursorSpec = ProjectEditorInputs.cursorSpec(editor);
		this.webcamSpec = ProjectEditorInputs.webcamSpec(editor);
		this.annotations = ProjectEditorInputs.annotationSpecs(editor);
		this.captions = ProjectEditorInputs.captionSpec(editor);
		this.keystrokes = ProjectEditorInputs.keystrokeSpec(editor, videoPath);
		this.fade = ProjectEditorInputs.fadeSpec(editor);
	}

	/** Convenience: probes the media duration, empty cursor telemetry, black wallpapers, 60 fps. */
	public ProjectFrameInputFactory(ProjectEditorState editor, Path videoPath, Path webcamPath) {
		this(editor, videoPath, webcamPath, List.of(), 0, 0, null, 60);
	}

	@Override
	public FrameInput inputAtTime(double timelineSeconds) {
		if (closed) {
			throw new IllegalStateException("project frame input factory is closed");
		}
		if (!Double.isFinite(timelineSeconds)) {
			throw new IllegalArgumentException("timelineSeconds must be finite");
		}
		long timelineMs = Math.round(timelineSeconds * 1000);
		Double sourceSeconds = mapTimelineToSourceSeconds(timelineSeconds);
		RgbaFrame sourceFrame = sourceSeconds == null ? null
				: videoFrames.decodeForward(sourceSeconds);
		// Source-time overlays (captions, keystrokes, cursor, webcam) follow the clip mapping like
		// the TS exporter; a gap between clips reports source time 0 there as well.
		double sourceTime = sourceSeconds == null ? 0 : sourceSeconds;
		RgbaFrame webcamFrame = null;
		if (webcamFrames != null && webcamSpec.enabled()
				&& webcamSpec.isVisibleAt(sourceTime * 1000)) {
			double webcamSeconds = WebcamSync.getWebcamMediaTargetTimeSeconds(sourceTime,
					webcamFrames.durationSeconds(), (double) webcamTimeOffsetMs);
			webcamFrame = webcamFrames.decodeForward(webcamSeconds);
		}
		return new FrameInput(
				Math.round(timelineSeconds * frameRate),
				timelineSeconds,
				sourceFrame,
				webcamFrame,
				cursorSamples,
				backgroundAt(timelineSeconds),
				editor.backgroundBlur(),
				layout,
				zoom,
				style,
				cursorSpec,
				webcamSpec,
				annotations,
				captions,
				keystrokes,
				fade,
				ProjectEditorInputs.sceneSpec(editor, timelineMs),
				sourceTime);
	}

	/**
	 * The wallpaper for a timeline time. Static wallpapers resolve once (the compositor caches the
	 * rendered background by identity); a video wallpaper loops over its duration like
	 * {@code syncBackgroundFrame}.
	 */
	private @Nullable Background backgroundAt(double timelineSeconds) {
		Path video = ProjectEditorInputs.videoWallpaperPath(editor, wallpaperRoot);
		if (video == null) {
			if (staticBackground == null) {
				staticBackground = ProjectEditorInputs.background(editor, wallpaperRoot);
			}
			return staticBackground;
		}
		if (wallpaperFrames == null) {
			wallpaperFrames = new MediaFrameReader(video, 0, true);
		}
		double duration = wallpaperFrames.durationSeconds();
		double loopSeconds = duration > 0
				? ((timelineSeconds % duration) + duration) % duration
				: Math.max(0, timelineSeconds);
		RgbaFrame frame = wallpaperFrames.decodeForward(loopSeconds);
		return frame == null ? Background.black() : new Background.VideoFrame(frame);
	}

	/** Maps a timeline time to the source timestamp through the clip sequence. */
	private @Nullable Double mapTimelineToSourceSeconds(double timelineSeconds) {
		List<ClipRegion> clips = editor.clipRegions() == null ? List.of()
				: editor.clipRegions();
		if (clips.isEmpty()) {
			return Math.max(0, timelineSeconds);
		}
		double timeMs = timelineSeconds * 1000;
		ClipRegion clip = Clips.findClipAtTimelineTime(timeMs, clips);
		if (clip == null) {
			List<ClipRegion> sorted = Clips.sortClipRegions(clips);
			ClipRegion last = sorted.get(sorted.size() - 1);
			if (Math.abs(timeMs - last.endMs()) < 1e-7) {
				clip = last;
			} else {
				return null;
			}
		}
		double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
		double sourceMs = Clips.getClipSourceStartMs(clip) + (timeMs - clip.startMs()) * speed;
		return Math.max(0, sourceMs / 1000.0);
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		videoFrames.close();
		if (webcamFrames != null) {
			webcamFrames.close();
		}
		if (wallpaperFrames != null) {
			wallpaperFrames.close();
		}
	}
}
