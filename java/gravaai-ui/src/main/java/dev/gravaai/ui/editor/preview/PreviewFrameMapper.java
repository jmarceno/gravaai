package dev.gravaai.ui.editor.preview;

import org.jspecify.annotations.Nullable;

import java.util.List;

import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.Padding;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.media.compose.Background;
import dev.gravaai.media.compose.FrameInput;
import dev.gravaai.media.compose.ProjectEditorInputs;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.media.compose.CursorClickEffect;
import dev.gravaai.media.compose.CursorSpec;
import dev.gravaai.media.compose.CursorStyle;
import dev.gravaai.media.compose.FrameStyle;
import dev.gravaai.media.compose.WebcamSpec;
import dev.gravaai.media.compose.ZoomFocus;
import dev.gravaai.media.compose.ZoomMode;
import dev.gravaai.media.compose.ZoomRegionSpec;
import dev.gravaai.media.compose.KeystrokeSpec;
import dev.gravaai.media.compose.LayoutCrop;
import dev.gravaai.media.compose.LayoutPadding;
import dev.gravaai.media.compose.LayoutSpec;
import dev.gravaai.media.compose.SceneSpec;
import dev.gravaai.media.compose.ZoomSpec;
import dev.gravaai.media.playback.PlaybackFrame;

/**
 * Maps the project editor state + a {@link PlaybackFrame} to the compositor's
 * {@link FrameInput} (plan §5.8 stage 1/2, §5.9 P2.5). Pure logic so it is unit-tested without a
 * GL context; the preview panel owns one immutable {@link PreviewScene} snapshot so the decode
 * thread never reads a mutable editor store.
 */
public final class PreviewFrameMapper {

	/** The parts of the editor state that do not depend on the frame. */
	public record PreviewScene(Background background, double backgroundBlur, LayoutSpec layout,
			FrameStyle style, ZoomSpec zoom, CursorTrack cursor, CursorSpec cursorSpec,
			WebcamSpec webcam, java.nio.file.@Nullable Path videoWallpaper,
			List<dev.gravaai.media.compose.AnnotationSpec> annotations,
			dev.gravaai.media.compose.CaptionSpec captions,
			dev.gravaai.media.compose.@Nullable KeystrokeSpec keystrokes,
			dev.gravaai.media.compose.FadeSpec fade, ProjectEditorState editor) {

		/**
		 * The scene with one annotation's box moved/resized (percent of the recording rect) while
		 * the overlay gesture is live, so the composited annotation follows the box before the
		 * edit is committed to the store. {@code id} null leaves the scene untouched.
		 */
		public PreviewScene withAnnotationBox(@Nullable String id, double x, double y, double width,
				double height) {
			if (annotations == null || id == null) {
				return this;
			}
			List<dev.gravaai.media.compose.AnnotationSpec> next = new java.util.ArrayList<>();
			for (dev.gravaai.media.compose.AnnotationSpec spec : annotations) {
				next.add(!id.equals(spec.id()) ? spec
						: new dev.gravaai.media.compose.AnnotationSpec(spec.id(), spec.startMs(),
								spec.endMs(), spec.type(), spec.content(), spec.imageData(),
								new dev.gravaai.media.compose.AnnotationSpec.Position(x, y),
								new dev.gravaai.media.compose.AnnotationSpec.Size(width, height),
								spec.style(), spec.zIndex(), spec.figure(), spec.blurIntensity(),
								spec.blurColor()));
			}
			return new PreviewScene(background, backgroundBlur, layout, style, zoom, cursor,
					cursorSpec, webcam, videoWallpaper, List.copyOf(next), captions, keystrokes, fade,
					editor);
		}
	}

	/**
	 * Cursor telemetry ready for the compositor: samples in project seconds plus the editor's
	 * show-cursor toggle. Only the samples bracketing a frame time are handed to the compositor
	 * ({@link CursorPosition} interpolates between neighbours).
	 */
	public record CursorTrack(List<CursorSample> samples, boolean visible) {

