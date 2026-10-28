package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 5.14: the prompts are copied verbatim from prompts.ts into resource text files, and this
 * test compares them byte-for-byte until Electron is deleted (Phase 3).
 */
class PromptsMatchTsTest {

	private static final char BACKTICK = (char) 96;

	@Test
	@DisplayName("prompt resources are byte-identical to electron/ipc/meeting/prompts.ts")
	void promptsMatchTypeScriptSource() throws IOException {
		Path source = typeScriptSource();
		Assumptions.assumeTrue(source != null,
				"electron/ is gone (Phase 3); nothing left to compare against");
		String typescript = Files.readString(source, StandardCharsets.UTF_8);

		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("summary.txt", templateLiteral(typescript, "SUMMARY_PROMPT"));
		expected.put("partial-summary.txt",
				templateLiteral(typescript, "PARTIAL_SUMMARY_PROMPT"));
		expected.put("merge-summary.txt", templateLiteral(typescript, "MERGE_SUMMARY_PROMPT"));
		expected.put("title.txt", templateLiteral(typescript, "TITLE_PROMPT"));

		assertArrayEquals(expected.get("summary.txt").getBytes(StandardCharsets.UTF_8),
				MeetingPrompts.SUMMARY_PROMPT.getBytes(StandardCharsets.UTF_8),
				"summary.txt differs from SUMMARY_PROMPT");
		assertArrayEquals(expected.get("partial-summary.txt").getBytes(StandardCharsets.UTF_8),
				MeetingPrompts.PARTIAL_SUMMARY_PROMPT.getBytes(StandardCharsets.UTF_8),
				"partial-summary.txt differs from PARTIAL_SUMMARY_PROMPT");
		assertArrayEquals(expected.get("merge-summary.txt").getBytes(StandardCharsets.UTF_8),
				MeetingPrompts.MERGE_SUMMARY_PROMPT.getBytes(StandardCharsets.UTF_8),
				"merge-summary.txt differs from MERGE_SUMMARY_PROMPT");
		assertArrayEquals(expected.get("title.txt").getBytes(StandardCharsets.UTF_8),
				MeetingPrompts.TITLE_PROMPT.getBytes(StandardCharsets.UTF_8),
				"title.txt differs from TITLE_PROMPT");
	}

	private static Path typeScriptSource() {
		Path directory = Path.of("").toAbsolutePath();
		for (int depth = 0; depth < 6 && directory != null; depth++) {
			Path candidate = directory.resolve(Path.of("electron", "ipc", "meeting", "prompts.ts"));
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
			directory = directory.getParent();
		}
		return null;
	}

	/** Reads one exported template literal, un-escaping backticks. */
	private static String templateLiteral(String source, String name) {
		String prefix = "export const " + name + " = " + BACKTICK;
		int start = source.indexOf(prefix);
		assertTrue(start >= 0, "prompts.ts does not define " + name);
		int index = start + prefix.length();
		StringBuilder value = new StringBuilder();
		while (index < source.length()) {
			char current = source.charAt(index);
			if (current == '\\' && index + 1 < source.length()
					&& source.charAt(index + 1) == BACKTICK) {
				value.append(BACKTICK);
				index += 2;
				continue;
			}
			if (current == BACKTICK) {
				return value.toString();
			}
			value.append(current);
			index++;
		}
		throw new AssertionError("unterminated template literal " + name);
	}
}
