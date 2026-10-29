package dev.gravaai.ui.editor.timeline.core;

import org.jspecify.annotations.Nullable;

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
		@Nullable Span sourceSpan,
		String label,
		@Nullable String audioPath,
		@Nullable Double audioGain,
		@Nullable Boolean audioNormalize,
		@Nullable ZoomDepth zoomDepth,
		@Nullable ZoomMode zoomMode,
		@Nullable Double speedValue,
		@Nullable Boolean showSourceAudio,
		@Nullable Boolean muted,
		@Nullable Double fadeInMs,
		@Nullable Double fadeOutMs,
		@Nullable ClipCameraLayout cameraLayout,
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