		/** No cursor samples (no recording telemetry). */
		public static final CursorTrack NONE = new CursorTrack(List.of(), false);

		public CursorTrack {
			samples = samples == null ? List.of() : List.copyOf(samples);
		}

		/** The samples around {@code timeSeconds} (previous + next) for one frame. */
		public List<CursorSample> around(double timeSeconds) {
			if (!visible || samples.isEmpty()) {
				return List.of();
			}
			double timeMs = timeSeconds * 1000;
			int lo = 0;
			int hi = samples.size() - 1;
			if (timeMs <= samples.get(0).timeSeconds() * 1000) {
				return List.of(samples.get(0));
			}
			if (timeMs >= samples.get(hi).timeSeconds() * 1000) {
				return List.of(samples.get(hi));
			}
			while (lo < hi - 1) {
				int mid = (lo + hi) >> 1;
				if (samples.get(mid).timeSeconds() * 1000 <= timeMs) {
					lo = mid;
				} else {
					hi = mid;
				}
			}
			return List.of(samples.get(lo), samples.get(hi));
		}
	}

	private PreviewFrameMapper() {
	}

	/** Background, blur and padding/crop layout for the current editor state. */
	public static PreviewScene sceneFor(ProjectEditorState state) {
		return sceneFor(state, CursorTrack.NONE);
	}

	/** Scene for the current editor state and the recording's cursor telemetry. */
	public static PreviewScene sceneFor(ProjectEditorState state, CursorTrack cursor) {
		return sceneFor(state, cursor, null);
	}

	/**
	 * Scene with the exporter's overlay specs (annotations, captions, keystrokes from the
	 * recording's {@code .keys.json}, fade, per-clip scene layout), so the preview shows what the
	 * export renders.
	 */
	public static PreviewScene sceneFor(ProjectEditorState state, CursorTrack cursor,
			java.nio.file.@Nullable Path videoPath) {
		return new PreviewScene(PreviewBackgrounds.of(state.wallpaper()),
				Math.max(0, state.backgroundBlur()), layoutFor(state), styleFor(state), zoomSpecFor(state),
				cursor == null ? CursorTrack.NONE : cursor, cursorSpecFor(state), webcamSpecFor(state),
				PreviewBackgrounds.videoPath(state.wallpaper()),
				ProjectEditorInputs.annotationSpecs(state), ProjectEditorInputs.captionSpec(state),
				videoPath == null ? null : ProjectEditorInputs.keystrokeSpec(state, videoPath),
				ProjectEditorInputs.fadeSpec(state), state);
	}

	/**
	 * {@code zoomRegions} + spring/easing settings → compositor {@link ZoomSpec} (stage 3). Depths,
	 * focus and mode map one to one with the media-local ports of the TS types.
	 */
	public static ZoomSpec zoomSpecFor(ProjectEditorState state) {
		List<ZoomRegionSpec> regions = state.zoomRegions() == null ? List.of()
				: state.zoomRegions().stream()
						.map(region -> new ZoomRegionSpec(region.id(), (int) region.startMs(),
								(int) region.endMs(),
								dev.gravaai.media.compose.ZoomDepth.of(region.depth().wire()),
								new ZoomFocus(region.focus().cx(), region.focus().cy()),
								region.mode() == null ? null : ZoomMode.valueOf(region.mode().name())))
						.toList();
		return new ZoomSpec(regions, Math.max(0, Math.min(1, state.zoomSmoothness())),
				state.connectZooms(), state.zoomClassicMode(), state.zoomInDurationMs(),
				state.zoomOutDurationMs(), state.cameraSpringStiffnessMultiplier(),
				state.cameraSpringDampingMultiplier(), state.cameraSpringMassMultiplier(),
				Math.max(0, state.zoomMotionBlur()));
	}

