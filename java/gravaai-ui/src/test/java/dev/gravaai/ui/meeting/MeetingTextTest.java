package dev.gravaai.ui.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import dev.gravaai.services.meeting.MeetingJob;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.meeting.MeetingMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the formatters in {@code MeetingView.tsx}, {@code MeetingThumbnail.tsx} and the settings panel. */
class MeetingTextTest {

	@Test
	@DisplayName("prefers the saved meeting title")
	void prefersSavedTitle() {
		MeetingMeta meta = new MeetingMeta("meeting", "2026-10-27T12:00:00Z", true, null, 3_600_000L,
				"  Weekly sync  ", null, null, null, null, null, null);

		assertEquals("Weekly sync", MeetingText.meetingTitle(meta, null));
	}

	@Test
	@DisplayName("falls back to a date and time, then to Meeting")
	void fallsBackToDateTime() {
		assertEquals("Meeting", MeetingText.meetingTitle(null, null));
		assertEquals("Meeting", MeetingText.meetingTitle(new MeetingMeta(null, null, null, null, null,
				null, null, null, null, null, null, null), null));

		String fromMeta = MeetingText.meetingTitle(new MeetingMeta(null, "2026-10-27T12:00:00Z", null,
				null, null, null, null, null, null, null, null, null), null);
		assertTrue(fromMeta.startsWith("Meeting "), fromMeta);
		assertTrue(fromMeta.contains(":"), fromMeta);

		String fromFallback = MeetingText.meetingTitle(null, 1_800_000_000_000L);
		assertTrue(fromFallback.startsWith("Meeting "), fromFallback);
	}

	@Test
	@DisplayName("formats meeting durations like the React helper")
	void formatsDurations() {
		assertNull(MeetingText.formatMeetingDuration(null));
		assertNull(MeetingText.formatMeetingDuration(0L));
		assertEquals("45 s", MeetingText.formatMeetingDuration(45_000L));
		assertEquals("5 min 5 s", MeetingText.formatMeetingDuration(305_000L));
		assertEquals("1 h 1 min", MeetingText.formatMeetingDuration(3_661_000L));
	}

	@Test
	@DisplayName("formats asset sizes as GB with one decimal or whole MB")
	void formatsAssetSizes() {
		assertEquals("35 MB", MeetingText.formatSize(34_989_558L));
		assertEquals("574 MB", MeetingText.formatSize(574_041_195L));
		assertEquals("1.3 GB", MeetingText.formatSize(1_280_835_840L));
		assertEquals("1 MB", MeetingText.formatSize(885_098L));
	}

	@Test
	@DisplayName("maps library job states to the thumbnail badge labels")
	void mapsStatusLabels() {
		assertNull(MeetingText.libraryStatusLabel(MeetingLibraryEntry.Status.IDLE));
		assertNull(MeetingText.libraryStatusLabel(null));
		assertEquals("Waiting", MeetingText.libraryStatusLabel(MeetingLibraryEntry.Status.QUEUED));
		assertEquals("Processing…", MeetingText.libraryStatusLabel(MeetingLibraryEntry.Status.RUNNING));
		assertEquals("Failed", MeetingText.libraryStatusLabel(MeetingLibraryEntry.Status.FAILED));
		assertEquals("Interrupted",
				MeetingText.libraryStatusLabel(MeetingLibraryEntry.Status.INTERRUPTED));
	}

	@Test
	@DisplayName("labels the pipeline stages and the waiting state")
	void labelsStages() {
		assertEquals("Waiting to start", MeetingText.stageLabel(null));
		assertEquals("Transcribing", MeetingText.stageLabel(MeetingJob.Stage.TRANSCRIBE));
		assertEquals("Writing notes", MeetingText.stageLabel(MeetingJob.Stage.SUMMARIZE));
		assertEquals("Naming the meeting", MeetingText.stageLabel(MeetingJob.Stage.TITLE));
	}

	@Test
	@DisplayName("treats queued and running jobs as active")
	void detectsActiveJobs() {
		MeetingJob queued = new MeetingJob("id", java.nio.file.Path.of("/tmp/a.mp4"), null,
				MeetingJob.Status.QUEUED, null, null, null, null, null, null);
		MeetingJob done = new MeetingJob("id", java.nio.file.Path.of("/tmp/a.mp4"), null,
				MeetingJob.Status.DONE, null, null, null, null, null, null);
		assertTrue(MeetingText.isActive(queued));
		assertTrue(!MeetingText.isActive(done));
		assertTrue(!MeetingText.isActive(null));
	}
}
