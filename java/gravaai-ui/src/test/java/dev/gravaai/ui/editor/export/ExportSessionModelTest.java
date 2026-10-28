package dev.gravaai.ui.editor.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.export.CaptionSidecars;
import dev.gravaai.media.export.ExportProgress;

/** Port of {@code useExportSession.ts} state rules and the popover screen mapping (plan §5.12). */
class ExportSessionModelTest {

	@Test
	@DisplayName("maps the popover screen from the state flags")
	void screenMapping() {
		ExportSessionModel model = new ExportSessionModel();
		assertEquals(ExportSessionModel.Screen.SETTINGS, model.screen());
		model.setExporting(true);
		assertEquals(ExportSessionModel.Screen.PROGRESS, model.screen());
		model.setExporting(false);
		model.setError("boom");
		assertEquals(ExportSessionModel.Screen.ERROR, model.screen());
		model.setError(null);
		model.setExportedFilePath("/tmp/out.mp4");
		assertEquals(ExportSessionModel.Screen.COMPLETE, model.screen());
	}

	@Test
	@DisplayName("notifies listeners only when a value actually changes")
	void changeNotifications() {
		ExportSessionModel model = new ExportSessionModel();
		AtomicInteger changes = new AtomicInteger();
		model.onChange(changes::incrementAndGet);
		model.setError("boom");
		model.setError("boom");
		assertEquals(1, changes.get());
		model.setError(null);
		assertEquals(2, changes.get());
	}

	@Test
	@DisplayName("run ids ignore late callbacks of cancelled runs")
	void runIds() {
		ExportSessionModel model = new ExportSessionModel();
		int first = model.beginExportRun();
		assertTrue(model.isCurrentRun(first));
		assertFalse(model.isCancelledRun(first));
		model.cancelRun();
		assertFalse(model.isCurrentRun(first));
		assertTrue(model.isCancelledRun(first));
		int second = model.beginExportRun();
		assertTrue(model.isCurrentRun(second));
		assertFalse(model.isCancelledRun(second));
	}

	@Test
	@DisplayName("markExportAsSaving pins 100% with the saving phase (?? fallback chain)")
	void markExportAsSaving() {
		ExportSessionModel model = new ExportSessionModel();
		// No previous progress: totalFrames falls back to 1 (0 is not null in the TS chain).
		model.markExportAsSaving();
		ExportProgress pinned = model.progress();
		assertEquals(1, pinned.currentFrame());
		assertEquals(1, pinned.totalFrames());
		assertEquals(100, pinned.percentage());
		assertEquals(ExportProgress.Phase.SAVING, pinned.phase());
		// A previous snapshot keeps its frame counts and metadata.
		ExportProgress previous = ExportProgress.of(10, 100, 50, 30).withRenderFps(42);
		model.setProgress(previous);
		model.markExportAsSaving();
		ExportProgress next = model.progress();
		assertEquals(100, next.currentFrame());
		assertEquals(100, next.totalFrames());
		assertEquals(100, next.percentage());
		assertEquals(ExportProgress.Phase.SAVING, next.phase());
		assertEquals(42, next.renderFps());
	}

	@Test
	@DisplayName("pending saves round-trip and clear without a value change on empty")
	void pendingSaves() {
		ExportSessionModel model = new ExportSessionModel();
		AtomicInteger changes = new AtomicInteger();
		model.onChange(changes::incrementAndGet);
		assertNull(model.clearPendingExportSave());
		assertEquals(0, changes.get());
		ExportSessionModel.PendingExportSave pending = new ExportSessionModel.PendingExportSave(
				"export-1.mp4", Path.of("/tmp/staging/export-1.mp4"),
				new CaptionSidecars.Payload(CaptionSidecars.Format.BOTH, List.of()));
		model.setPendingExportSave(pending);
		assertTrue(model.hasPendingExportSave());
		assertSame(pending, model.pendingExportSave());
		assertEquals(1, changes.get());
		assertSame(pending, model.clearPendingExportSave());
		assertFalse(model.hasPendingExportSave());
		assertNull(model.pendingExportSave());
	}

	@Test
	@DisplayName("a pending save without a staged file does not flag the pending state")
	void pendingSaveWithoutStagedFile() {
		ExportSessionModel model = new ExportSessionModel();
		model.setPendingExportSave(new ExportSessionModel.PendingExportSave("export-1.mp4", null,
				null));
		assertFalse(model.hasPendingExportSave());
	}
}
