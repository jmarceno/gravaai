package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.document.DocumentBlock;
import dev.gravaai.services.document.DocumentFormat;
import dev.gravaai.services.document.PlainDocument;

/** The header and naming rules of the meeting notes export. */
class MeetingNotesExportTest {

	@Test
	@DisplayName("lists the date, the duration and the speakers the transcript names")
	void collectsMetadata() {
		MeetingMeta meta = new MeetingMeta("meeting", "2026-11-12T12:00:00.000Z", true, "en",
				2_700_000L, "Weekly sync", null, null, null, null, null, null, null);

		List<String> metadata = MeetingNotesExport.metadata(meta, """
				[00:00:12] **Me:** Welcome everyone.

				[00:01:05] **Remote:** The timeline needs the indicators.
				""");

		assertEquals(3, metadata.size(), metadata.toString());
		assertTrue(metadata.get(0).contains("2026"), metadata.toString());
		assertEquals("45 min 0 s", metadata.get(1));
		assertEquals("Me, Remote", metadata.get(2));
	}

	@Test
	@DisplayName("omits the speaker line when the transcript has no labels")
	void omitsSpeakersWithoutLabels() {
		MeetingMeta meta = new MeetingMeta("meeting", null, false, null, null, "Sync", null, null,
				null, null, null, null, null);

		assertTrue(MeetingNotesExport.metadata(meta, "[00:00:01] plain text").isEmpty());
	}

	@Test
	@DisplayName("trims the trailing colon and keeps first-seen speaker order")
	void parsesSpeakerLabels() {
		assertEquals(List.of("Maria", "Jardel"),
				List.copyOf(MeetingNotesExport.speakers("""
						[00:00:01] **Maria:** first
						[00:00:05] **Jardel:** second
						[00:00:09] **Maria:** again
						""")));
	}

	@Test
	@DisplayName("makes a file-safe name ending in the format's extension")
	void namesTheFile() {
		assertEquals("Weekly sync.docx",
				MeetingNotesExport.defaultFileName("Weekly sync", DocumentFormat.DOCX));
		assertEquals("AB.pdf",
				MeetingNotesExport.defaultFileName("A/B", DocumentFormat.PDF));
		assertEquals("Weekly sync.md",
				MeetingNotesExport.defaultFileName("Weekly sync", DocumentFormat.MARKDOWN));
	}

	@Test
	@DisplayName("names the file for a meeting whose title needs sanitising")
	void namesWithFallbackTitle() {
		assertEquals("Meeting.docx",
				MeetingNotesExport.defaultFileName("Meeting", DocumentFormat.DOCX));
	}

	@Test
	@DisplayName("maps a file name back to its format")
	void mapsExtensions() {
		assertEquals(java.util.Optional.of(DocumentFormat.DOCX),
				DocumentFormat.fromFileName("notes.DOCX"));
		assertEquals(java.util.Optional.of(DocumentFormat.PDF),
				DocumentFormat.fromFileName("a/b/notes.pdf"));
		assertEquals(java.util.Optional.empty(), DocumentFormat.fromFileName("notes.txt"));
	}

	@Test
	@DisplayName("defaults the header to nothing without metadata")
	void handlesMissingMeta() {
		MeetingMeta empty = new MeetingMeta(null, null, null, null, null, null, null, null, null,
				null, null, null, null);
		assertTrue(MeetingNotesExport.metadata(empty, "").isEmpty());
		assertTrue(MeetingNotesExport.speakers("").isEmpty());
	}

	@Test
	@DisplayName("an untitled meeting with a date is named once, not with the date twice")
	void namesAnUntitledMeetingOnce() {
		MeetingMeta meta = new MeetingMeta("meeting", "2026-11-12T12:00:00.000Z", false, "en",
				2_700_000L, null, null, null, null, null, null, null, null);
		var result = MeetingNotesExport.build(new MeetingService.FilesView(
				MeetingFiles.meetingFilesFor(java.nio.file.Path.of("/tmp/meeting.m4a")), null,
				NOTES, meta, false), new MeetingNotesExport.Request(DocumentFormat.DOCX, false),
				"Meeting");

		String title = assertInstanceOf(dev.gravaai.core.result.Result.Ok.class, result).value()
				instanceof PlainDocument document ? document.title() : "";
		assertTrue(title.startsWith("Meeting "), title);
		// The label carries the date exactly once.
		assertEquals(title.indexOf("Meeting"), title.lastIndexOf("Meeting"), title);
		assertEquals(MeetingTexts.meetingTitle(java.util.Optional.of(meta),
				java.util.OptionalLong.empty(), "Meeting"), title);
	}

