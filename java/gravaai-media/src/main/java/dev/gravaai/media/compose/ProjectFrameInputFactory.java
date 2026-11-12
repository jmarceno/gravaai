package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

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
import dev.gravaai.media.segmentation.MaskTuning;
import dev.gravaai.media.segmentation.WebcamSegmenter;

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
public final class ProjectFrameInputFactory implements FrameAtTime.FrameInputFactory, AutoCloseable {

	private final ProjectEditorState editor;
	private final Path videoPath;
	private final @Nullable Path webcamPath;
	private final List<CursorSample> cursorSamples;
	private final double sourceDurationSeconds;
	private final long webcamTimeOffsetMs;
	private final @Nullable Path wallpaperRoot;
	private final int frameRate;
	private final int stageWidth;
	private final int stageHeight;
	private final MediaFrameReader videoFrames;
	private final @Nullable MediaFrameReader webcamFrames;
	private final @Nullable WebcamSegmenter webcamSegmenter;
	/** Edits each decoded webcam frame once (eye contact); {@link WebcamFrameFilter#NONE} by default. */
	private WebcamFrameFilter webcamFilter = WebcamFrameFilter.NONE;
	/** The frame the filter last edited, so a frame re-served by the reader is not edited twice. */
	private @Nullable RgbaFrame lastFilteredWebcamFrame;
	// Masks cache per requested webcam media time: consecutive timeline frames inside one decoded
	// webcam frame re-request the same time (the reader's buffers rotate, so frame identity is
	// not a stable key).
	private double lastMaskedWebcamSeconds = Double.NaN;
	private @Nullable RgbaFrame lastWebcamMask;
	private @Nullable Background staticBackground;
	// The editor state is fixed for this factory, so the overlay specs resolve once (the keystroke
	// spec reads the .keys.json sidecar).
	private final LayoutSpec layout;
	private final ZoomSpec zoom;
	private final FrameStyle style;
	private final CursorSpec cursorSpec;
	private final WebcamSpec webcamSpec;
	/** The per-project mask edge shape (Webcam → Advanced), fixed with the editor state. */
	private final MaskTuning maskTuning;
	private final List<AnnotationSpec> annotations;
	private final CaptionSpec captions;
	private final KeystrokeSpec keystrokes;
	private final FadeSpec fade;
	private final GradeSpec grade;
	/** The smart-framing dodge schedule, resolved once on first use when the project opts in. */
	private SmartFraming.@Nullable Plan smartFramingPlan;
	private boolean smartFramingResolved;
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
	public ProjectFrameInputFactory(ProjectEditorState editor, Path videoPath,
			@Nullable Path webcamPath, List<CursorSample> cursorSamples,
			double sourceDurationSeconds, long webcamTimeOffsetMs, @Nullable Path wallpaperRoot,
			int frameRate) {
		this(editor, videoPath, webcamPath, cursorSamples, sourceDurationSeconds,
				webcamTimeOffsetMs, wallpaperRoot, frameRate, null);
	}

	/**
	 * Same as above with the compositor's output size, which smart framing needs to plan its
	 * dodges against the same stage the frames render into.
	 */
	public ProjectFrameInputFactory(ProjectEditorState editor, Path videoPath,
			@Nullable Path webcamPath, List<CursorSample> cursorSamples,
			double sourceDurationSeconds, long webcamTimeOffsetMs, @Nullable Path wallpaperRoot,
			int frameRate, int stageWidth, int stageHeight) {
		this(editor, videoPath, webcamPath, cursorSamples, sourceDurationSeconds,
				webcamTimeOffsetMs, wallpaperRoot, frameRate, null, stageWidth, stageHeight);
	}

	/**
	 * @param webcamSegmenter the person segmenter backing the webcam background blur, or null
	 *        when unavailable (blur then silently renders off even when enabled). The factory
	 *        takes ownership: {@link #close()} closes it with the decoders.
	 */
	public ProjectFrameInputFactory(ProjectEditorState editor, Path videoPath,
			@Nullable Path webcamPath, List<CursorSample> cursorSamples,
			double sourceDurationSeconds, long webcamTimeOffsetMs, @Nullable Path wallpaperRoot,
			int frameRate, @Nullable WebcamSegmenter webcamSegmenter) {
		this(editor, videoPath, webcamPath, cursorSamples, sourceDurationSeconds,
				webcamTimeOffsetMs, wallpaperRoot, frameRate, webcamSegmenter,
				WebcamOverlay.REFERENCE_VIEWPORT_WIDTH, WebcamOverlay.referenceViewportHeight());
	}

