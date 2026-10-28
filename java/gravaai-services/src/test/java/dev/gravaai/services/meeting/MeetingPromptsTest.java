package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from electron/ipc/meeting/prompts.test.ts. */
class MeetingPromptsTest {

	@Test
	@DisplayName("puts the language right before the transcript label")
	void languageInstructionBeforeTranscriptLabel() {
		String prompt = MeetingPrompts.withLanguageInstruction(MeetingPrompts.SUMMARY_PROMPT,
				"pt");
		int instruction = prompt.indexOf("Write your answer in Portuguese.");
		assertTrue(instruction > prompt.indexOf("## Open questions"));
		assertTrue(instruction < prompt.indexOf("TRANSCRIPT:"));
		assertTrue(prompt.endsWith("{transcript}\n"));
	}

	@Test
	@DisplayName("leaves the prompt alone for unknown languages")
	void languageInstructionUnknownLanguage() {
		assertEquals(MeetingPrompts.SUMMARY_PROMPT,
				MeetingPrompts.withLanguageInstruction(MeetingPrompts.SUMMARY_PROMPT, null));
		assertEquals(MeetingPrompts.SUMMARY_PROMPT,
				MeetingPrompts.withLanguageInstruction(MeetingPrompts.SUMMARY_PROMPT, "xx"));
	}

	@Test
	@DisplayName("appends to prompts without a placeholder")
	void languageInstructionAppends() {
		assertEquals("Summarize.\n\nWrite your answer in German.",
				MeetingPrompts.withLanguageInstruction("Summarize.", "de"));
	}

	@Test
	@DisplayName("removes placeholder sections and keeps real ones")
	void dropEmptySectionsRemovesPlaceholders() {
		String notes = String.join("\n",
				"## Summary",
				"We planned the release.",
				"",
				"## Decisions",
				"- None",
				"",
				"## Action items",
				"- [ ] Ana: send the budget",
				"",
				"## Open questions",
				"N/A",
				"",
				"## Key points",
				"Nenhum.");
		String cleaned = MeetingPrompts.dropEmptySections(notes);
		assertTrue(cleaned.contains("## Summary"));
		assertTrue(cleaned.contains("## Action items"));
		assertFalse(cleaned.contains("## Decisions"));
		assertFalse(cleaned.contains("## Open questions"));
		assertFalse(cleaned.contains("## Key points"));
	}

	@Test
	@DisplayName("keeps text before the first heading")
	void dropEmptySectionsKeepsPreamble() {
		assertEquals("# Notes\n\n## Summary\nDone.",
				MeetingPrompts.dropEmptySections("# Notes\n\n## Summary\nDone."));
	}

	@Test
	@DisplayName("removes the extra bullet small models put around checklist items")
	void normalizeChecklistsRemovesExtraBullet() {
		assertEquals("- [ ] **Ana:** send it\n- [x] done\n- [ ] fine\n* plain",
				MeetingPrompts.normalizeChecklists(
						"*   - [ ] **Ana:** send it\n* [x] done\n- [ ] fine\n* plain"));
	}

	@Test
	@DisplayName("keeps short transcripts whole")
	void chunkTranscriptShort() {
		assertEquals(List.of("a\nb"), MeetingPrompts.chunkTranscript("a\nb", 100));
	}

	@Test
	@DisplayName("splits only between lines and respects the limit")
	void chunkTranscriptSplitsBetweenLines() {
		List<String> lines = new ArrayList<>();
		for (int index = 0; index < 50; index++) {
			lines.add("[00:00:" + index + "] line " + index);
		}
		List<String> chunks = MeetingPrompts.chunkTranscript(String.join("\n", lines), 120);
		assertTrue(chunks.size() > 1);
		for (String chunk : chunks) {
			assertTrue(chunk.length() <= 120, "chunk too long: " + chunk.length());
		}
		assertEquals(lines, List.of(String.join("\n", chunks).split("\n")));
	}

	@Test
	@DisplayName("cleans model title answers")
	void cleanTitleAnswers() {
		assertEquals("Planejamento do Q3", MeetingPrompts.cleanTitle("Title: \"Planejamento do Q3\"."));
		assertEquals("Release review",
				MeetingPrompts.cleanTitle("<think hmm</think\n# Release review\nextra"));
	}

	@Test
	@DisplayName("renders values and appends content to templates without a placeholder")
	void renderPromptValues() {
		assertEquals("Part 2: x", MeetingPrompts.renderPrompt("Part {part}: {transcript}",
				Map.of("part", 2, "transcript", "x")));
		assertEquals("Title this.\n\nnotes",
				MeetingPrompts.renderPrompt("Title this.", Map.of("transcript", "notes")));
	}

	@Test
	@DisplayName("falls back to the default prompt when the custom one is blank")
	void effectivePromptFallback() {
		assertEquals("default", MeetingPrompts.effectivePrompt("  ", "default"));
		assertEquals("mine", MeetingPrompts.effectivePrompt("mine", "default"));
	}

	@Test
	@DisplayName("strips reasoning blocks")
	void stripThinkingBlocks() {
		assertEquals("Answer", MeetingPrompts.stripThinking("<think a\nb</think\n\nAnswer"));
	}

	@Test
	@DisplayName("maps whisper-1 language names to codes")
	void languageCodeFromNameMapping() {
		assertEquals("pt", MeetingPrompts.languageCodeFromName("portuguese"));
		assertEquals("en", MeetingPrompts.languageCodeFromName("en"));
		assertNull(MeetingPrompts.languageCodeFromName("klingon"));
	}
}
