package dev.gravaai.ui.editor.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportPipelineModel;
import dev.gravaai.media.export.ExportCancelledException;
import dev.gravaai.media.export.ExportException;
import dev.gravaai.media.export.ExportProgress;

/** Port of {@code useExportStatusViewModel} label rules (plan §5.12). */
class ExportProgressViewModelTest {

	private static ExportProgress progress() {
		return ExportProgress.of(10, 100, 10, 30);
	}

	@Test
	@DisplayName("shows preparing before the first progress snapshot")
	void preparing() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(true, null, ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals("Preparing export...", model.exportPercentLabel());
		assertTrue(model.canCancel());
		assertEquals(0.0, model.progressFraction());
	}

	@Test
	@DisplayName("formats the render percentage and render speed")
	void renderLabels() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(true, progress().withRenderFps(123.456), ExportFormat.MP4,
				ExportPipelineModel.MODERN);
		assertEquals("10% complete", model.exportPercentLabel());
		assertEquals("Render speed 123.5 FPS · 0:30 left", model.exportRenderSpeedLabel());
		assertEquals(0.10, model.progressFraction(), 1e-9);
	}

	@Test
	@DisplayName("formats the time left as m:ss or h:mm:ss")
	void remainingFormat() {
		assertEquals("0:05", ExportProgressViewModel.formatRemaining(5));
		assertEquals("4:32", ExportProgressViewModel.formatRemaining(272));
		assertEquals("1:02:03", ExportProgressViewModel.formatRemaining(3723));
	}

	@Test
	@DisplayName("shows save-dialog and audio finalization labels")
	void savingAndAudioLabels() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(true, progress().withPhase(ExportProgress.Phase.SAVING), ExportFormat.MP4,
				ExportPipelineModel.MODERN);
		assertEquals("Opening save dialog...", model.exportPercentLabel());
		model.update(true, progress().withPhase(ExportProgress.Phase.FINALIZING)
				.withAudioProgress(0.42), ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals("Rendering audio 42%", model.exportPercentLabel());
		assertEquals(0.42, model.progressFraction(), 1e-9);
	}

	@Test
	@DisplayName("shows muxing progress and the indeterminate muxing label for modern MP4")
	void muxingLabels() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(true, progress().withPhase(ExportProgress.Phase.FINALIZING)
				.withRenderProgress(50), ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals("Muxing and saving 50%", model.exportPercentLabel());
		assertEquals(0.5, model.progressFraction(), 1e-9);
		model.update(true, progress().withPhase(ExportProgress.Phase.FINALIZING)
				.withRenderProgress(104.4), ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals("Muxing audio and saving file...", model.exportPercentLabel());
	}

	@Test
	@DisplayName("uses the plain finalizing label for legacy and GIF exports")
	void plainFinalizingLabels() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(true, progress().withPhase(ExportProgress.Phase.FINALIZING)
				.withRenderProgress(50), ExportFormat.MP4, ExportPipelineModel.LEGACY);
		assertEquals("Finalizing 50%", model.exportPercentLabel());
		model.update(true, progress().withPhase(ExportProgress.Phase.FINALIZING)
				.withRenderProgress(50), ExportFormat.GIF, ExportPipelineModel.MODERN);
		assertEquals("Finalizing 50%", model.exportPercentLabel());
	}

	@Test
	@DisplayName("cancellable only while exporting and errors are formatted without cancellations")
	void cancelAndErrors() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(false, progress(), ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertFalse(model.canCancel());
		assertEquals("Export failed: boom", ExportProgressViewModel.errorText(
				new ExportException("boom")));
		assertNull(ExportProgressViewModel.errorText(new ExportCancelledException()));
		assertNull(ExportProgressViewModel.errorText(null));
	}
}
