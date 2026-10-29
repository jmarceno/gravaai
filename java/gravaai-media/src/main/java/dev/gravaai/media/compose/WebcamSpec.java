package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** Webcam overlay settings for one compositor frame (plan §5.8 stage 7), media-local. */
public record WebcamSpec(boolean enabled, String positionPreset, String corner, @Nullable double positionX,
		@Nullable double positionY, double size, @Nullable double width, @Nullable double height, boolean mirror,
		@Nullable double roundness, @Nullable double shadow, @Nullable double margin, boolean reactToZoom, LayoutCrop cropRegion,
		List<VisibleRange> visibleRanges, @Nullable double timeOffsetMs) {

	/** A webcam visibility window in timeline milliseconds (TS {@code TimeRange}). */
	public record VisibleRange(double startMs, double endMs) {
	}

	/** No webcam overlay. */
	public static final WebcamSpec DISABLED = new WebcamSpec(false, "bottom-right",
			"bottom-right", 1, 1, 40, 40, 40, true, 100, 0.3, 24, true, LayoutCrop.FULL, List.of(),
			0);

	public WebcamSpec {
		visibleRanges = visibleRanges == null ? List.of() : List.copyOf(visibleRanges);
		cropRegion = cropRegion == null ? LayoutCrop.FULL : cropRegion;
		if (!Double.isFinite(positionX) || !Double.isFinite(positionY)
				|| !Double.isFinite(size) || !Double.isFinite(width)
				|| !Double.isFinite(height) || !Double.isFinite(timeOffsetMs)) {
			throw new IllegalArgumentException("webcam spec values must be finite");
		}
	}

	/** {@code true} when the webcam should be drawn at this timeline time. */
	public boolean isVisibleAt(double timeMs) {
		if (!enabled) {
			return false;
		}
		if (visibleRanges.isEmpty()) {
			return true;
		}
		for (VisibleRange range : visibleRanges) {
			if (timeMs >= range.startMs() && timeMs < range.endMs()) {
				return true;
			}
		}
		return false;
	}
}
