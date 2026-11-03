package dev.gravaai.media.compose;

import java.util.List;
import java.util.Optional;

/** Webcam overlay settings for one compositor frame (plan §5.8 stage 7), media-local. */
public record WebcamSpec(boolean enabled, String positionPreset, String corner, double positionX,
		double positionY, double size, double width, double height, boolean mirror,
		double roundness, double shadow, double margin, boolean reactToZoom, LayoutCrop cropRegion,
		List<VisibleRange> visibleRanges, double timeOffsetMs,
		Optional<Transition> transition) {

	/** A webcam visibility window in timeline milliseconds (TS {@code TimeRange}). */
	public record VisibleRange(double startMs, double endMs) {
	}

	/**
	 * A smooth Camera layout switch in progress: the camera box (and the split content region)
	 * moves from where {@code from} puts it to where this spec puts it; {@code progress} is the
	 * eased 0..1 position.
	 */
	public record Transition(WebcamSpec from, double progress) {
		public Transition {
			progress = Math.max(0, Math.min(1, progress));
		}
	}

	public WebcamSpec(boolean enabled, String positionPreset, String corner, double positionX,
			double positionY, double size, double width, double height, boolean mirror,
			double roundness, double shadow, double margin, boolean reactToZoom,
			LayoutCrop cropRegion, List<VisibleRange> visibleRanges, double timeOffsetMs) {
		this(enabled, positionPreset, corner, positionX, positionY, size, width, height, mirror,
				roundness, shadow, margin, reactToZoom, cropRegion, visibleRanges, timeOffsetMs,
				Optional.empty());
	}

	public WebcamSpec withTransition(Optional<Transition> next) {
		return new WebcamSpec(enabled, positionPreset, corner, positionX, positionY, size, width,
				height, mirror, roundness, shadow, margin, reactToZoom, cropRegion, visibleRanges,
				timeOffsetMs, next);
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
