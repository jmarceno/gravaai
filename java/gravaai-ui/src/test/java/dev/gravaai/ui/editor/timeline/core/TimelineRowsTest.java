package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code timeline/core/rows.test.ts}. */
class TimelineRowsTest {

	@Test
	@DisplayName("builds and parses annotation rows")
	void buildsAndParsesAnnotationRows() {
		assertEquals("row-annotation-2", TimelineRows.getAnnotationTrackRowId(2.9));
		assertEquals("row-annotation-0", TimelineRows.getAnnotationTrackRowId(-5));
		assertEquals(4, TimelineRows.getAnnotationTrackIndex("row-annotation-4"));
		assertEquals(0, TimelineRows.getAnnotationTrackIndex("row-annotation"));
		assertTrue(TimelineRows.isAnnotationTrackRowId("row-annotation"));
		assertTrue(TimelineRows.isAnnotationTrackRowId("row-annotation-1"));
	}

	@Test
	@DisplayName("handles invalid annotation row IDs safely")
	void handlesInvalidAnnotationRowIds() {
		assertEquals(0, TimelineRows.getAnnotationTrackIndex("row-annotation-foo"));
		assertEquals(0, TimelineRows.getAnnotationTrackIndex("other"));
		assertFalse(TimelineRows.isAnnotationTrackRowId("row-audio-1"));
	}

	@Test
	@DisplayName("builds and parses audio rows")
	void buildsAndParsesAudioRows() {
		assertEquals("row-audio-1", TimelineRows.getAudioTrackRowId(1.2));
		assertEquals("row-audio-0", TimelineRows.getAudioTrackRowId(-3));
		assertEquals(3, TimelineRows.getAudioTrackIndex("row-audio-3"));
		assertEquals(0, TimelineRows.getAudioTrackIndex("row-audio"));
		assertTrue(TimelineRows.isAudioTrackRowId("row-audio"));
		assertTrue(TimelineRows.isAudioTrackRowId("row-audio-1"));
	}

	@Test
	@DisplayName("handles invalid audio row IDs safely")
	void handlesInvalidAudioRowIds() {
		assertEquals(0, TimelineRows.getAudioTrackIndex("row-audio-foo"));
		assertEquals(0, TimelineRows.getAudioTrackIndex("other"));
		assertFalse(TimelineRows.isAudioTrackRowId("row-annotation-1"));
	}
}
