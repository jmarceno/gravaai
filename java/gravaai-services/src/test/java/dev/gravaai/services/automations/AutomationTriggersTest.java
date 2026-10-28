package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Port of {@code electron/automations/triggers.test.ts}. The TS test flushes microtasks; the
 * Java dispatcher is synchronous, so events are observable immediately.
 */
class AutomationTriggersTest {

	private final List<AutomationEvent> events = new ArrayList<>();
	private final AtomicLong now = new AtomicLong(1_000_000);
	private AutomationTriggers triggers;

	@BeforeEach
	void setUp() {
		events.clear();
		triggers = new AutomationTriggers(events::add, now::get);
	}

	@Test
	@DisplayName("formatForExportPath maps mp4 and gif case-insensitively")
	void formatForExportPath() {
		assertEquals(AutomationExportFormat.MP4, AutomationTriggers.formatForExportPath("/tmp/a.mp4"));
		assertEquals(AutomationExportFormat.GIF, AutomationTriggers.formatForExportPath("/tmp/a.GIF"));
		assertNull(AutomationTriggers.formatForExportPath("/tmp/a.mkv"));
		assertNull(AutomationTriggers.formatForExportPath("/tmp/no-extension"));
	}

	@Test
	@DisplayName("shouldTriggerExportFinished deduplicates the same path inside the window")
	void deduplicatesExports() {
		long start = 1_000_000;
		assertTrue(triggers.shouldTriggerExportFinished("/tmp/a.mp4", start));
		assertFalse(triggers.shouldTriggerExportFinished("/tmp/a.mp4", start + 1_000));
		assertFalse(triggers.shouldTriggerExportFinished(
				"/tmp/a.mp4", start + AutomationTriggers.EXPORT_FINISHED_DEDUPE_MS - 1));
		assertTrue(triggers.shouldTriggerExportFinished(
				"/tmp/a.mp4", start + AutomationTriggers.EXPORT_FINISHED_DEDUPE_MS));
		assertTrue(triggers.shouldTriggerExportFinished(
				"/tmp/b.mp4", start + AutomationTriggers.EXPORT_FINISHED_DEDUPE_MS));
	}

	@Test
	@DisplayName("shouldTriggerExportFinished forgets everything on reset")
	void resetsDedupe() {
		assertTrue(triggers.shouldTriggerExportFinished("/tmp/a.mp4", 1));
		triggers.resetAutomationTriggerState();
		assertTrue(triggers.shouldTriggerExportFinished("/tmp/a.mp4", 2));
	}

	@Test
	@DisplayName("fireExportFinished fires export-finished once per path with the detected format")
	void firesExportOnce() {
		triggers.fireExportFinished("/tmp/out.mp4");
		triggers.fireExportFinished("/tmp/out.mp4");
		assertEquals(1, events.size());
		assertEquals(
				new AutomationEvent(
						AutomationTrigger.EXPORT_FINISHED, "/tmp/out.mp4", null, AutomationExportFormat.MP4, null, null, false),
				events.get(0));

		triggers.fireExportFinished("/tmp/out.gif");
		assertEquals(
				new AutomationEvent(
						AutomationTrigger.EXPORT_FINISHED, "/tmp/out.gif", null, AutomationExportFormat.GIF, null, null, false),
				events.get(1));
	}

	@Test
	@DisplayName("fireExportFinished ignores empty paths and unsupported formats")
	void ignoresBadExports() {
		triggers.fireExportFinished("");
		triggers.fireExportFinished("/tmp/out.mkv");
		assertEquals(0, events.size());
	}

	@Test
	@DisplayName("fireRecordingSaved ignores failed or pathless stops")
	void ignoresFailedStops() {
		triggers.fireRecordingSaved(null);
		triggers.fireRecordingSaved(new AutomationTriggers.RecordingSavedResult(false, "/tmp/a.mp4", false));
		triggers.fireRecordingSaved(new AutomationTriggers.RecordingSavedResult(true, null, false));
		assertEquals(0, events.size());
	}

	@Test
	@DisplayName("fireRecordingSaved fires recording-saved for screen recordings")
	void firesScreenRecording() {
		triggers.fireRecordingSaved(new AutomationTriggers.RecordingSavedResult(true, "/tmp/screen.mp4", false));
		assertEquals(
				new AutomationEvent(
						AutomationTrigger.RECORDING_SAVED,
						"/tmp/screen.mp4",
						AutomationMediaKind.SCREEN,
						null,
						null,
						null,
						false),
				events.get(0));
	}

	@Test
	@DisplayName("fireRecordingSaved fires meeting-saved for meeting recordings")
	void firesMeetingRecording() {
		triggers.fireRecordingSaved(new AutomationTriggers.RecordingSavedResult(true, "/tmp/meeting.mp4", true));
		assertEquals(
				new AutomationEvent(
						AutomationTrigger.MEETING_SAVED,
						"/tmp/meeting.mp4",
						AutomationMediaKind.MEETING,
						null,
						null,
						null,
						false),
				events.get(0));
	}

	@Test
	@DisplayName("isWebcamRecordingPath matches only the webcam track of a browser recording")
	void webcamPaths() {
		assertTrue(AutomationTriggers.isWebcamRecordingPath("/rec/recording-1700000000000-webcam.webm"));
		assertTrue(AutomationTriggers.isWebcamRecordingPath("C:\\rec\\recording-1700000000000-webcam.mp4"));
		assertFalse(AutomationTriggers.isWebcamRecordingPath("/rec/recording-1700000000000.webm"));
		assertFalse(AutomationTriggers.isWebcamRecordingPath("/rec-webcam.d/recording-1.mp4"));
	}

	@Test
	@DisplayName("fireMeetingNotesReady passes the media path and the notes path")
	void firesMeetingNotes() {
		triggers.fireMeetingNotesReady("/tmp/meeting.m4a", "/tmp/meeting.notes.md");
		assertEquals(
				new AutomationEvent(
						AutomationTrigger.MEETING_NOTES_READY,
						"/tmp/meeting.m4a",
						AutomationMediaKind.MEETING,
						null,
						"/tmp/meeting.notes.md",
						null,
						false),
				events.get(0));
	}

	@Test
	@DisplayName("fireMeetingNotesReady ignores an empty media path")
	void ignoresEmptyMeetingMedia() {
		triggers.fireMeetingNotesReady("", "/tmp/meeting.notes.md");
		assertEquals(0, events.size());
	}

	@Test
	@DisplayName("a throwing dispatcher never breaks the caller")
	void throwingDispatcherIsContained() {
		AutomationTriggers failing = new AutomationTriggers(event -> {
			throw new IllegalStateException("boom");
		}, now::get);
		failing.fireMeetingNotesReady("/tmp/meeting.m4a", null);
	}
}
