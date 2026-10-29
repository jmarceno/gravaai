package dev.gravaai.ui.editor.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportQuality;
import dev.gravaai.core.editor.ExportPreferences.GifSizePreset;

/**
 * Port of {@code useExportSettings.ts} setters: every change replaces the snapshot, persists it and
 * notifies the view (plan §5.12).
 */
class ExportSettingsStateTest {

	@Test
	@DisplayName("setters replace the snapshot, persist and notify")
	void setters() {
		ExportPreferences initial = ExportPreferences.defaults();
		List<ExportPreferences> persisted = new ArrayList<>();
		ExportSettingsState state = new ExportSettingsState(initial, persisted::add);
		AtomicInteger changes = new AtomicInteger();
		state.onChange(changes::incrementAndGet);

		state.setExportFormat(ExportFormat.GIF);
		assertEquals(ExportFormat.GIF, state.exportFormat());
		state.setExportQuality(ExportQuality.GOOD);
		assertEquals(ExportQuality.GOOD, state.exportQuality());
		state.setExportEncodingMode(ExportEncodingMode.QUALITY);
		assertEquals(ExportEncodingMode.QUALITY, state.exportEncodingMode());
		state.setMp4FrameRate(60);
		assertEquals(60, state.mp4FrameRate());
		state.setGifFrameRate(30);
		assertEquals(30, state.gifFrameRate());
		state.setGifLoop(false);
		assertFalse(state.gifLoop());
		state.setGifSizePreset(GifSizePreset.LARGE);
		assertEquals(GifSizePreset.LARGE, state.gifSizePreset());
		state.setIncludeCaptionSidecar(true);
		assertTrue(state.includeCaptionSidecar());

		// Every setter persisted the full snapshot and notified once.
		assertEquals(8, persisted.size());
		assertEquals(8, changes.get());
		ExportPreferences last = persisted.get(persisted.size() - 1);
		assertEquals(ExportFormat.GIF, last.exportFormat());
		assertEquals(ExportQuality.GOOD, last.exportQuality());
		assertEquals(ExportEncodingMode.QUALITY, last.exportEncodingMode());
		assertEquals(60, last.mp4FrameRate());
		assertEquals(30, last.gifFrameRate());
		assertFalse(last.gifLoop());
		assertEquals(GifSizePreset.LARGE, last.gifSizePreset());
		assertTrue(last.includeCaptionSidecar());
		// Unrelated fields survive the rebuild.
		assertEquals(initial.exportBackendPreference(), last.exportBackendPreference());
		assertEquals(initial.publishDestination(), last.publishDestination());
	}

	@Test
	@DisplayName("identical values do not persist or notify")
	void noChangeNotifications() {
		ExportSettingsState state = new ExportSettingsState(ExportPreferences.defaults(),
				preferences -> { });
		AtomicInteger changes = new AtomicInteger();
		state.onChange(changes::incrementAndGet);
		state.setExportFormat(state.exportFormat());
		state.setExportQuality(state.exportQuality());
		state.setExportEncodingMode(state.exportEncodingMode());
		state.setMp4FrameRate(state.mp4FrameRate());
		state.setGifFrameRate(state.gifFrameRate());
		state.setGifLoop(state.gifLoop());
		state.setGifSizePreset(state.gifSizePreset());
		state.setIncludeCaptionSidecar(state.includeCaptionSidecar());
		assertEquals(0, changes.get());
	}

	@Test
	@DisplayName("null enum values are ignored")
	void nullValues() {
		ExportSettingsState state = new ExportSettingsState(ExportPreferences.defaults(),
				preferences -> { });
		AtomicInteger changes = new AtomicInteger();
		state.onChange(changes::incrementAndGet);
		state.setExportFormat(null);
		state.setExportQuality(null);
		state.setExportEncodingMode(null);
		state.setGifSizePreset(null);
		assertEquals(0, changes.get());
	}

	@Test
	@DisplayName("the persisted snapshot serialises to the 11 export JSON keys")
	void toJsonKeys() {
		ExportSettingsState state = new ExportSettingsState(ExportPreferences.defaults(),
				preferences -> { });
		state.setExportFormat(ExportFormat.GIF);
		var json = state.preferences().toJson();
		assertEquals(11, json.size());
		assertEquals("gif", json.get("exportFormat").asString());
	}
}
