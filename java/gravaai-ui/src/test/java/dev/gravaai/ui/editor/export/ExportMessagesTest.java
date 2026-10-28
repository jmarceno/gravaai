package dev.gravaai.ui.editor.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code useExportMessages.test.ts}. */
class ExportMessagesTest {

	private static ExportMessages.Message message(String id) {
		return new ExportMessages.Message(id, id, 6);
	}

	@Test
	@DisplayName("alternates built-in tips with streamed announcements")
	void alternatesTipsAndAnnouncements() {
		List<String> ids = ExportMessages.buildExportMessageStream(
				List.of(message("tip-1"), message("tip-2"), message("tip-3")),
				List.of(message("announcement-1"), message("announcement-2"))).stream()
						.map(ExportMessages.Message::id).toList();
		assertEquals(List.of("tip-1", "announcement-1", "tip-2", "announcement-2", "tip-3"), ids);
	}

	@Test
	@DisplayName("uses only built-in tips when the announcement feed is empty")
	void usesOnlyTips() {
		List<String> ids = ExportMessages.buildExportMessageStream(
				List.of(message("tip-1"), message("tip-2")), List.of()).stream()
						.map(ExportMessages.Message::id).toList();
		assertEquals(List.of("tip-1", "tip-2"), ids);
	}

	@Test
	@DisplayName("compacts whitespace and clamps long messages with an ellipsis")
	void compactsMessages() {
		assertEquals("Tip: one two", ExportMessages.compactMessage("  Tip:  one\n two  "));
		String longMessage = "x".repeat(ExportMessages.MAX_EXPORT_MESSAGE_LENGTH + 40);
		String compacted = ExportMessages.compactMessage(longMessage);
		assertEquals(ExportMessages.MAX_EXPORT_MESSAGE_LENGTH, compacted.length());
		assertTrue(compacted.endsWith("…"));
	}

	@Test
	@DisplayName("the built-in tips are the two editor tips")
	void defaultTips() {
		assertEquals(List.of("tip:auto-zooms", "tip:cursor-appearance"),
				ExportMessages.defaultTips().stream().map(ExportMessages.Message::id).toList());
	}
}
