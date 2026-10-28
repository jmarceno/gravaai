package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.media.export.ExportTuning.BackpressureOptions;
import dev.gravaai.media.export.ExportTuning.EncodeBackend;
import dev.gravaai.media.export.ExportTuning.LatencyMode;

/** Port of {@code src/lib/exporter/exportTuning.test.ts}. */
class ExportTuningTest {

	@Test
	@DisplayName("prefers realtime latency for fast and balanced exports")
	void latencyPreferences() {
		assertEquals(List.of(LatencyMode.REALTIME, LatencyMode.QUALITY),
				ExportTuning.preferredWebCodecsLatencyModes(ExportEncodingMode.FAST));
		assertEquals(List.of(LatencyMode.REALTIME, LatencyMode.QUALITY),
				ExportTuning.preferredWebCodecsLatencyModes(ExportEncodingMode.BALANCED));
		assertEquals(List.of(LatencyMode.QUALITY, LatencyMode.REALTIME),
				ExportTuning.preferredWebCodecsLatencyModes(ExportEncodingMode.QUALITY));
	}

	@Test
	@DisplayName("keeps queue depth bounded by encoding mode")
	void queueDepth() {
		assertEquals(75, ExportTuning.webCodecsEncodeQueueLimit(60, ExportEncodingMode.FAST));
		assertEquals(120, ExportTuning.webCodecsEncodeQueueLimit(60, ExportEncodingMode.BALANCED));
		assertEquals(144, ExportTuning.webCodecsEncodeQueueLimit(60, ExportEncodingMode.QUALITY));
		assertEquals(96, ExportTuning.webCodecsEncodeQueueLimit(240, ExportEncodingMode.FAST));
		assertEquals(72, ExportTuning.webCodecsEncodeQueueLimit(12, ExportEncodingMode.BALANCED));
	}

	@Test
	@DisplayName("widens keyframe spacing for faster modes")
	void keyFrameSpacing() {
		assertEquals(240, ExportTuning.keyFrameInterval(60, ExportEncodingMode.FAST));
		assertEquals(180, ExportTuning.keyFrameInterval(60, ExportEncodingMode.BALANCED));
		assertEquals(150, ExportTuning.keyFrameInterval(60, ExportEncodingMode.QUALITY));
	}

	@Test
	@DisplayName("uses shallower decode buffers for Breeze than for WebCodecs")
	void backpressureProfiles() {
		ExportTuning.BackpressureProfile webCodecs = ExportTuning.backpressureProfile(
				new BackpressureOptions(EncodeBackend.WEBCODECS, 1280, 720, 60,
						ExportEncodingMode.BALANCED, 8));
		ExportTuning.BackpressureProfile breeze = ExportTuning.backpressureProfile(
				new BackpressureOptions(EncodeBackend.FFMPEG, 1280, 720, 60,
						ExportEncodingMode.BALANCED, 8));
		assertEquals("webcodecs-balanced-plus", webCodecs.name());
		assertEquals(12, webCodecs.maxDecodeQueue());
		assertEquals(32, webCodecs.maxPendingFrames());
		assertEquals("breeze-balanced-plus", breeze.name());
		assertEquals(14, breeze.maxDecodeQueue());
		assertEquals(40, breeze.maxPendingFrames());
		assertEquals(8, breeze.maxInFlightNativeWrites());
	}

	@Test
	@DisplayName("falls back to conservative native settings on low-core or very heavy workloads")
	void conservativeProfiles() {
		ExportTuning.BackpressureProfile lowCore = ExportTuning.backpressureProfile(
				new BackpressureOptions(EncodeBackend.FFMPEG, 1280, 720, 60,
						ExportEncodingMode.BALANCED, 4));
		ExportTuning.BackpressureProfile heavy = ExportTuning.backpressureProfile(
				new BackpressureOptions(EncodeBackend.FFMPEG, 3840, 2160, 60,
						ExportEncodingMode.BALANCED, 12));
		assertEquals("breeze-conservative", lowCore.name());
		assertEquals(8, lowCore.maxDecodeQueue());
		assertEquals(16, lowCore.maxPendingFrames());
		assertEquals(2, lowCore.maxInFlightNativeWrites());
		assertEquals("breeze-conservative", heavy.name());
		assertEquals(8, heavy.maxDecodeQueue());
		assertEquals(16, heavy.maxPendingFrames());
		assertTrue(heavy.maxInFlightNativeWrites() <= 2);
	}
}
