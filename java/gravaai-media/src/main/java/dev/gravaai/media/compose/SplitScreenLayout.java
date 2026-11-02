package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import io.github.humbleui.types.Rect;

/**
 * Split screen geometry for the {@code split-*} webcam position presets: the output frame divides
 * into one region for the webcam and the remaining region for the screen content, side by side
 * or one above the other. The camera draws as a rounded card inset in its region
 * ({@link #cameraCard}, {@link #cardRadius}). Any other preset is the floating bubble overlay
 * and resolves to {@code null}.
 *
 * <p>The camera region takes the authored webcam {@code width} percent of the frame for the
 * side-by-side presets and the {@code height} percent for the stacked ones, so the existing
 * webcam size sliders drive the split. The share is clamped so both regions stay visible.</p>
 */
public final class SplitScreenLayout {

	/** The camera region never takes less/more than this share of the split axis. */
	public static final double MIN_SHARE = 0.1;
	public static final double MAX_SHARE = 0.9;

	/** The screen-content region and the camera region, in output pixels. */
	public record Regions(Rect content, Rect camera) {
	}

	private SplitScreenLayout() {
	}

	/** True when the wire preset is one of the split screen presets. */
	public static boolean isSplit(@Nullable String positionPreset) {
		return "split-right".equals(positionPreset) || "split-left".equals(positionPreset)
				|| "split-top".equals(positionPreset) || "split-bottom".equals(positionPreset);
	}

	/**
	 * Resolves the two regions for a split preset, or {@code null} for any other preset.
	 *
	 * @param positionPreset the webcam preset wire
	 * @param width the output width in pixels
	 * @param height the output height in pixels
	 * @param sharePercent the authored camera share of the split axis (the webcam width percent
	 *        for side-by-side, height percent for stacked), 10..100 like the editor sliders
	 */
	public static @Nullable Regions resolve(@Nullable String positionPreset, int width,
			int height, double sharePercent) {
		if (width <= 0 || height <= 0) {
			return null;
		}
		return switch (positionPreset == null ? "" : positionPreset) {
			case "split-right" -> regions(width, height, share(width, sharePercent), 0, false,
					true);
			case "split-left" -> regions(width, height, share(width, sharePercent), 0, false,
					false);
			case "split-top" -> regions(width, height, 0, share(height, sharePercent), true,
					false);
			case "split-bottom" -> regions(width, height, 0, share(height, sharePercent), true,
					true);
			default -> null;
		};
	}

	private static double share(int axis, double sharePercent) {
		double percent = Double.isFinite(sharePercent) ? sharePercent : 40;
		double fraction = Math.min(MAX_SHARE, Math.max(MIN_SHARE, percent / 100.0));
		return axis * fraction;
	}

	/**
	 * The camera card inside its region: inset by {@code gapPx} on every side so it floats like
	 * the screen card next to it (never collapsing below a quarter of the region).
	 */
	public static Rect cameraCard(Rect camera, double gapPx) {
		double maxInset = Math.min(camera.getWidth(), camera.getHeight()) / 4;
		float inset = (float) Math.min(maxInset, Math.max(0, gapPx));
		return Rect.makeLTRB(camera.getLeft() + inset, camera.getTop() + inset,
				camera.getRight() - inset, camera.getBottom() - inset);
	}

	/**
	 * The camera card corner radius: the webcam roundness percent drives a rounded rectangle
	 * (up to {@link #MAX_CARD_RADIUS_FRACTION} of the card's short side) instead of the overlay's
	 * pill/circle, so a large strip stays a card.
	 */
	public static double cardRadius(double roundnessPercent, Rect card) {
		double roundness = Double.isFinite(roundnessPercent)
				? Math.min(100, Math.max(0, roundnessPercent))
				: 0;
		double shortSide = Math.max(0, Math.min(card.getWidth(), card.getHeight()));
		return shortSide * MAX_CARD_RADIUS_FRACTION * Math.sqrt(roundness / 100);
	}

	/** The camera card radius at 100% roundness, as a fraction of its short side. */
	public static final double MAX_CARD_RADIUS_FRACTION = 0.1;

	/** Builds the regions from the camera strip size on the split axis (Skija rects are LTRB). */
	private static Regions regions(int width, int height, double cameraWidth, double cameraHeight,
			boolean stacked, boolean after) {
		Rect camera;
		Rect content;
		if (stacked) {
			camera = after
					? new Rect(0, (float) (height - cameraHeight), width, height)
					: new Rect(0, 0, width, (float) cameraHeight);
			content = after
					? new Rect(0, 0, width, (float) (height - cameraHeight))
					: new Rect(0, (float) cameraHeight, width, height);
		} else {
			camera = after
					? new Rect((float) (width - cameraWidth), 0, width, height)
					: new Rect(0, 0, (float) cameraWidth, height);
			content = after
					? new Rect(0, 0, (float) (width - cameraWidth), height)
					: new Rect((float) cameraWidth, 0, width, height);
		}
		return new Regions(content, camera);
	}
}
