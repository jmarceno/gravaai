package dev.gravaai.ui.editor.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportPipelineModel;
import dev.gravaai.media.export.ExportCancelledException;
import dev.gravaai.media.export.ExportException;
import dev.gravaai.media.export.ExportProgress;
import dev.gravaai.ui.i18n.UiI18n;

/**
 * Port of {@code useExportStatusViewModel} label rules (plan §5.12). The labels resolve through
 * {@code UiI18n}; the expectations below follow the same keys so the rules — not the English
 * wording — are what is pinned (the harness runs before {@code init}, i.e. English).
 */
class ExportProgressViewModelTest {

	private static ExportProgress progress() {
		return ExportProgress.of(10, 100, 10, 30);
	}

	@Test
	@DisplayName("shows preparing before the first progress snapshot")
	void preparing() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(true, null, ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals(UiI18n.t("editor:exportStatus.preparing"), model.exportPercentLabel());
		assertTrue(model.canCancel());
		assertEquals(0.0, model.progressFraction());
	}

	@Test
	@DisplayName("formats the render percentage and render speed")
	void renderLabels() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(true, progress().withRenderFps(123.456), ExportFormat.MP4,
				ExportPipelineModel.MODERN);
		assertEquals(UiI18n.t("editor:exportStatus.completePercent", Map.of("percent", 10L)),
				model.exportPercentLabel());
		assertEquals(UiI18n.t("dialogs:export.renderSpeed", Map.of("fps", 123.5)) + " · "
						+ UiI18n.t("dialogs:export.timeLeft", Map.of("time", "0:30")),
				model.exportRenderSpeedLabel());
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
		assertEquals(UiI18n.t("dialogs:export.openingSaveDialog"), model.exportPercentLabel());
		model.update(true, progress().withPhase(ExportProgress.Phase.FINALIZING)
				.withAudioProgress(0.42), ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals(UiI18n.t("dialogs:export.renderingAudio", Map.of("percent", 42L)),
				model.exportPercentLabel());
		assertEquals(0.42, model.progressFraction(), 1e-9);
	}

	@Test
	@DisplayName("shows muxing progress and the indeterminate muxing label for modern MP4")
	void muxingLabels() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(true, progress().withPhase(ExportProgress.Phase.FINALIZING)
				.withRenderProgress(50), ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals(UiI18n.t("dialogs:export.muxingSaving", Map.of("percent", 50L)),
				model.exportPercentLabel());
		assertEquals(0.5, model.progressFraction(), 1e-9);
		model.update(true, progress().withPhase(ExportProgress.Phase.FINALIZING)
				.withRenderProgress(104.4), ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertEquals(UiI18n.t("dialogs:export.muxingSavingFile"), model.exportPercentLabel());
	}

	@Test
	@DisplayName("uses the plain finalizing label for legacy and GIF exports")
	void plainFinalizingLabels() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(true, progress().withPhase(ExportProgress.Phase.FINALIZING)
				.withRenderProgress(50), ExportFormat.MP4, ExportPipelineModel.LEGACY);
		assertEquals(UiI18n.t("dialogs:export.finalizing", Map.of("percent", 50L)),
				model.exportPercentLabel());
		model.update(true, progress().withPhase(ExportProgress.Phase.FINALIZING)
				.withRenderProgress(50), ExportFormat.GIF, ExportPipelineModel.MODERN);
		assertEquals(UiI18n.t("dialogs:export.finalizing", Map.of("percent", 50L)),
				model.exportPercentLabel());
	}

	@Test
	@DisplayName("cancellable only while exporting and errors are formatted without cancellations")
	void cancelAndErrors() {
		ExportProgressViewModel model = new ExportProgressViewModel();
		model.update(false, progress(), ExportFormat.MP4, ExportPipelineModel.MODERN);
		assertFalse(model.canCancel());
		assertEquals(UiI18n.t("dialogs:export.failedWithError", Map.of("error", "boom")),
				ExportProgressViewModel.errorText(new ExportException("boom")));
		assertNull(ExportProgressViewModel.errorText(new ExportCancelledException()));
		assertNull(ExportProgressViewModel.errorText(null));
	}
}
