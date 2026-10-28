package dev.gravaai.ui.editor.timeline.core;

import dev.gravaai.core.project.ClipCameraLayout;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomMode;

/**
 * One drawable timeline block (port of {@code TimelineRenderItem} in
 * {@code timeline/core/timelineTypes.ts}).
 *
 * <p>Optional fields are nullable ({@code fadeInMs}/{@code fadeOutMs} only for clips with a
 * fade-through-black transition, {@code cameraLayout} only when it is not the default, ...).
 */
public record TimelineRenderItem(
		String id,
		String rowId,
		Span span,
		Span sourceSpan,
		String label,
		String audioPath,
		Double audioGain,
		Boolean audioNormalize,
		ZoomDepth zoomDepth,
		ZoomMode zoomMode,
		Double speedValue,
		Boolean showSourceAudio,
		Boolean muted,
		Double fadeInMs,
		Double fadeOutMs,
		ClipCameraLayout cameraLayout,
		Variant variant) {

	/** Port of the {@code variant} union. */
	public enum Variant {
		ZOOM,
		TRIM,
		CLIP,
		ANNOTATION,
		SPEED,
		AUDIO,
		CAPTION
	}
}
