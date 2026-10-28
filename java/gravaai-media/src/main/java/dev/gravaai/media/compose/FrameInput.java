package dev.gravaai.media.compose;

import java.util.List;

/**
 * Everything the compositor needs for one output frame (plan §5.8: "FrameInput record: the
 * source video frame, the webcam frame, the cursor sample(s), time, and the resolved
 * ProjectEditorState").
 *
 * <p>The editor state is not part of this record: it lives in the project model
 * ({@code gravaai-core}, P2.2) and is resolved by the caller into the fields below. The media
 * layer stays free of the project model so the compositor can be tested with plain values and
 * so preview and export feed it the same way.</p>
 *
 * @param frameIndex the output frame number, monotonically increasing
 * @param timeSeconds the output time in seconds
 * @param sourceFrame the decoded source video frame, or {@code null} for a background-only frame
 * @param webcamFrame the decoded webcam frame, or {@code null} (webcam overlay stage 7)
 * @param cursorSamples cursor samples covering this frame, in time order (stages 3 and 6)
 * @param background the background to paint (stage 1); callers keep one instance per project so
 *        the compositor can cache the pre-rendered background
 * @param backgroundBlur the background blur setting (scaled through {@link SceneEffects})
 * @param layout the padding/crop layout (stage 2), or {@code null} for the full frame
 * @param zoom the zoom regions/settings (stage 3), or {@code null} for no zoom
 * @param style the squircle radius and drop-shadow style (stage 4), or {@code null} for none
 * @param cursor the cursor overlay settings (stage 6), or {@code null} for hidden defaults
 * @param webcam the webcam overlay settings (stage 7), or {@code null} for disabled defaults
 * @param annotations annotations for this frame (stage 8), in z-order; empty when none
 * @param captions the resolved caption settings/cues (stage 9); disabled by default
 * @param keystrokes the key-press overlay settings/presses (stage 10); disabled by default
 * @param fade fade-through-black clip transition windows (stage 11); disabled by default
 * @param scene per-clip camera layout state (camera opacity/fill, screen opacity)
 * @param sourceTimeSeconds the source recording time of this frame (the clip mapping of
 *        {@code timeSeconds}); captions, keystrokes, the cursor and the webcam follow it, like
 *        the TS exporter's {@code timestamp}/{@code currentVideoTime}, while zoom, annotations,
 *        fade and the wallpaper follow the timeline time
 */
