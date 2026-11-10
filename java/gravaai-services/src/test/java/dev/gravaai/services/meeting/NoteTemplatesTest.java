package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The built-in notes templates and the user's template files. */
class NoteTemplatesTest {

	@TempDir
	Path temp;

	@Test
	@DisplayName("ships the six built-in templates in picker order")
	void builtInsInPickerOrder() {
		List<NoteTemplates.Template> builtIns = NoteTemplates.builtIns();
		assertEquals(List.of("general", "standup", "one-on-one", "interview", "sales-call", "retro"),
				builtIns.stream().map(NoteTemplates.Template::id).toList());
		assertEquals(List.of("General", "Standup", "1:1", "Interview", "Sales call", "Retro"),
				builtIns.stream().map(NoteTemplates.Template::label).toList());
		for (NoteTemplates.Template template : builtIns) {
			assertFalse(template.prompt().isBlank(), template.id() + " has no prompt");
			assertTrue(template.prompt().contains("{transcript}"),
					template.id() + " must take the transcript");
		}
		assertEquals(MeetingPrompts.SUMMARY_PROMPT,
				NoteTemplates.resolve(NoteTemplates.GENERAL_ID, temp).prompt());
	}

	@Test
	@DisplayName("loads the user's .txt files in file-name order and skips the rest")
	void loadsUserTemplates() throws IOException {
		Path dir = NoteTemplates.userTemplatesDir(temp);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("weekly-review.txt"), "Review week {transcript}");
		Files.writeString(dir.resolve("all-hands.txt"), "All hands notes");
		Files.writeString(dir.resolve("notes.md"), "not a template");
		Files.createDirectories(dir.resolve("folder.txt"));

		List<NoteTemplates.Template> user = NoteTemplates.userTemplates(dir);
		assertEquals(2, user.size());
		assertEquals("user:all-hands.txt", user.get(0).id());
		assertEquals("all-hands", user.get(0).label());
		assertEquals("All hands notes", user.get(0).prompt());
		assertEquals("user:weekly-review.txt", user.get(1).id());

		List<NoteTemplates.Template> all = NoteTemplates.all(dir);
		assertEquals(NoteTemplates.builtIns().size() + 2, all.size());
	}

	@Test
	@DisplayName("a missing templates folder is simply no user templates")
	void missingFolderIsEmpty() {
		assertTrue(NoteTemplates.userTemplates(temp.resolve("nope")).isEmpty());
	}

	@Test
	@DisplayName("resolves built-ins, user files and rejects escapes")
	void resolvesById() throws IOException {
		Path dir = NoteTemplates.userTemplatesDir(temp);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("my-template.txt"), "Hello");

		assertEquals("Standup", NoteTemplates.resolve("standup", dir).label());
		assertEquals("Hello", NoteTemplates.resolve("user:my-template.txt", dir).prompt());
		assertNull(NoteTemplates.resolve("user:missing.txt", dir));
		assertNull(NoteTemplates.resolve("custom", dir));
		assertNull(NoteTemplates.resolve("user:../settings.json", dir));
	}

	@Test
	@DisplayName("the effective template is explicit, then custom prompt, then default, then General")
	void effectivePrecedence() throws IOException {
		Path dir = NoteTemplates.userTemplatesDir(temp);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("team.txt"), "Team notes");

		assertEquals("standup", NoteTemplates.effective("standup", "custom text", "retro", dir)
				.id());
		// An explicit id that matches nothing falls through instead of failing the job.
		assertEquals("custom", NoteTemplates.effective("user:gone.txt", "custom text", null, dir)
				.id());
		assertEquals("custom text",
				NoteTemplates.effective(null, "custom text", "standup", dir).prompt());
		assertEquals("standup", NoteTemplates.effective(null, "  ", "standup", dir).id());
		assertEquals("user:team.txt",
				NoteTemplates.effective(null, null, "user:team.txt", dir).id());
		assertEquals(NoteTemplates.GENERAL_ID,
				NoteTemplates.effective(null, null, "user:gone.txt", dir).id());
		assertEquals(NoteTemplates.GENERAL_ID, NoteTemplates.effective(null, null, null, dir).id());
	}

	@Test
	@DisplayName("General merges with the built-in prompt; other templates carry their sections over")
	void mergePromptFollowsTheTemplate() {
		assertEquals(MeetingPrompts.MERGE_SUMMARY_PROMPT, NoteTemplates.mergePromptFor(
				NoteTemplates.resolve(NoteTemplates.GENERAL_ID, temp)));

		String standupMerge = NoteTemplates.mergePromptFor(NoteTemplates.resolve("standup", temp));
		assertTrue(standupMerge.startsWith(
				"You are an experienced executive assistant. Below are notes"));
		assertTrue(standupMerge.contains("## Blockers"), "the template's sections must survive");
		assertTrue(standupMerge.contains("PARTIAL NOTES:\n{transcript}"));
		assertFalse(standupMerge.contains("TRANSCRIPT:\n{transcript}"));
	}
}
