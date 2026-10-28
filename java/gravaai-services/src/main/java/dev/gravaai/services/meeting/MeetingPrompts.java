package dev.gravaai.services.meeting;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Meeting-notes prompts and the text clean-up around them, ported 1:1 from
 * {@code electron/ipc/meeting/prompts.ts}. The prompts themselves live in
 * {@code src/main/resources/meeting/prompts/*.txt} so a test can compare them byte-for-byte
 * with the TypeScript source until Electron is deleted (plan §5.14).
 */
public final class MeetingPrompts {

	public static final String SUMMARY_PROMPT = loadResource("summary.txt");
	public static final String PARTIAL_SUMMARY_PROMPT = loadResource("partial-summary.txt");
	public static final String MERGE_SUMMARY_PROMPT = loadResource("merge-summary.txt");
	public static final String TITLE_PROMPT = loadResource("title.txt");

	private static final Map<String, String> LANGUAGE_NAMES = languageNames();

	private static final Pattern PLACEHOLDER = Pattern.compile(
			"(none|n/a|na|nothing|no \\w+( \\w+)?|nenhum[a]?|nada|sem \\w+|ninguno|ninguna|aucun[e]?)\\z");

	private MeetingPrompts() {
	}

	private static String loadResource(String name) {
		try (InputStream stream = MeetingPrompts.class
				.getResourceAsStream("/meeting/prompts/" + name)) {
			if (stream == null) {
				throw new IllegalStateException("Missing meeting prompt resource: " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}

	private static Map<String, String> languageNames() {
		String[][] entries = {
				{"ar", "Arabic"}, {"ca", "Catalan"}, {"cs", "Czech"}, {"da", "Danish"},
				{"de", "German"}, {"el", "Greek"}, {"en", "English"}, {"es", "Spanish"},
				{"fi", "Finnish"}, {"fr", "French"}, {"he", "Hebrew"}, {"hi", "Hindi"},
				{"hu", "Hungarian"}, {"id", "Indonesian"}, {"it", "Italian"}, {"ja", "Japanese"},
				{"ko", "Korean"}, {"nl", "Dutch"}, {"no", "Norwegian"}, {"pl", "Polish"},
				{"pt", "Portuguese"}, {"ro", "Romanian"}, {"ru", "Russian"}, {"sk", "Slovak"},
				{"sv", "Swedish"}, {"th", "Thai"}, {"tr", "Turkish"}, {"uk", "Ukrainian"},
				{"vi", "Vietnamese"}, {"zh", "Chinese"},
		};
		Map<String, String> names = new LinkedHashMap<>();
		for (String[] entry : entries) {
			names.put(entry[0], entry[1]);
		}
		return Collections.unmodifiableMap(names);
	}

	public static String languageName(String code) {
		if (code == null || code.isEmpty()) {
			return null;
		}
		return LANGUAGE_NAMES.get(code.toLowerCase(Locale.ROOT));
	}

	/** whisper-1 reports the language as a name ("portuguese"); map it back to a code. */
	public static String languageCodeFromName(String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		String lowered = name.strip().toLowerCase(Locale.ROOT);
		if (LANGUAGE_NAMES.containsKey(lowered)) {
			return lowered;
		}
		for (Map.Entry<String, String> entry : LANGUAGE_NAMES.entrySet()) {
			if (entry.getValue().toLowerCase(Locale.ROOT).equals(lowered)) {
				return entry.getKey();
			}
		}
		return null;
	}

	/** A custom prompt, or the built-in default when the custom one is blank. */
	public static String effectivePrompt(String custom, String fallback) {
		return custom != null && !custom.strip().isEmpty() ? custom : fallback;
	}

	/**
	 * Small models drift into English when the prompt is English, so the detected language is
	 * restated as the last instruction before the content.
	 */
	public static String withLanguageInstruction(String template, String language) {
		String name = languageName(language);
		if (name == null) {
			return template;
		}
		String instruction = "Write your answer in " + name + ".";
		int marker = template.lastIndexOf("{transcript}");
		if (marker < 0) {
			return template + "\n\n" + instruction;
		}
		String head = template.substring(0, marker);
		int labelStart = head.lastIndexOf("\n\n");
		int insertAt = labelStart >= 0 ? labelStart : marker;
		return template.substring(0, insertAt) + "\n\n" + instruction + template.substring(insertAt);
	}

	public static String renderPrompt(String template, Map<String, ?> values) {
		String rendered = template;
		for (Map.Entry<String, ?> entry : values.entrySet()) {
			rendered = rendered.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
		}
		if (!template.contains("{transcript}") && values.containsKey("transcript")) {
			rendered = rendered + "\n\n" + values.get("transcript");
		}
		return rendered;
	}

	/** Drops sections a model filled with a placeholder instead of leaving them out. */
	public static String dropEmptySections(String notes) {
		String[] parts = notes.split("(?m)(?=^##\\s)");
		StringBuilder kept = new StringBuilder();
		for (String part : parts) {
			if (!part.startsWith("##")) {
				kept.append(part);
				continue;
			}
			String body = part.replaceFirst("##[^\\n]*\\n?", "");
			if (!isPlaceholder(body)) {
				kept.append(part);
			}
		}
		return kept.toString().strip();
	}

	private static boolean isPlaceholder(String body) {
		String text = body
				.replaceAll("(?m)^\\s*(?:[>*_-]+\\s*)*(?:\\[[ xX]\\]\\s*)?", "")
				.replaceAll("[.*_\\s]+\\z", "")
				.strip()
				.toLowerCase(Locale.ROOT);
		return text.isEmpty() || PLACEHOLDER.matcher(text).matches();
	}

	/** Small models wrap checklist items in a second bullet ("*   - [ ] ..."). */
	public static String normalizeChecklists(String notes) {
		return notes
				.replaceAll("(?m)^(\\s*)[*-]\\s+(?=[*-] \\[[ xX]\\])", "$1")
				.replaceAll("(?m)^(\\s*)\\* (\\[[ xX]\\])", "$1- $2");
	}

	/** Clean-up applied to every model answer before it is saved as notes. */
	public static String tidyNotes(String notes) {
		return dropEmptySections(normalizeChecklists(notes));
	}

	/** Strips a reasoning block some models emit before the answer. */
	public static String stripThinking(String text) {
		String result = text;
		while (true) {
			int start = indexOfIgnoreCase(result, "<think");
			if (start < 0) {
				break;
			}
			int closeStart = indexOfIgnoreCase(result, "</think", start + 1);
			if (closeStart < 0) {
				break;
			}
		int close = closeStart + "</think".length();
			int scan = close;
			while (scan < result.length() && Character.isWhitespace(result.charAt(scan))) {
				scan++;
			}
			if (scan < result.length() && result.charAt(scan) == '>') {
				close = scan + 1;
			}
			result = result.substring(0, start) + result.substring(close);
		}
		return result.strip();
	}

	/** Case-insensitive {@code indexOf} (the transcript helpers avoid regex for literal tags). */
	private static int indexOfIgnoreCase(String haystack, String needle) {
		int max = haystack.length() - needle.length();
		for (int index = 0; index <= max; index++) {
			if (haystack.regionMatches(true, index, needle, 0, needle.length())) {
				return index;
			}
		}
		return -1;
	}

	/** Case-insensitive {@code indexOf} starting at {@code fromIndex}. */
	private static int indexOfIgnoreCase(String haystack, String needle, int fromIndex) {
		int max = haystack.length() - needle.length();
		for (int index = Math.max(0, fromIndex); index <= max; index++) {
			if (haystack.regionMatches(true, index, needle, 0, needle.length())) {
				return index;
			}
		}
		return -1;
	}

	public static String cleanTitle(String raw) {
		String firstLine = "";
		for (String line : stripThinking(raw).split("\n", -1)) {
			String trimmed = line.strip();
			if (!trimmed.isEmpty()) {
				firstLine = trimmed;
				break;
			}
		}
		String cleaned = firstLine
				.replaceAll("^#+\\s*", "")
				.replaceAll("(?i)^(title|título|titulo)\\s*:\\s*", "")
				.replaceAll("^[\"'*\u0060]+|[\"'*\u0060.]+\\z", "")
				.strip();
		return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
	}

	/**
	 * Splits a transcript into chunks of at most {@code maxChars}, breaking only between lines so
	 * a speaker turn is never cut in half.
	 */
	public static List<String> chunkTranscript(String transcript, int maxChars) {
		if (transcript.length() <= maxChars) {
			return List.of(transcript);
		}
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String line : transcript.split("\n", -1)) {
			if (current.length() > 0 && current.length() + line.length() + 1 > maxChars) {
				chunks.add(current.toString().strip());
				current.setLength(0);
			}
			current.append(line).append('\n');
		}
		if (!current.toString().strip().isEmpty()) {
			chunks.add(current.toString().strip());
		}
		return List.copyOf(chunks);
	}
}