public record FrameInput(
		long frameIndex,
		double timeSeconds,
		RgbaFrame sourceFrame,
		RgbaFrame webcamFrame,
		List<CursorSample> cursorSamples,
		Background background,
		double backgroundBlur,
		LayoutSpec layout,
		ZoomSpec zoom,
		FrameStyle style,
		CursorSpec cursor,
		WebcamSpec webcam,
		List<AnnotationSpec> annotations,
		CaptionSpec captions,
		KeystrokeSpec keystrokes,
		FadeSpec fade,
		SceneSpec scene,
		double sourceTimeSeconds) {

	public FrameInput {
		if (frameIndex < 0) {
			throw new IllegalArgumentException("frameIndex must not be negative: " + frameIndex);
		}
		if (!Double.isFinite(timeSeconds)) {
			throw new IllegalArgumentException("timeSeconds must be finite: " + timeSeconds);
		}
		if (background == null) {
			throw new IllegalArgumentException("background must not be null");
		}
		if (!Double.isFinite(backgroundBlur) || backgroundBlur < 0) {
			throw new IllegalArgumentException(
					"backgroundBlur must be a finite non-negative number: " + backgroundBlur);
		}
		cursorSamples = cursorSamples == null ? List.of() : List.copyOf(cursorSamples);
		layout = layout == null ? LayoutSpec.FULL_FRAME : layout;
		zoom = zoom == null ? ZoomSpec.NONE : zoom;
		style = style == null ? FrameStyle.DEFAULT : style;
		cursor = cursor == null ? CursorSpec.DEFAULT : cursor;
		webcam = webcam == null ? WebcamSpec.DISABLED : webcam;
		annotations = annotations == null ? List.of() : List.copyOf(annotations);
		captions = captions == null ? CaptionSpec.DISABLED : captions;
		keystrokes = keystrokes == null ? KeystrokeSpec.DISABLED : keystrokes;
		fade = fade == null ? FadeSpec.NONE : fade;
		scene = scene == null ? SceneSpec.FULL : scene;
		if (!Double.isFinite(sourceTimeSeconds)) {
			sourceTimeSeconds = timeSeconds;
		}
	}

	/** Constructor for callers without a clip mapping: the source time equals the timeline time. */
	public FrameInput(
			long frameIndex,
			double timeSeconds,
			RgbaFrame sourceFrame,
			RgbaFrame webcamFrame,
			List<CursorSample> cursorSamples,
			Background background,
			double backgroundBlur,
			LayoutSpec layout,
			ZoomSpec zoom,
			FrameStyle style,
			CursorSpec cursor,
			WebcamSpec webcam,
			List<AnnotationSpec> annotations,
			CaptionSpec captions,
			KeystrokeSpec keystrokes,
			FadeSpec fade,
			SceneSpec scene) {
		this(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples, background,
				backgroundBlur, layout, zoom, style, cursor, webcam, annotations, captions,
				keystrokes, fade, scene, timeSeconds);
	}

	/** Back-compat constructor for frames without annotations (stage 8 defaults to none). */
	public FrameInput(
			long frameIndex,
			double timeSeconds,
			RgbaFrame sourceFrame,
			RgbaFrame webcamFrame,
			List<CursorSample> cursorSamples,
			Background background,
			double backgroundBlur,
			LayoutSpec layout,
			ZoomSpec zoom,
			FrameStyle style,
			CursorSpec cursor,
			WebcamSpec webcam) {
		this(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples, background,
				backgroundBlur, layout, zoom, style, cursor, webcam, List.of(),
				CaptionSpec.DISABLED, KeystrokeSpec.DISABLED, FadeSpec.NONE, SceneSpec.FULL);
	}

	/** Back-compat constructor for frames with annotations but no captions (stage 9). */
	public FrameInput(
			long frameIndex,
			double timeSeconds,
			RgbaFrame sourceFrame,
			RgbaFrame webcamFrame,
			List<CursorSample> cursorSamples,
			Background background,
			double backgroundBlur,
			LayoutSpec layout,
			ZoomSpec zoom,
			FrameStyle style,
			CursorSpec cursor,
			WebcamSpec webcam,
			List<AnnotationSpec> annotations) {
		this(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples, background,
				backgroundBlur, layout, zoom, style, cursor, webcam, annotations,
				CaptionSpec.DISABLED, KeystrokeSpec.DISABLED, FadeSpec.NONE, SceneSpec.FULL);
	}

	/** Back-compat constructor for frames with annotations and captions but no keystrokes. */
	public FrameInput(
			long frameIndex,
			double timeSeconds,
			RgbaFrame sourceFrame,
			RgbaFrame webcamFrame,
			List<CursorSample> cursorSamples,
			Background background,
			double backgroundBlur,
			LayoutSpec layout,
			ZoomSpec zoom,
			FrameStyle style,
			CursorSpec cursor,
			WebcamSpec webcam,
			List<AnnotationSpec> annotations,
			CaptionSpec captions) {
		this(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples, background,
				backgroundBlur, layout, zoom, style, cursor, webcam, annotations, captions,
				KeystrokeSpec.DISABLED, FadeSpec.NONE, SceneSpec.FULL);
	}

	/** Back-compat constructor for frames with overlays but no fade windows (stage 11). */
	public FrameInput(
			long frameIndex,
			double timeSeconds,
			RgbaFrame sourceFrame,
			RgbaFrame webcamFrame,
			List<CursorSample> cursorSamples,
			Background background,
			double backgroundBlur,
			LayoutSpec layout,
			ZoomSpec zoom,
			FrameStyle style,
			CursorSpec cursor,
			WebcamSpec webcam,
			List<AnnotationSpec> annotations,
			CaptionSpec captions,
			KeystrokeSpec keystrokes) {
		this(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples, background,
				backgroundBlur, layout, zoom, style, cursor, webcam, annotations, captions,
				keystrokes, FadeSpec.NONE, SceneSpec.FULL);
	}

	/** Back-compat constructor for frames without scene layout state. */
	public FrameInput(
			long frameIndex,
			double timeSeconds,
			RgbaFrame sourceFrame,
			RgbaFrame webcamFrame,
			List<CursorSample> cursorSamples,
			Background background,
			double backgroundBlur,
			LayoutSpec layout,
			ZoomSpec zoom,
			FrameStyle style,
			CursorSpec cursor,
			WebcamSpec webcam,
			List<AnnotationSpec> annotations,
			CaptionSpec captions,
			KeystrokeSpec keystrokes,
			FadeSpec fade) {
		this(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples, background,
				backgroundBlur, layout, zoom, style, cursor, webcam, annotations, captions,
				keystrokes, fade, SceneSpec.FULL);
	}

	/** Copy with the stage-8 annotation list. */
	public FrameInput withAnnotations(List<AnnotationSpec> annotations) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples,
				background, backgroundBlur, layout, zoom, style, cursor, webcam, annotations,
				captions, keystrokes, fade, scene, sourceTimeSeconds);
	}

	/** Copy with the stage-9 captions. */
	public FrameInput withCaptions(CaptionSpec captions) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples,
				background, backgroundBlur, layout, zoom, style, cursor, webcam, annotations,
				captions, keystrokes, fade, scene, sourceTimeSeconds);
	}

	/** Copy with the stage-10 keystroke overlay. */
	public FrameInput withKeystrokes(KeystrokeSpec keystrokes) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples,
				background, backgroundBlur, layout, zoom, style, cursor, webcam, annotations,
				captions, keystrokes, fade, scene, sourceTimeSeconds);
	}

	/** Copy with the stage-11 fade windows. */
	public FrameInput withFade(FadeSpec fade) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples,
				background, backgroundBlur, layout, zoom, style, cursor, webcam, annotations,
				captions, keystrokes, fade, scene, sourceTimeSeconds);
	}

	/** Copy with the per-clip camera layout state. */
	public FrameInput withScene(SceneSpec scene) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples,
				background, backgroundBlur, layout, zoom, style, cursor, webcam, annotations,
				captions, keystrokes, fade, scene, sourceTimeSeconds);
	}

	/** Copy with the clip-mapped source time. */
	public FrameInput withSourceTime(double sourceTimeSeconds) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, webcamFrame, cursorSamples,
				background, backgroundBlur, layout, zoom, style, cursor, webcam, annotations,
				captions, keystrokes, fade, scene, sourceTimeSeconds);
	}

	/** A background-only frame. */
	public static FrameInput background(long frameIndex, double timeSeconds,
			Background background) {
		return new FrameInput(frameIndex, timeSeconds, null, null, List.of(), background, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT, WebcamSpec.DISABLED);
	}

	/** A background-only frame with background blur. */
	public static FrameInput background(long frameIndex, double timeSeconds,
			Background background, double backgroundBlur) {
		return new FrameInput(frameIndex, timeSeconds, null, null, List.of(), background,
				backgroundBlur, LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT, WebcamSpec.DISABLED);
	}

	/** A frame with source video, the full-frame layout, no zoom and the default style. */
	public static FrameInput ofSource(long frameIndex, double timeSeconds, RgbaFrame sourceFrame,
			Background background) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, null, List.of(), background, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT, WebcamSpec.DISABLED);
	}

	/** A frame with source video and an explicit padding/crop layout. */
	public static FrameInput ofSource(long frameIndex, double timeSeconds, RgbaFrame sourceFrame,
			Background background, double backgroundBlur, LayoutSpec layout) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, null, List.of(), background,
				backgroundBlur, layout, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT, WebcamSpec.DISABLED);
	}

	/** A frame with source video, layout and zoom regions. */
	public static FrameInput ofSource(long frameIndex, double timeSeconds, RgbaFrame sourceFrame,
			Background background, double backgroundBlur, LayoutSpec layout, ZoomSpec zoom) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, null, List.of(), background,
				backgroundBlur, layout, zoom, FrameStyle.DEFAULT, CursorSpec.DEFAULT, WebcamSpec.DISABLED);
	}

	/** A frame with source video, layout, zoom and the stage-4 visual style. */
	public static FrameInput ofSource(long frameIndex, double timeSeconds, RgbaFrame sourceFrame,
			Background background, double backgroundBlur, LayoutSpec layout, ZoomSpec zoom,
			FrameStyle style) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, null, List.of(), background,
				backgroundBlur, layout, zoom, style, CursorSpec.DEFAULT, WebcamSpec.DISABLED);
	}

	/** A frame with source video, layout, zoom, style and cursor overlay. */
	public static FrameInput ofSource(long frameIndex, double timeSeconds, RgbaFrame sourceFrame,
			Background background, double backgroundBlur, LayoutSpec layout, ZoomSpec zoom,
			FrameStyle style, CursorSpec cursor) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, null, List.of(), background,
				backgroundBlur, layout, zoom, style, cursor, WebcamSpec.DISABLED);
	}

	/** A frame with source video, cursor telemetry, layout, zoom and style. */
	public static FrameInput ofSource(long frameIndex, double timeSeconds, RgbaFrame sourceFrame,
			List<CursorSample> cursorSamples, Background background, double backgroundBlur,
			LayoutSpec layout, ZoomSpec zoom, FrameStyle style) {
		return new FrameInput(frameIndex, timeSeconds, sourceFrame, null, cursorSamples,
				background, backgroundBlur, layout, zoom, style, CursorSpec.DEFAULT, WebcamSpec.DISABLED);
	}
}