	/** Primary: everything above plus the segmenter and the compositor output size. */
	public ProjectFrameInputFactory(ProjectEditorState editor, Path videoPath,
			@Nullable Path webcamPath, List<CursorSample> cursorSamples,
			double sourceDurationSeconds, long webcamTimeOffsetMs, @Nullable Path wallpaperRoot,
			int frameRate, @Nullable WebcamSegmenter webcamSegmenter, int stageWidth,
			int stageHeight) {
		this.editor = Objects.requireNonNull(editor, "editor");
		this.videoPath = Objects.requireNonNull(videoPath, "videoPath");
		this.webcamSegmenter = webcamSegmenter;
		this.webcamPath = webcamPath;
		this.cursorSamples = cursorSamples.stream()
				.sorted(Comparator.comparingDouble(CursorSample::timeSeconds))
				.toList();
		this.sourceDurationSeconds = sourceDurationSeconds;
		this.webcamTimeOffsetMs = webcamTimeOffsetMs;
		this.wallpaperRoot = wallpaperRoot;
		this.frameRate = frameRate > 0 ? frameRate : 60;
		this.stageWidth = Math.max(1, stageWidth);
		this.stageHeight = Math.max(1, stageHeight);
		this.videoFrames = new MediaFrameReader(videoPath, sourceDurationSeconds, true);
		this.webcamFrames = webcamPath == null ? null : new MediaFrameReader(webcamPath, 0, false);
		this.layout = ProjectEditorInputs.layout(editor);
		this.zoom = ProjectEditorInputs.zoomSpec(editor);
		this.style = ProjectEditorInputs.frameStyle(editor);
		this.cursorSpec = ProjectEditorInputs.cursorSpec(editor);
		this.webcamSpec = ProjectEditorInputs.webcamSpec(editor);
		this.maskTuning = ProjectEditorInputs.maskTuning(editor);
		this.annotations = ProjectEditorInputs.annotationSpecs(editor);
		this.captions = ProjectEditorInputs.captionSpec(editor);
		this.keystrokes = ProjectEditorInputs.keystrokeSpec(editor, videoPath);
		this.fade = ProjectEditorInputs.fadeSpec(editor);
		this.grade = ProjectEditorInputs.gradeSpec(editor);
	}

	/** Convenience: probes the media duration, empty cursor telemetry, black wallpapers, 60 fps. */
	public ProjectFrameInputFactory(ProjectEditorState editor, Path videoPath,
			@Nullable Path webcamPath) {
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
		OptionalDouble sourceSeconds = mapTimelineToSourceSeconds(timelineSeconds);
		RgbaFrame sourceFrame = sourceSeconds.isPresent()
				? videoFrames.decodeForward(sourceSeconds.getAsDouble())
				: null;
		// Source-time overlays (captions, keystrokes, cursor, webcam) follow the clip mapping like
		// the TS exporter; a gap between clips reports source time 0 there as well.
		double sourceTime = sourceSeconds.orElse(0);
		RgbaFrame webcamFrame = null;
		double webcamSeconds = 0;
		if (webcamFrames != null && webcamSpec.enabled()
				&& webcamSpec.isVisibleAt(sourceTime * 1000)) {
			webcamSeconds = WebcamSync.getWebcamMediaTargetTimeSeconds(sourceTime,
					webcamFrames.durationSeconds(), (double) webcamTimeOffsetMs);
			webcamFrame = webcamFrames.decodeForward(webcamSeconds);
			if (webcamFrame != null && webcamFrame != lastFilteredWebcamFrame) {
				// The reader re-serves the same frame object while the timeline stays inside one
				// webcam frame; editing it again would apply the correction twice.
				webcamFilter.apply(webcamFrame, webcamFrames.lastPtsSeconds(), timelineSeconds);
				lastFilteredWebcamFrame = webcamFrame;
			}
		}
		RgbaFrame webcamMask = webcamFrame != null && webcamSpec.segmentsPerson()
				? webcamMask(webcamSeconds, webcamFrame)
				: null;
		return new FrameInput(
				Math.round(timelineSeconds * frameRate),
				timelineSeconds,
				sourceFrame,
				webcamFrame,
				webcamMask,
				cursorSamples,
				backgroundAt(timelineSeconds),
				editor.backgroundBlur(),
				layout,
				zoom,
				style,
				cursorSpec,
				ProjectEditorInputs.webcamSpecAt(webcamSpec, editor, timelineMs, plan()),
				annotations,
				captions,
				keystrokes,
				fade,
				ProjectEditorInputs.sceneSpec(editor, timelineMs),
				grade,
				sourceTime);
	}

