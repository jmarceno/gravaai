package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/**
 * Pure crop math of the webcam crop control (port of the helpers in
 * {@code WebcamCropControl.tsx}, plan P3.4 gap 4).
 *
 * <p>The control itself lives in the UI, but the geometry is project data: the crop region is a
 * fraction of the webcam source (0..1), clamped and normalised exactly like
 * {@code normalizeWebcamCropRegion}. A mirrored webcam edits the flipped rectangle and the commit
 * flips it back, so the stored crop stays in source space.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class WebcamCropMath {

	/** React {@code MIN_CROP_SIZE}. */
	public static final double MIN_CROP_SIZE = 0.08;
	/** React {@code KEYBOARD_STEP}. */
	public static final double KEYBOARD_STEP = 0.01;
	/** React {@code KEYBOARD_FAST_STEP}. */
	public static final double KEYBOARD_FAST_STEP = 0.05;

	/** The seven drag targets of the control. */
	public enum Handle {
		MOVE, NW, NE, SW, SE
	}

	@Nullable private WebcamCropMath() {
	}

	private static @Nullable double clamp(@Nullable double value, double min, @Nullable double max) {
		return Math.min(max, Math.max(min, value));
	}

	/** {@code normalizeWebcamCropRegion}: finite numbers, x/y in 0..0.99, min 0.01 size. */
	public static @Nullable CropRegion normalize(CropRegion cropRegion) {
		CropRegion candidate = cropRegion == null ? new CropRegion(0, 0, 1, 1) : cropRegion;
		double rawX = Double.isFinite(candidate.x()) ? candidate.x() : 0;
		double rawY = Double.isFinite(candidate.y()) ? candidate.y() : 0;
		double x = clamp(rawX, 0, 0.99);
		double y = clamp(rawY, 0, 0.99);
		double width = clamp(Double.isFinite(candidate.width()) ? candidate.width() : 1, 0.01,
				1 - x);
		double height = clamp(Double.isFinite(candidate.height()) ? candidate.height() : 1, 0.01,
				1 - y);
		return new CropRegion(x, y, width, height);
	}

	/** {@code isWebcamCropRegionDefault}: the whole source, untouched. */
	public static @Nullable boolean isDefault(CropRegion cropRegion) {
		CropRegion crop = normalize(cropRegion);
		return crop.x() <= 0 && crop.y() <= 0 && crop.width() >= 1 && crop.height() >= 1;
	}

	/** {@code flipCropHorizontally}: mirror a crop rect in normalised source space. */
	public static @Nullable CropRegion flipHorizontally(CropRegion cropRegion) {
		CropRegion crop = normalize(cropRegion);
		return new CropRegion(clamp(1 - crop.x() - crop.width(), 0, 1 - crop.width()), crop.y(),
				crop.width(), crop.height());
	}

	/**
	 * {@code resizeCrop}: move or resize by a normalised delta, clamped to the source and to
	 * {@link #MIN_CROP_SIZE} on the resized edges.
	 */
	public static @Nullable CropRegion resize(CropRegion cropRegion, Handle handle, double deltaX,
			double deltaY) {
		CropRegion crop = normalize(cropRegion);
		if (handle == Handle.MOVE) {
			return normalize(new CropRegion(
					clamp(crop.x() + deltaX, 0, 1 - crop.width()),
					clamp(crop.y() + deltaY, 0, 1 - crop.height()), crop.width(), crop.height()));
		}

		double left = crop.x();
		double top = crop.y();
		double right = crop.x() + crop.width();
		double bottom = crop.y() + crop.height();

		if (handle == Handle.NW || handle == Handle.SW) {
			left = clamp(left + deltaX, 0, right - MIN_CROP_SIZE);
		}
		if (handle == Handle.NE || handle == Handle.SE) {
			right = clamp(right + deltaX, left + MIN_CROP_SIZE, 1);
		}
		if (handle == Handle.NW || handle == Handle.NE) {
			top = clamp(top + deltaY, 0, bottom - MIN_CROP_SIZE);
		}
		if (handle == Handle.SW || handle == Handle.SE) {
			bottom = clamp(bottom + deltaY, top + MIN_CROP_SIZE, 1);
		}
		return normalize(new CropRegion(left, top, right - left, bottom - top));
	}

	/** Keyboard delta of an arrow key ({@code getKeyboardDelta}); zero when not an arrow. */
	public static @Nullable double[] keyboardDelta(String key, boolean shift) {
		double step = shift ? KEYBOARD_FAST_STEP : KEYBOARD_STEP;
		return switch (key) {
			case "Left" -> new double[] {-step, 0};
			case "Right" -> new double[] {step, 0};
			case "Up" -> new double[] {0, -step};
			case "Down" -> new double[] {0, step};
			default -> null;
		};
	}
}
