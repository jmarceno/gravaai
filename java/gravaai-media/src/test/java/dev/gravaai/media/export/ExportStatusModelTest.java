package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportPipelineModel;
import dev.gravaai.media.export.ExportTuning.EncodeBackend;

/** Port of {@code src/components/video-editor/exportStatusModel.test.ts}. */
class ExportStatusModelTest {

	private static ExportProgress progress() {
		return ExportProgress.of(10, 100, 10, 30);
	}

	@Test
	@DisplayName("marks a modern MP4 export as preparing before the first progress event")
	void preparingBeforeProgress() {
		ExportStatusModel status = ExportStatusModel.resolve(true, null, ExportFormat.MP4,
				ExportPipelineModel.MODERN);
		assertTrue(status.isExportPreparing());
		assertTrue(status.isLightningExportInProgress());
		assertTrue(status.shouldSuspendPreviewRendering());
		assertNull(status.renderSpeedFps());
	}

	@Test
	@DisplayName("marks legacy MP4 progress separately from the modern path")
	void legacyMp4() {
		ExportStatusModel status = ExportStatusModel.resolve(true, progress(), ExportFormat.MP4,
				ExportPipelineModel.LEGACY);
		assertTrue(status.isLegacyExportInProgress());
		assertFalse(status.isLightningExportInProgress());
		assertFalse(status.shouldSuspendPreviewRendering());
	}

	@Test
	@DisplayName("derives finalizing progress from render progress and clamps the display value")
	void finalizingProgress() {
		ExportStatusModel status = ExportStatusModel.resolve(true,
				progress().withPhase(ExportProgress.Phase.FINALIZING).withRenderProgress(104.4),
				ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals(100.0, status.exportFinalizingProgress());
		assertEquals(100, status.exportFinalizingPercent());
		assertTrue(status.isExportMuxingAndSaving());
		assertTrue(status.isExportFinalSaveIndeterminate());
	}

	@Test
	@DisplayName("sanitizes invalid finalizing progress before rounding")
	void invalidFinalizingProgress() {
		for (double invalid : new double[] {-12.4, Double.POSITIVE_INFINITY, Double.NaN}) {
			ExportStatusModel status = ExportStatusModel.resolve(true,
					progress().withPhase(ExportProgress.Phase.FINALIZING).withRenderProgress(invalid),
					ExportFormat.MP4, ExportPipelineModel.MODERN);
			assertEquals(0.0, status.exportFinalizingProgress());
			assertEquals(0, status.exportFinalizingPercent());
		}
	}

	@Test
	@DisplayName("keeps audio finalization out of the muxing-and-saving state")
	void audioFinalization() {
		ExportStatusModel status = ExportStatusModel.resolve(true,
				progress().withPhase(ExportProgress.Phase.FINALIZING)
						.withRenderProgress(80)
						.withAudioProgress(0.42),
				ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertTrue(status.isRenderingAudio());
		assertFalse(status.isExportMuxingAndSaving());
		assertFalse(status.isExportFinalSaveIndeterminate());
	}

	@Test
	@DisplayName("shows render speed only during active render progress")
	void renderSpeed() {
		ExportStatusModel rendering = ExportStatusModel.resolve(true,
				progress().withRenderFps(123.456), ExportFormat.MP4, ExportPipelineModel.MODERN);
		ExportStatusModel finalizing = ExportStatusModel.resolve(true,
				progress().withPhase(ExportProgress.Phase.FINALIZING).withRenderFps(123.456),
				ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals("123.5", rendering.renderSpeedFps());
		assertNull(finalizing.renderSpeedFps());
	}

	@Test
	@DisplayName("builds runtime labels from render and encode backends")
	void runtimeLabels() {
		ExportStatusModel status = ExportStatusModel.resolve(true,
				progress().withBackends(ExportProgress.RenderBackend.WEBGPU, EncodeBackend.FFMPEG)
						.withEncoderName("h264_nvenc"),
				ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals("WebGPU + FFmpeg (h264_nvenc)", status.runtimeLabel());
	}

	@Test
	@DisplayName("falls back to the encoder name when backend labels are unavailable")
	void runtimeLabelFallback() {
		ExportStatusModel status = ExportStatusModel.resolve(true,
				progress().withEncoderName("VideoEncoder"), ExportFormat.MP4,
				ExportPipelineModel.MODERN);
		assertEquals("VideoEncoder", status.runtimeLabel());
	}

	@Test
	@DisplayName("prefers multiple native skip reasons over the single legacy reason")
	void nativeSkipReasons() {
		ExportStatusModel status = ExportStatusModel.resolve(true,
				progress().withSkipReasons("legacy-reason",
						List.of("timeline-edits-present", "unsupported-background")),
				ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals(List.of("timeline-edits-present", "unsupported-background"),
				status.nativeSkipReasons());
		assertEquals("Native skipped: timeline-edits-present (+1 more)", status.nativeSkipLabel());
	}
}