	@Test
	@DisplayName("keeps a titled meeting's name as the export title")
	void usesTheMeetingTitle() {
		MeetingMeta meta = new MeetingMeta(null, null, null, null, null, "  Design review  ", null,
				null, null, null, null, null, null);

		assertEquals("Design review", MeetingTexts.meetingTitle(java.util.Optional.of(meta),
				java.util.OptionalLong.empty(), "Meeting"));
	}

	@Test
	@DisplayName("names an untitled meeting after its date")
	void fallsBackToTheDate() {
		MeetingMeta meta = new MeetingMeta(null, "2026-11-12T12:00:00.000Z", null, null, null, null,
				null, null, null, null, null, null, null);

		String title = MeetingTexts.meetingTitle(java.util.Optional.of(meta),
				java.util.OptionalLong.empty(), "Meeting");
		assertTrue(title.startsWith("Meeting "), title);
	}

	@Test
	@DisplayName("the format glob matches its extension")
	void exposesGlobs() {
		assertEquals("*.docx", DocumentFormat.DOCX.glob());
		assertEquals("docx", DocumentFormat.DOCX.extension());
	}

	@Test
	@DisplayName("builds notes only when the transcript is left out")
	void buildsNotesOnly() {
		PlainDocument document = built(files(NOTES, TRANSCRIPT),
				new MeetingNotesExport.Request(DocumentFormat.DOCX, false));

		String text = document.plainLines().toString();
		assertTrue(text.contains("Keep the compact cards"), text);
		assertTrue(!text.contains("Welcome everyone"), text);
		assertTrue(document.blocks().stream()
				.noneMatch(block -> block instanceof DocumentBlock.Heading heading
						&& heading.plainText().equals("Transcript")));
	}

	@Test
	@DisplayName("appends the transcript under its own heading when asked")
	void appendsTranscript() {
		PlainDocument document = built(files(NOTES, TRANSCRIPT),
				new MeetingNotesExport.Request(DocumentFormat.PDF, true));

		List<String> lines = document.plainLines();
		assertTrue(lines.toString().contains("Welcome everyone"), lines.toString());
		int transcriptHeading = -1;
		for (int index = 0; index < document.blocks().size(); index++) {
			DocumentBlock block = document.blocks().get(index);
			if (block instanceof DocumentBlock.Heading heading
					&& heading.plainText().equals("Transcript")) {
				transcriptHeading = index;
			}
		}
		assertTrue(transcriptHeading > 0, "no Transcript heading");
		// Notes come first, then the rule and the transcript section.
		assertInstanceOf(DocumentBlock.Rule.class, document.blocks().get(transcriptHeading - 1));
	}

	@Test
	@DisplayName("refuses an export with no notes and no transcript")
	void refusesEmptyMeeting() {
		var result = MeetingNotesExport.build(files(null, null),
				new MeetingNotesExport.Request(DocumentFormat.DOCX, false), "Meeting");

		assertTrue(result.isErr());
		assertTrue(result.errorMessage().contains("no notes or transcript"), result.errorMessage());
	}

	@Test
	@DisplayName("refuses the transcript appendix when the meeting has none")
	void refusesMissingTranscript() {
		var result = MeetingNotesExport.build(files(NOTES, null),
				new MeetingNotesExport.Request(DocumentFormat.DOCX, true), "Meeting");

		assertTrue(result.isErr());
		assertTrue(result.errorMessage().contains("no transcript"), result.errorMessage());
	}

	@Test
	@DisplayName("uses the meeting title and the transcript's speakers in the header")
	void buildsHeaderFromTheMeeting() {
		PlainDocument document = built(files(NOTES, TRANSCRIPT),
				new MeetingNotesExport.Request(DocumentFormat.DOCX, false));

		assertEquals("Design review", document.title());
		assertTrue(document.metadata().contains("Me, Remote"), document.metadata().toString());
	}

	private static PlainDocument built(MeetingService.FilesView files,
			MeetingNotesExport.Request request) {
		var result = MeetingNotesExport.build(files, request, "Meeting");
		assertTrue(result.isOk(), result.errorMessage());
		PlainDocument document = result.orNull();
		assertTrue(document != null);
		return document;
	}

	/** A files view over the given notes and transcript text (metadata: a titled meeting). */
	private static MeetingService.FilesView files(String notes, String transcript) {
		MeetingMeta meta = new MeetingMeta("meeting", "2026-11-12T12:00:00.000Z", false, "en",
				2_700_000L, "Design review", null, null, null, null, null, null, null);
		return new MeetingService.FilesView(MeetingFiles.meetingFilesFor(
				java.nio.file.Path.of("/tmp/meeting.m4a")), transcript, notes, meta, false);
	}

	private static final String NOTES = """
			## Decisions

			- Keep the compact cards
			- Ship the hover preview later
			""";

	private static final String TRANSCRIPT = """
			[00:00:12] **Me:** Welcome everyone, today we review the dashboard design.

			[00:01:05] **Remote:** The timeline needs the new indicators.
			""";
}
