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

	@DisplayName("formatTime matches the React m:ss formatting")
	@Test
	void formatTime() {
		assertEquals("0:00", PlaybackControls.formatTime(0));
		assertEquals("0:09", PlaybackControls.formatTime(9.9));
		assertEquals("1:05", PlaybackControls.formatTime(65));
		assertEquals("0:00", PlaybackControls.formatTime(-3));
		assertEquals("0:00", PlaybackControls.formatTime(Double.NaN));
	}
}
