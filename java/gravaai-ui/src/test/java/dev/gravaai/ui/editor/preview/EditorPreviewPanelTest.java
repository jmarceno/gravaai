package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure aspect-ratio helpers of the preview panel (getAspectRatioValue/Label ports). */
class EditorPreviewPanelTest {

	@DisplayName("aspect labels keep the project strings, native becomes Native")
	@Test
	void labels() {
		assertEquals("16:9", EditorPreviewPanel.aspectLabelFor("16:9"));
		assertEquals("9:16", EditorPreviewPanel.aspectLabelFor("9:16"));
		assertEquals("Native", EditorPreviewPanel.aspectLabelFor("native"));
		assertEquals("16:9", EditorPreviewPanel.aspectLabelFor(null));
	}

	@DisplayName("aspect values match getAspectRatioValue")
	@Test
	void values() {
		assertEquals(16.0 / 9.0, EditorPreviewPanel.aspectValue("16:9"), 1e-9);
		assertEquals(9.0 / 16.0, EditorPreviewPanel.aspectValue("9:16"), 1e-9);
		assertEquals(1, EditorPreviewPanel.aspectValue("1:1"), 1e-9);
		assertEquals(4.0 / 3.0, EditorPreviewPanel.aspectValue("4:3"), 1e-9);
		assertEquals(16.0 / 9.0, EditorPreviewPanel.aspectValue(null), 1e-9);
	}

	@DisplayName("formatClock matches the mock's HH:MM:SS transport timecode")
	@Test
	void formatClock() {
		assertEquals("00:00:00", PlaybackControls.formatClock(0));
		assertEquals("00:00:09", PlaybackControls.formatClock(9.9));
		assertEquals("00:01:05", PlaybackControls.formatClock(65));
		assertEquals("00:14:22", PlaybackControls.formatClock(14 * 60 + 22.03));
		assertEquals("01:01:01", PlaybackControls.formatClock(3661));
		assertEquals("00:00:00", PlaybackControls.formatClock(-3));
		assertEquals("00:00:00", PlaybackControls.formatClock(Double.NaN));
	}

	@DisplayName("formatCentis truncates against the floored second the clock shows")
	@Test
	void formatCentis() {
		assertEquals(".00", PlaybackControls.formatCentis(0));
		assertEquals(".03", PlaybackControls.formatCentis(14 * 60 + 22.03));
		assertEquals(".90", PlaybackControls.formatCentis(9.9));
		assertEquals(".99", PlaybackControls.formatCentis(65.999));
		assertEquals(".00", PlaybackControls.formatCentis(-3));
		assertEquals(".00", PlaybackControls.formatCentis(Double.NaN));
	}

	@DisplayName("formatDuration matches the mock's MM:SS.cs, hours included past 1h")
	@Test
	void formatDuration() {
		assertEquals("00:00.00", PlaybackControls.formatDuration(0));
		assertEquals("00:09.90", PlaybackControls.formatDuration(9.9));
		assertEquals("01:05.00", PlaybackControls.formatDuration(65));
		assertEquals("42:18.11", PlaybackControls.formatDuration(42 * 60 + 18.11));
		assertEquals("01:01:01.50", PlaybackControls.formatDuration(3661.5));
		assertEquals("00:00.00", PlaybackControls.formatDuration(-3));
		assertEquals("00:00.00", PlaybackControls.formatDuration(Double.NaN));
	}
}