	/** {@code DEFAULT_CURSOR_CONFIG.dotRadius}. */
	private static final double DEFAULT_CURSOR_DOT_RADIUS = 28;

	/**
	 * Cursor settings → compositor {@link CursorSpec} (stage 6). Port of the {@code PixiCursorOverlay}
	 * construction in {@code VideoPlayback.tsx}: the dot radius is
	 * {@code DEFAULT_CURSOR_CONFIG.dotRadius * cursorSize}, the click-effect colour is the editor's
	 * CSS colour parsed to ARGB (alpha ignored by the compositor).
	 */
	public static CursorSpec cursorSpecFor(ProjectEditorState state) {
		return new CursorSpec(state.showCursor(), CursorStyle.fromWire(state.cursorStyle()),
				DEFAULT_CURSOR_DOT_RADIUS * Math.max(0.01, state.cursorSize()),
				clamp(state.cursorSmoothing(), 0, 1), state.cursorSpringStiffnessMultiplier(),
				state.cursorSpringDampingMultiplier(), state.cursorSpringMassMultiplier(),
				Math.max(0, state.cursorMotionBlur()),
				CursorClickEffect.fromWire(state.cursorClickEffect().wire()),
				parseArgb(state.cursorClickEffectColor(), 0xFF2563EB),
				state.cursorClickEffectScale(), clamp(state.cursorClickEffectOpacity(), 0, 1),
				Math.max(1, state.cursorClickEffectDurationMs()), state.cursorClickBounce(),
				Math.max(1, state.cursorClickBounceDuration()), state.cursorSway());
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	/** CSS colour ({@code #rgb}, {@code #rrggbb}, {@code #rrggbbaa}) to ARGB; fallback otherwise. */
	static int parseArgb(String cssColour, int fallback) {
		if (cssColour == null) {
			return fallback;
		}
		String value = cssColour.trim();
		if (value.startsWith("#")) {
			value = value.substring(1);
		}
		try {
			if (value.length() == 3) {
				int r = Integer.parseInt(value.substring(0, 1), 16);
				int g = Integer.parseInt(value.substring(1, 2), 16);
				int b = Integer.parseInt(value.substring(2, 3), 16);
				return 0xFF000000 | (r * 17 << 16) | (g * 17 << 8) | (b * 17);
			}
			if (value.length() == 6) {
				return 0xFF000000 | Integer.parseInt(value, 16);
			}
			if (value.length() == 8) {
				long rgba = Long.parseLong(value, 16);
				return (int) ((rgba & 0xFF) << 24 | (rgba >>> 8) & 0xFFFFFF);
			}
		} catch (NumberFormatException ignored) {
			// fall through
		}
		return fallback;
	}

	/**
	 * {@code WebcamOverlaySettings} → compositor {@link WebcamSpec} (stage 7): position preset and
	 * legacy corner, normalized position/size, mirror, roundness/shadow/margin, react-to-zoom, the
	 * webcam crop and the visible timeline ranges (all wire values kept exactly as the editor
	 * stores them). A missing webcam block becomes {@link WebcamSpec#DISABLED}.
	 */
	public static WebcamSpec webcamSpecFor(ProjectEditorState state) {
		dev.gravaai.core.project.WebcamOverlaySettings webcam = state.webcam();
		if (webcam == null) {
			return WebcamSpec.DISABLED;
		}
		List<WebcamSpec.VisibleRange> ranges = webcam.visibleRanges() == null ? List.of()
				: webcam.visibleRanges().stream()
						.map(range -> new WebcamSpec.VisibleRange(range.startMs(), range.endMs()))
						.toList();
		dev.gravaai.core.project.CropRegion crop = webcam.cropRegion();
		LayoutCrop cropSpec = crop == null ? LayoutCrop.FULL
				: new LayoutCrop(crop.x(), crop.y(), crop.width(), crop.height());
		return new WebcamSpec(webcam.enabled(),
				webcam.positionPreset() == null ? "bottom-right" : webcam.positionPreset().wire(),
				webcam.corner() == null ? "bottom-right" : webcam.corner().wire(), webcam.positionX(),
				webcam.positionY(), webcam.size(), webcam.width(), webcam.height(), webcam.mirror(),
				webcam.roundness(), webcam.shadow(), webcam.margin(), webcam.reactToZoom(), cropSpec,
				ranges, webcam.timeOffsetMs());
	}

	/**
	 * {@code borderRadius}/{@code shadowIntensity} → compositor video-layer style (stage 4). The
	 * radius keeps the editor's 0-50 percentage scale; the compositor scales it for the target.
	 */
	public static FrameStyle styleFor(ProjectEditorState state) {
		double radius = Math.max(0, Math.min(50, state.borderRadius()));
		double shadow = Math.max(0, Math.min(1, state.shadowIntensity()));
		return new FrameStyle(radius, shadow > 0, shadow);
	}

	/** {@code Padding}/{@code CropRegion} → compositor layout ({@link LayoutSpec}). */
	public static LayoutSpec layoutFor(ProjectEditorState state) {
		return new LayoutSpec(paddingFor(state.padding()), cropFor(state.cropRegion()));
	}

	static LayoutPadding paddingFor(@Nullable Padding padding) {
		if (padding == null) {
			return LayoutPadding.NONE;
		}
		return new LayoutPadding(padding.top(), padding.bottom(), padding.left(), padding.right(),
				Boolean.TRUE.equals(padding.linked()));
	}

	static LayoutCrop cropFor(@Nullable CropRegion crop) {
		if (crop == null) {
			return LayoutCrop.FULL;
		}
		return new LayoutCrop(crop.x(), crop.y(), crop.width(), crop.height());
	}

	/** One compositor input from one decoded playback frame. */
	public static FrameInput frameInput(PlaybackFrame frame, PreviewScene scene) {
		return frameInput(frame, scene, scene.background());
	}

	/** One compositor input with a per-frame background (a decoded video wallpaper frame). */
	public static FrameInput frameInput(PlaybackFrame frame, PreviewScene scene,
			Background background) {
		// Captions, keystrokes, cursor and webcam follow the source time (VideoPlayback maps the
		// timeline time through the clips, nearest clip boundary in a gap).
		double sourceSeconds = frame.sourceSeconds() != null ? frame.sourceSeconds()
				: scene.editor() == null ? frame.timelineSeconds()
				: dev.gravaai.core.project.Clips.mapTimelineTimeToSourceTime(
						frame.timelineSeconds() * 1000, scene.editor().clipRegions()) / 1000.0;
		KeystrokeSpec keystrokes = scene.keystrokes() == null
				? KeystrokeSpec.DISABLED : scene.keystrokes();
		SceneSpec sceneSpec = scene.editor() == null ? SceneSpec.FULL
				: ProjectEditorInputs.sceneSpec(scene.editor(),
						frame.timelineSeconds() * 1000);
		return new FrameInput(frame.frameIndex(), frame.timelineSeconds(), frame.sourceFrame(),
				frame.webcamFrame(), scene.cursor().around(sourceSeconds), background,
				scene.backgroundBlur(), scene.layout(), scene.zoom(), scene.style(),
				scene.cursorSpec(), scene.webcam(), scene.annotations(), scene.captions(),
				keystrokes, scene.fade(), sceneSpec, sourceSeconds);
	}

	/** A background-only frame (idle preview, gap over the timeline start). */
	public static FrameInput idleInput(long frameIndex, double timeSeconds, PreviewScene scene) {
		return new FrameInput(frameIndex, timeSeconds, null, null, scene.cursor().around(timeSeconds),
				scene.background(), scene.backgroundBlur(), LayoutSpec.FULL_FRAME, ZoomSpec.NONE,
				scene.style(), scene.cursorSpec(), scene.webcam());
	}
}