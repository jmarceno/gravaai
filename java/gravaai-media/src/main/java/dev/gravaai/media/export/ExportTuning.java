package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.util.List;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;

/**
 * Export encoder tuning (port of {@code src/lib/exporter/exportTuning.ts}, plan §5.10).
 *
 * <p>The WebCodecs queue limits are not used by the ffmpeg-CLI export path, but the numbers are
 * ported with their tests so the editor can share one policy table; {@link #keyFrameInterval} feeds
 * the libx264 {@code -g} argument.
 */
public final class ExportTuning {

	public static final ExportEncodingMode DEFAULT_ENCODING_MODE = ExportEncodingMode.BALANCED;
	private static final int BASELINE_PIXELS_PER_SECOND = 1280 * 720 * 60;

	/** {@code WebCodecsLatencyMode}. */
	public enum LatencyMode {
		QUALITY("quality"),
		REALTIME("realtime");

		private final String wire;

		LatencyMode(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	/** {@code ExportEncodeBackend}. */
	public enum EncodeBackend {
		FFMPEG("ffmpeg"),
		WEBCODECS("webcodecs");

		private final String wire;

		EncodeBackend(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	/** {@code ExportBackpressureProfile}. */
	public record BackpressureProfile(String name, int maxEncodeQueue, int maxDecodeQueue,
			int maxPendingFrames, int maxInFlightNativeWrites) {}

	/** {@code ExportBackpressureProfileOptions}. */
	public record BackpressureOptions(EncodeBackend encodeBackend, int width, int height,
			int frameRate, ExportEncodingMode encodingMode, @Nullable Integer hardwareConcurrency) {}

	private static final int[] TARGET_QUEUE_SECONDS_X100 = {125, 200, 240};
	private static final int[] MIN_QUEUE_LIMIT = {36, 72, 96};
	private static final int[] MAX_QUEUE_LIMIT = {96, 120, 180};
	private static final double[] KEYFRAME_INTERVAL_SECONDS = {4.0, 3.0, 2.5};

	private ExportTuning() {}

	/** {@code getPreferredWebCodecsLatencyModes}. */
	public static List<LatencyMode> preferredWebCodecsLatencyModes(ExportEncodingMode encodingMode) {
		ExportEncodingMode mode = encodingMode == null ? DEFAULT_ENCODING_MODE : encodingMode;
		return mode == ExportEncodingMode.QUALITY
				? List.of(LatencyMode.QUALITY, LatencyMode.REALTIME)
				: List.of(LatencyMode.REALTIME, LatencyMode.QUALITY);
	}

	/** {@code getWebCodecsEncodeQueueLimit}. */
	public static int webCodecsEncodeQueueLimit(int frameRate, ExportEncodingMode encodingMode) {
		ExportEncodingMode mode = encodingMode == null ? DEFAULT_ENCODING_MODE : encodingMode;
		int index = mode.ordinal();
		int targetLimit = (int) Math.round(frameRate * (TARGET_QUEUE_SECONDS_X100[index] / 100.0));
		return Math.min(Math.max(targetLimit, MIN_QUEUE_LIMIT[index]), MAX_QUEUE_LIMIT[index]);
	}

	/** {@code getWebCodecsKeyFrameInterval}. */
	public static int keyFrameInterval(int frameRate, ExportEncodingMode encodingMode) {
		ExportEncodingMode mode = encodingMode == null ? DEFAULT_ENCODING_MODE : encodingMode;
		return Math.max(1, (int) Math.round(frameRate * KEYFRAME_INTERVAL_SECONDS[mode.ordinal()]));
	}

	/** {@code getExportBackpressureProfile}. */
	public static BackpressureProfile backpressureProfile(BackpressureOptions options) {
		int hardwareConcurrency = effectiveHardwareConcurrency(options.hardwareConcurrency());
		double relativePixelRate = relativePixelRate(options.width(), options.height(),
				options.frameRate());
		boolean isLowCoreSystem = hardwareConcurrency <= 4;
		boolean isHighCoreSystem = hardwareConcurrency >= 8;
		boolean isHeavyWorkload = relativePixelRate >= 1.5;
		boolean isExtremeWorkload = relativePixelRate >= 3;
		int maxEncodeQueue = webCodecsEncodeQueueLimit(options.frameRate(), options.encodingMode());

		if (options.encodeBackend() == EncodeBackend.FFMPEG) {
			if (isLowCoreSystem || isExtremeWorkload) {
				return new BackpressureProfile("breeze-conservative", maxEncodeQueue, 8, 16, 2);
			}
			if (isHighCoreSystem && !isHeavyWorkload) {
				return new BackpressureProfile("breeze-balanced-plus", maxEncodeQueue, 14, 40, 8);
			}
			return new BackpressureProfile("breeze-balanced", maxEncodeQueue, 12, 28, 4);
		}

		if (isLowCoreSystem || isExtremeWorkload) {
			return new BackpressureProfile("webcodecs-conservative", maxEncodeQueue, 8, 20, 1);
		}
		if (isHighCoreSystem && !isHeavyWorkload) {
			return new BackpressureProfile("webcodecs-balanced-plus", maxEncodeQueue, 12, 32, 1);
		}
		return new BackpressureProfile("webcodecs-balanced", maxEncodeQueue, 10, 24, 1);
	}

	private static int effectiveHardwareConcurrency(@Nullable Integer hardwareConcurrency) {
		if (hardwareConcurrency != null && hardwareConcurrency > 0) {
			return hardwareConcurrency;
		}
		return Math.max(1, Runtime.getRuntime().availableProcessors());
	}

	private static double relativePixelRate(int width, int height, int frameRate) {
		return ((double) Math.max(1, width) * Math.max(1, height) * Math.max(1, frameRate))
				/ BASELINE_PIXELS_PER_SECOND;
	}
}
