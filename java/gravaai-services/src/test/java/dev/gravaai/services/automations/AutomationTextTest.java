package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Port of {@code src/lib/automationTemplate.test.ts}.
 */
class AutomationTextTest {

	private static Instant at(int year, int month, int day, int hour, int minute, int second) {
		return LocalDateTime.of(year, month, day, hour, minute, second)
				.atZone(ZoneId.systemDefault())
				.toInstant();
	}

	private static AutomationContext context() {
		return new AutomationContext(
				AutomationTrigger.EXPORT_FINISHED,
				"/home/me/Videos/demo.final.mp4",
				null,
				AutomationExportFormat.MP4,
				null,
				null,
				at(2026, 10, 24, 13, 45, 6));
	}

	private static AutomationContext context(AutomationMediaKind kind, String notesPath) {
		return new AutomationContext(
				AutomationTrigger.EXPORT_FINISHED,
				"/home/me/Videos/demo.final.mp4",
				kind,
				AutomationExportFormat.MP4,
				notesPath,
				null,
				at(2026, 10, 24, 13, 45, 6));
	}

	@Test
	@DisplayName("splitAutomationPath splits posix paths and keeps dots inside names")
	void splitsPosixPaths() {
		assertEquals(
				new AutomationText.SplitPath("/home/me/Videos", "demo.final.mp4", "demo.final", "mp4"),
				AutomationText.splitAutomationPath("/home/me/Videos/demo.final.mp4"));
	}

	@Test
	@DisplayName("splitAutomationPath splits windows paths and lowercases the extension")
	void splitsWindowsPaths() {
		assertEquals(
				new AutomationText.SplitPath("C:\\Users\\me", "Clip.MP4", "Clip", "mp4"),
				AutomationText.splitAutomationPath("C:\\Users\\me\\Clip.MP4"));
	}

	@Test
	@DisplayName("splitAutomationPath handles dotfiles and bare names")
	void splitsDotfilesAndBareNames() {
		assertEquals(
				new AutomationText.SplitPath(".", ".hidden", ".hidden", ""),
				AutomationText.splitAutomationPath(".hidden"));
		assertEquals(
				new AutomationText.SplitPath(".", "clip", "clip", ""),
				AutomationText.splitAutomationPath("clip"));
	}

	@Test
	@DisplayName("expandAutomationTemplate expands every placeholder")
	void expandsEveryPlaceholder() {
		String result = AutomationText.expandAutomationTemplate(
				"{path}|{dir}|{name}|{ext}|{date}|{time}|{kind}|{format}|{notesPath}",
				context(AutomationMediaKind.MEETING, "/tmp/meeting.notes.md"));
		assertEquals(
				"/home/me/Videos/demo.final.mp4|/home/me/Videos|demo.final|mp4|2026-10-24|13-45-06|meeting|mp4|/tmp/meeting.notes.md",
				result);
	}

	@Test
	@DisplayName("expandAutomationTemplate leaves unknown placeholders as-is")
	void leavesUnknownPlaceholders() {
		assertEquals(
				"demo.final-{unknown}",
				AutomationText.expandAutomationTemplate("{name}-{unknown}", context()));
	}

	@Test
	@DisplayName("expandAutomationTemplate expands known placeholders without a value to an empty string")
	void expandsMissingValuesToEmpty() {
		assertEquals("[]", AutomationText.expandAutomationTemplate("[{notesPath}]", context()));
	}

	@Test
	@DisplayName("expandAutomationTemplate keeps empty templates empty")
	void keepsEmptyTemplates() {
		assertEquals("", AutomationText.expandAutomationTemplate("", context()));
	}

	@Test
	@DisplayName("formatAutomationDate and formatAutomationTime format local date and time")
	void formatsDateAndTime() {
		Instant instant = at(2026, 1, 3, 4, 5, 6);
		assertEquals("2026-01-03", AutomationText.formatAutomationDate(instant));
		assertEquals("04-05-06", AutomationText.formatAutomationTime(instant));
	}

	@Test
	@DisplayName("sanitizeFileName removes separators and invalid characters")
	void removesInvalidCharacters() {
		assertEquals("abcdefghij", AutomationText.sanitizeFileName("a/b\\c:d*e?f\"g<h>i|j"));
	}

	@Test
	@DisplayName("sanitizeFileName removes control characters and trims")
	void removesControlCharacters() {
		assertEquals("clip name", AutomationText.sanitizeFileName("  clip\u0000\u0007 name  "));
	}

	@Test
	@DisplayName("sanitizeFileName drops trailing dots and spaces")
	void dropsTrailingDotsAndSpaces() {
		assertEquals("name", AutomationText.sanitizeFileName("name... "));
	}

	@Test
	@DisplayName("sanitizeFileName falls back for empty, dot and dot-dot names")
	void fallsBackForEmptyNames() {
		assertEquals("file", AutomationText.sanitizeFileName(""));
		assertEquals("file", AutomationText.sanitizeFileName("   "));
		assertEquals("file", AutomationText.sanitizeFileName(".."));
		assertEquals("file", AutomationText.sanitizeFileName("///"));
	}

	@Test
	@DisplayName("sanitizeFileName limits the length")
	void limitsLength() {
		assertEquals(200, AutomationText.sanitizeFileName("x".repeat(500)).length());
	}

	@Test
	@DisplayName("expandHomeDirectory expands only a leading tilde")
	void expandsHomeDirectory() {
		java.nio.file.Path home = java.nio.file.Path.of("/home/me");
		assertEquals("/home/me", AutomationText.expandHomeDirectory("~", home));
		assertEquals("/home/me/Videos", AutomationText.expandHomeDirectory("~/Videos", home));
		assertEquals("/home/me/Videos", AutomationText.expandHomeDirectory("~\\Videos", home));
		assertEquals("/tmp/~/x", AutomationText.expandHomeDirectory("/tmp/~/x", home));
	}

	@Test
	@DisplayName("AUTOMATION_PLACEHOLDERS lists the documented placeholders")
	void placeholdersList() {
		assertEquals(
				List.of("{path}", "{dir}", "{name}", "{ext}", "{date}", "{time}", "{kind}", "{format}", "{notesPath}"),
				Automations.AUTOMATION_PLACEHOLDERS);
	}
}
