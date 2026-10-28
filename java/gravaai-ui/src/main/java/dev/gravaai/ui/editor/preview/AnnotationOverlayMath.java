package dev.gravaai.ui.editor.preview;

import dev.gravaai.core.project.AnnotationPosition;
import dev.gravaai.core.project.AnnotationSize;
import dev.gravaai.media.compose.CameraGeometry;

/**
 * Coordinate math of the preview annotation overlay: port of the pure parts of
 * {@code AnnotationOverlay.tsx} (plan P3.4 gap 3).
 *
 * <p>Annotations store their position/size in percent of the recording rect (the compositor's
 * video mask), while the overlay lives in panel pixels on top of the rendered frame. This class
 * converts both ways, applying the camera transform (zoom scale + translation) exactly like the
 * React overlay:
 *
 * <pre>
 * sceneX   = maskX + position.x / 100 * maskWidth
 * screenX  = sceneX * scale + transformX
 * panelX   = screenX * panelWidth / outputWidth
 * </pre>
 *
 * <p>Pure logic (no JavaFX), so the behaviour is unit-tested without a GL context.
 */
public final class AnnotationOverlayMath {

	/** A rectangle in panel pixels (screen space of the preview surface). */
	public record Rect(double x, double y, double width, double height) {
	}

	private AnnotationOverlayMath() {
	}

	/**
	 * The recording rect + camera transform to use. Falls back to the full output frame when the
	 * compositor has not drawn a source frame yet (React's {@code safeRecordingRect} fallback).
	 */
	public static CameraGeometry safeGeometry(CameraGeometry geometry, double panelWidth,
			double panelHeight) {
		if (geometry == null || !geometry.hasRecordingRect()) {
			return CameraGeometry.fullFrame((int) Math.max(1, Math.round(panelWidth)),
					(int) Math.max(1, Math.round(panelHeight)));
		}
		return geometry;
	}

	/**
	 * Panel-pixel rectangle for an annotation (React {@code AnnotationOverlay} position/size
	 * computation). Drawn on top of the compositor frame, so the annotation is not re-rendered
	 * here: this is the selection/hit area.
	 */
	public static Rect overlayRect(AnnotationPosition position, AnnotationSize size,
			CameraGeometry geometry, double panelWidth, double panelHeight) {
		CameraGeometry g = safeGeometry(geometry, panelWidth, panelHeight);
		double posX = position == null ? AnnotationPosition.DEFAULT.x() : position.x();
		double posY = position == null ? AnnotationPosition.DEFAULT.y() : position.y();
		double sizeWidth = size == null ? AnnotationSize.DEFAULT.width() : size.width();
		double sizeHeight = size == null ? AnnotationSize.DEFAULT.height() : size.height();

		double sceneX = g.maskX() + (posX / 100.0) * g.maskWidth();
		double sceneY = g.maskY() + (posY / 100.0) * g.maskHeight();
		double sceneWidth = (sizeWidth / 100.0) * g.maskWidth();
		double sceneHeight = (sizeHeight / 100.0) * g.maskHeight();

		double scale = g.scale() <= 0 ? 1 : g.scale();
		double outputWidth = Math.max(1, g.outputWidth());
		double outputHeight = Math.max(1, g.outputHeight());
		double panelScaleX = panelWidth / outputWidth;
		double panelScaleY = panelHeight / outputHeight;

		return new Rect(
				(sceneX * scale + g.transformX()) * panelScaleX,
				(sceneY * scale + g.transformY()) * panelScaleY,
				sceneWidth * scale * panelScaleX,
				sceneHeight * scale * panelScaleY);
	}

	/** Annotation position (percent of the recording rect) for a panel-pixel rect. */
	public static AnnotationPosition positionFor(Rect rect, CameraGeometry geometry,
			double panelWidth, double panelHeight) {
		CameraGeometry g = safeGeometry(geometry, panelWidth, panelHeight);
		double outputWidth = Math.max(1, g.outputWidth());
		double outputHeight = Math.max(1, g.outputHeight());
		double scale = g.scale() <= 0 ? 1 : g.scale();
		double sceneX = (rect.x() * outputWidth / Math.max(1, panelWidth) - g.transformX()) / scale;
		double sceneY = (rect.y() * outputHeight / Math.max(1, panelHeight) - g.transformY()) / scale;
		return new AnnotationPosition(
				((sceneX - g.maskX()) / Math.max(1, g.maskWidth())) * 100.0,
				((sceneY - g.maskY()) / Math.max(1, g.maskHeight())) * 100.0);
	}

	/** Annotation size (percent of the recording rect) for a panel-pixel rect. */
	public static AnnotationSize sizeFor(Rect rect, CameraGeometry geometry, double panelWidth,
			double panelHeight) {
		CameraGeometry g = safeGeometry(geometry, panelWidth, panelHeight);
		double outputWidth = Math.max(1, g.outputWidth());
		double outputHeight = Math.max(1, g.outputHeight());
		double scale = g.scale() <= 0 ? 1 : g.scale();
		double sceneWidth = rect.width() * outputWidth / Math.max(1, panelWidth) / scale;
		double sceneHeight = rect.height() * outputHeight / Math.max(1, panelHeight) / scale;
		return new AnnotationSize(
				positivePercent((sceneWidth / Math.max(1, g.maskWidth())) * 100.0),
				positivePercent((sceneHeight / Math.max(1, g.maskHeight())) * 100.0));
	}

	/** React {@code positivePercent}: clamp to at least 1 and reject NaN/infinity. */
	static double positivePercent(double value) {
		if (!Double.isFinite(value)) {
			return 0;
		}
		return Math.max(1, value);
	}

	/**
	 * One drag/resize delta in panel pixels is applied to a screen rect and then committed; the
	 * overlay uses this for its live preview. Keeps a minimum size so a resize never collapses.
	 */
	public static Rect clampMinSize(Rect rect, double minimum) {
		return new Rect(rect.x(), rect.y(), Math.max(minimum, rect.width()),
				Math.max(minimum, rect.height()));
	}
}
