package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from {@code electron/ipc/capturePickerSources.test.ts} (Linux case; macOS/Windows are N/A). */
class CapturePickerSourcesTest {

	@Test
	@DisplayName("keeps Linux window selection on its source list without X11 helper probes")
	void keepsLinuxWindowSelectionOnItsSourceList() {
		UnsupportedOperationException error = assertThrows(
				UnsupportedOperationException.class, CapturePickerSources::listCapturePickerWindows);
		assertEquals("Use the source list on Linux", error.getMessage());
	}
}
