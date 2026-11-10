package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The meeting-notes templates: the built-in prompt files ({@code General} is the classic
 * {@code summary.txt}, the rest live next to it in {@code meeting/prompts/}) plus the user's own
 * {@code .txt} templates dropped into {@code <profile>/meeting/templates/}.
 */
public final class NoteTemplates {

	/** The id of the built-in General template (the classic summary prompt). */
	public static final String GENERAL_ID = "general";

	/** The pseudo-template id recorded when the custom notes prompt won over every template. */
	public static final String CUSTOM_ID = "custom";

	/** Prefix of a user template id ({@code user:standup-notes.txt}). */
	private static final String USER_ID_PREFIX = "user:";

	private static final String USER_EXTENSION = ".txt";

	private static final List<Template> BUILT_INS = List.of(
			new Template(GENERAL_ID, "General", MeetingPrompts.SUMMARY_PROMPT),
			new Template("standup", "Standup", resource("standup.txt")),
			new Template("one-on-one", "1:1", resource("one-on-one.txt")),
			new Template("interview", "Interview", resource("interview.txt")),
			new Template("sales-call", "Sales call", resource("sales-call.txt")),
			new Template("retro", "Retro", resource("retro.txt")));

	private NoteTemplates() {
	}

	/** One notes template: a stable id, a display label and the prompt text. */
	public record Template(String id, String label, String prompt) {
	}

	/** The built-in templates in picker order. */
	public static List<Template> builtIns() {
		return BUILT_INS;
	}

	/** {@code <profile>/meeting/templates}, where the user drops their own {@code .txt} files. */
	public static Path userTemplatesDir(Path userDataDir) {
		return userDataDir.resolve("meeting").resolve("templates");
	}

	/**
	 * The user's templates from the templates folder, file-name order. Unreadable files are
	 * skipped; a missing folder is simply no user templates.
	 */
	public static List<Template> userTemplates(Path userTemplatesDir) {
		if (!Files.isDirectory(userTemplatesDir)) {
			return List.of();
		}
		List<Template> templates = new ArrayList<>();
		try (Stream<Path> entries = Files.list(userTemplatesDir)) {
			entries.filter(Files::isRegularFile)
					.filter(file -> file.getFileName() != null
							&& file.getFileName().toString().toLowerCase(Locale.ROOT)
									.endsWith(USER_EXTENSION))
					.sorted()
					.forEach(file -> fileTemplate(file).ifPresent(templates::add));
		} catch (IOException | UncheckedIOException error) {
			System.getLogger("gravaai.meeting").log(System.Logger.Level.WARNING,
					"Could not list the meeting templates in " + userTemplatesDir, error);
		}
		return List.copyOf(templates);
	}

	private static java.util.Optional<Template> fileTemplate(Path file) {
		String fileName = file.getFileName().toString();
		String label = fileName.substring(0, fileName.length() - USER_EXTENSION.length()).strip();
		if (label.isEmpty()) {
			return java.util.Optional.empty();
		}
		try {
			return java.util.Optional.of(new Template(USER_ID_PREFIX + fileName, label,
					Files.readString(file, StandardCharsets.UTF_8)));
		} catch (IOException | UncheckedIOException error) {
			System.getLogger("gravaai.meeting").log(System.Logger.Level.WARNING,
					"Could not read the meeting template " + file, error);
			return java.util.Optional.empty();
		}
	}

	/** Built-ins followed by the user's templates: what the pickers show. */
	public static List<Template> all(Path userTemplatesDir) {
		List<Template> templates = new ArrayList<>(BUILT_INS);
		templates.addAll(userTemplates(userTemplatesDir));
		return List.copyOf(templates);
	}

	/**
	 * @param id a built-in id, a {@code user:<file>} id, or anything else (unknown stored ids
	 *     included)
	 * @return the template, or null when the id matches none
	 */
	public static @Nullable Template resolve(String id, Path userTemplatesDir) {
		for (Template template : BUILT_INS) {
			if (template.id().equals(id)) {
				return template;
			}
		}
		if (id.startsWith(USER_ID_PREFIX)) {
			String fileName = id.substring(USER_ID_PREFIX.length());
			Path file = userTemplatesDir.resolve(fileName).normalize();
			// "user:" ids name a file directly inside the templates folder; reject escapes.
			Path parent = file.getParent();
			if (parent == null || !parent.equals(userTemplatesDir.toAbsolutePath().normalize())) {
				return null;
			}
			return fileTemplate(file).orElse(null);
		}
		return null;
	}

	/**
	 * The template a job runs with: the explicit per-meeting choice, then the custom notes prompt,
	 * then the settings default, then General.
	 *
	 * @param templateId the per-meeting template id, or null when the job carries no choice
	 * @param customPrompt the custom notes prompt from the settings, or null/blank when unset
	 * @param defaultId the default template id from the settings, or null when unset
	 */
	public static Template effective(@Nullable String templateId, @Nullable String customPrompt,
			@Nullable String defaultId, Path userTemplatesDir) {
		if (templateId != null && !templateId.isBlank()) {
			Template chosen = resolve(templateId, userTemplatesDir);
			if (chosen != null) {
				return chosen;
			}
		}
		if (customPrompt != null && !customPrompt.strip().isEmpty()) {
			return new Template(CUSTOM_ID, "Custom", customPrompt);
		}
		if (defaultId != null && !defaultId.isBlank()) {
			Template fallback = resolve(defaultId, userTemplatesDir);
			if (fallback != null) {
				return fallback;
			}
		}
		return BUILT_INS.get(0);
	}

	/**
	 * The long-meeting merge prompt for a template: General keeps the byte-exact built-in merge
	 * prompt; every other template has its sections carried over, so the merged notes still follow
	 * the template. The template's intro and section block are everything before its
	 * {@code TRANSCRIPT:} trailer.
	 */
	public static String mergePromptFor(Template template) {
		if (GENERAL_ID.equals(template.id())) {
			return MeetingPrompts.MERGE_SUMMARY_PROMPT;
		}
		String head = template.prompt();
		int trailer = head.lastIndexOf("\nTRANSCRIPT:");
		if (trailer >= 0) {
			head = head.substring(0, trailer);
		}
		return mergeIntro() + "\n\n" + head.strip() + "\n\nPARTIAL NOTES:\n{transcript}";
	}

	/** The first paragraph of the built-in merge prompt, so both merge prompts stay in step. */
	private static String mergeIntro() {
		String merge = MeetingPrompts.MERGE_SUMMARY_PROMPT;
		int paragraphEnd = merge.indexOf("\n\n");
		return paragraphEnd >= 0 ? merge.substring(0, paragraphEnd) : merge;
	}

	private static String resource(String name) {
		try (InputStream stream = NoteTemplates.class
				.getResourceAsStream("/meeting/prompts/" + name)) {
			if (stream == null) {
				throw new IllegalStateException("Missing meeting template resource: " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}
}
