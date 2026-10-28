package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the timecode helpers in {@code CaptionListPanel.tsx}. */
class CaptionListPanelTest {

	@Test
	@DisplayName("formats caption timecodes as m:ss.mmm")
	void formatsTimecodes() {
		assertEquals("0:00.000", CaptionListPanel.formatTimecode(0));
		assertEquals("0:01.234", CaptionListPanel.formatTimecode(1_234));
		assertEquals("1:01.000", CaptionListPanel.formatTimecode(61_000));
		assertEquals("0:00.000", CaptionListPanel.formatTimecode(-50));
		assertEquals("62:03.456", CaptionListPanel.formatTimecode(3_723_456));
	}

	@Test
	@DisplayName("parses caption timecodes like the React parser")
	void parsesTimecodes() {
		assertEquals(62_000L, CaptionListPanel.parseTimecode("1:02"));
		assertEquals(62_500L, CaptionListPanel.parseTimecode("1:02.5"));
		assertEquals(1_234L, CaptionListPanel.parseTimecode("1.234"));
		assertEquals(90_000L, CaptionListPanel.parseTimecode("90"));
		assertEquals(5_000L, CaptionListPanel.parseTimecode("0:05"));
		assertNull(CaptionListPanel.parseTimecode("abc"));
		assertNull(CaptionListPanel.parseTimecode("1:2:3"));
		assertNull(CaptionListPanel.parseTimecode(".5"));
		assertNull(CaptionListPanel.parseTimecode(null));
	}
}