	/**
	 * Installs the per-frame webcam edit (eye contact). The factory takes ownership and closes it
	 * with the decoders. Call before the first frame.
	 */
	public ProjectFrameInputFactory withWebcamFilter(WebcamFrameFilter filter) {
		webcamFilter.close();
		webcamFilter = Objects.requireNonNull(filter, "filter");
		return this;
	}

	/**
	 * The smart-framing dodge schedule for this project, or null when the project does not opt in.
	 * Resolved once on first use: it probes both media sizes and walks the timeline (the probes
	 * only run for projects that opt in).
	 */
	public SmartFraming.@Nullable Plan plan() {
		if (!smartFramingResolved) {
			smartFramingResolved = true;
			var settings = editor.webcam();
			if (settings != null && settings.smartFraming() && settings.enabled()) {
				smartFramingPlan = SmartFraming.plan(editor, cursorSamples, sourceDurationSeconds,
						videoFrames.frameWidth(), videoFrames.frameHeight(),
						webcamFrames == null ? 0 : webcamFrames.frameWidth(),
						webcamFrames == null ? 0 : webcamFrames.frameHeight(), stageWidth,
						stageHeight);
			}
		}
		return smartFramingPlan;
	}

	/**
	 * The person mask for the decoded webcam frame (webcam background effect), cached per requested
	 * webcam media time so repeat requests (paused repaints, coalesced frames) do not re-run the
	 * segmenter.
	 */
	private @Nullable RgbaFrame webcamMask(double webcamSeconds, RgbaFrame webcamFrame) {
		if (webcamSeconds == lastMaskedWebcamSeconds && lastWebcamMask != null) {
			return lastWebcamMask;
		}
		RgbaFrame mask = webcamSegmenter == null
				? null
				: webcamSegmenter.maskFor(webcamFrame, maskTuning);
		lastMaskedWebcamSeconds = webcamSeconds;
		lastWebcamMask = mask;
		return mask;
	}

	/**
	 * The wallpaper for a timeline time. Static wallpapers resolve once (the compositor caches the
	 * rendered background by identity); a video wallpaper loops over its duration like
	 * {@code syncBackgroundFrame}.
	 */
	private Background backgroundAt(double timelineSeconds) {
		Path video = ProjectEditorInputs.videoWallpaperPath(editor, wallpaperRoot);
		if (video == null) {
			Background cached = staticBackground;
			if (cached == null) {
				cached = ProjectEditorInputs.background(editor, wallpaperRoot);
				staticBackground = cached;
			}
			return cached;
		}
		MediaFrameReader frames = wallpaperFrames;
		if (frames == null) {
			frames = new MediaFrameReader(video, 0, true);
			wallpaperFrames = frames;
		}
		double duration = frames.durationSeconds();
		double loopSeconds = duration > 0
				? ((timelineSeconds % duration) + duration) % duration
				: Math.max(0, timelineSeconds);
		RgbaFrame frame = frames.decodeForward(loopSeconds);
		return frame == null ? Background.black() : new Background.VideoFrame(frame);
	}

	/** Maps a timeline time to the source timestamp through the clip sequence. */
	private OptionalDouble mapTimelineToSourceSeconds(double timelineSeconds) {
		List<ClipRegion> clips = editor.clipRegions() == null ? List.of()
				: editor.clipRegions();
		if (clips.isEmpty()) {
			return OptionalDouble.of(Math.max(0, timelineSeconds));
		}
		double timeMs = timelineSeconds * 1000;
		ClipRegion clip = Clips.findClipAtTimelineTime(timeMs, clips);
		if (clip == null) {
			List<ClipRegion> sorted = Clips.sortClipRegions(clips);
			ClipRegion last = sorted.get(sorted.size() - 1);
			if (Math.abs(timeMs - last.endMs()) < 1e-7) {
				clip = last;
			} else {
				return OptionalDouble.empty();
			}
		}
		double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
		double sourceMs = Clips.getClipSourceStartMs(clip) + (timeMs - clip.startMs()) * speed;
		return OptionalDouble.of(Math.max(0, sourceMs / 1000.0));
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
		if (webcamSegmenter != null) {
			webcamSegmenter.close();
		}
		webcamFilter.close();
	}
}
