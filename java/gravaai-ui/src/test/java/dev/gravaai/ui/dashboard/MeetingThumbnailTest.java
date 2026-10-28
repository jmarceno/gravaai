package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.ui.meeting.MeetingText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure label logic of the meeting card thumbnail (port of {@code MeetingThumbnail.tsx}). */
class MeetingThumbnailTest {

	private static MeetingLibraryEntry meeting(boolean hasTranscript, boolean hasNotes,
			MeetingLibraryEntry.Status status) {
		return new MeetingLibraryEntry(Path.of("/tmp/meetings/x.m4a"), "Meeting", 0L, 90_000L,
				false, hasTranscript, hasNotes, status, null);
	}

	@Test
	@DisplayName("notes win over the transcript, then Not transcribed")
	void stateLabels() {
		assertEquals(MeetingThumbnail.NOTES_READY,
				MeetingThumbnail.stateLabel(meeting(true, true, MeetingLibraryEntry.Status.IDLE)));
		assertEquals(MeetingThumbnail.TRANSCRIPT_READY,
				MeetingThumbnail.stateLabel(meeting(true, false, MeetingLibraryEntry.Status.IDLE)));
		assertEquals(MeetingThumbnail.NOT_TRANSCRIBED,
				MeetingThumbnail.stateLabel(meeting(false, false, MeetingLibraryEntry.Status.IDLE)));

		assertTrue(MeetingThumbnail.hasNotesIcon(meeting(true, true, MeetingLibraryEntry.Status.IDLE)));
		assertFalse(MeetingThumbnail.hasNotesIcon(
				meeting(true, false, MeetingLibraryEntry.Status.IDLE)));
	}

	@Test
	@DisplayName("only active and failed jobs show a status badge, like STATUS_LABEL")
	void statusBadges() {
		assertEquals(null, MeetingText.libraryStatusLabel(MeetingLibraryEntry.Status.IDLE));
		assertEquals("Waiting", MeetingText.libraryStatusLabel(MeetingLibraryEntry.Status.QUEUED));
		assertEquals("Processing…",
				MeetingText.libraryStatusLabel(MeetingLibraryEntry.Status.RUNNING));
		assertEquals("Failed", MeetingText.libraryStatusLabel(MeetingLibraryEntry.Status.FAILED));
		assertEquals("Interrupted",
				MeetingText.libraryStatusLabel(MeetingLibraryEntry.Status.INTERRUPTED));
	}

	@Test
	@DisplayName("formats the duration chip with the MeetingView helper")
	void durationChip() {
		assertEquals("12 min 30 s", MeetingText.formatMeetingDuration(750_000L));
		assertEquals("30 min 0 s", MeetingText.formatMeetingDuration(1_800_000L));
		assertEquals("1 h 0 min", MeetingText.formatMeetingDuration(3_600_000L));
		assertEquals(null, MeetingText.formatMeetingDuration(0L));
		assertEquals(null, MeetingText.formatMeetingDuration(null));
	}
}
