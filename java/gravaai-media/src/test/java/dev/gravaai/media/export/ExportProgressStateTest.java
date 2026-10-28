package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.export.ExportTuning.EncodeBackend;

/** Port of {@code src/components/video-editor/exportProgressState.test.ts}. */
class ExportProgressStateTest {

	@Test
	@DisplayName("marks progress as saving while preserving runtime diagnostics")
	void savingKeepsDiagnostics() {
		ExportProgress previous = ExportProgress.of(24, 120, 20, 12)
				.withRenderFps(144.25)
				.withBackends(ExportProgress.RenderBackend.WEBGPU, EncodeBackend.FFMPEG)
				.withEncoderName("h264_nvenc");
		ExportProgress saving = ExportProgressState.resolveSavingExportProgress(previous);
		assertEquals(120, saving.currentFrame());
		assertEquals(120, saving.totalFrames());
		assertEquals(100, saving.percentage());
		assertEquals(0, saving.estimatedTimeRemaining());
		assertEquals(144.25, saving.renderFps());
		assertEquals(ExportProgress.RenderBackend.WEBGPU, saving.renderBackend());
		assertEquals(EncodeBackend.FFMPEG, saving.encodeBackend());
		assertEquals("h264_nvenc", saving.encoderName());
		assertEquals(ExportProgress.Phase.SAVING, saving.phase());
	}

	@Test
	@DisplayName("preserves the existing zero-total-frame behavior")
	void zeroTotalFrames() {
		ExportProgress saving = ExportProgressState.resolveSavingExportProgress(
				ExportProgress.of(36, 0, 0, 0));
		assertEquals(0, saving.currentFrame());
		assertEquals(0, saving.totalFrames());
		assertEquals(ExportProgress.Phase.SAVING, saving.phase());
	}

	@Test
	@DisplayName("uses a minimal fallback when there is no previous progress")
	void noPreviousProgress() {
		ExportProgress saving = ExportProgressState.resolveSavingExportProgress(null);
		assertEquals(1, saving.currentFrame());
		assertEquals(1, saving.totalFrames());
		assertEquals(100, saving.percentage());
		assertEquals(0, saving.estimatedTimeRemaining());
		assertNull(saving.renderFps());
		assertNull(saving.renderBackend());
		assertNull(saving.encodeBackend());
		assertNull(saving.encoderName());
		assertEquals(ExportProgress.Phase.SAVING, saving.phase());
	}
}
