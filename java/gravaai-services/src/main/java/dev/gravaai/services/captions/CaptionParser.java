package dev.gravaai.services.captions;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Port of electron/ipc/captions/parser.ts: whisper JSON/SRT cue parsing. */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class CaptionParser {

	private static final System.Logger LOG =
			System.getLogger(CaptionParser.class.getName());

	private static final Pattern WHISPER_MARKER = Pattern.compile("^\\[_\\[[^\\]]+\\]\\]$");
	private static final Pattern PARTS = Pattern.compile("\\s+|\\S+");
	private static final Pattern WHITESPACE = Pattern.compile("^\\s+$");
	private static final Pattern PUNCTUATION = Pattern.compile("^\\p{P}+$");
	private static final Pattern SRT_TIMESTAMP =
			Pattern.compile("^(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})$");
	private static final Pattern RETRY_WITHOUT_JSON = Pattern.compile(
			"(?:unknown|unrecognized|unsupported) (?:argument|option)[:\\s=]+['\"]?--?(?:ojf|output-json-full)\\b",
			Pattern.CASE_INSENSITIVE);

	private CaptionParser() {
	}

	/** {@code buildCaptionTextFromWords}: join words, honouring leading spaces. */
	public static String buildCaptionTextFromWords(List<CaptionWord> words) {
		StringBuilder text = new StringBuilder();
		for (int index = 0; index < words.size(); index++) {
			CaptionWord word = words.get(index);
			if (index > 0 && word.leadingSpace()) {
				text.append(' ');
			}
			text.append(word.text());
		}
		return text.toString().trim();
	}

	private static JsonMapper mapper() {
		return JsonMapper.shared();
	}

	/** {@code parseWhisperJsonWords}: token stream to timed words. */
	public static @Nullable List<CaptionWord> parseWhisperJsonWords(JsonNode tokens) {
		if (tokens == null || !tokens.isArray()) {
			return List.of();
		}
		List<CaptionWord> words = new ArrayList<>();
		boolean nextLeadingSpace = false;
		for (JsonNode token : tokens) {
			if (token == null || !token.isObject()) {
				continue;
			}
			String tokenText = token.path("text").isString() ? token.path("text").asString() : "";
			if (tokenText.isEmpty() || WHISPER_MARKER.matcher(tokenText).matches()) {
				continue;
			}
			Long tokenStartMs = finiteLong(token.path("offsets").path("from"));
			Long tokenEndMs = finiteLong(token.path("offsets").path("to"));
			java.util.regex.Matcher parts = PARTS.matcher(tokenText);
			while (parts.find()) {
				String part = parts.group();
				if (WHITESPACE.matcher(part).matches()) {
					nextLeadingSpace = !words.isEmpty();
					continue;
				}
				CaptionWord previousWord = words.isEmpty() ? null : words.get(words.size() - 1);
				if (previousWord != null && !nextLeadingSpace && PUNCTUATION.matcher(part).matches()) {
					words.set(words.size() - 1, new CaptionWord(
							previousWord.text() + part, previousWord.startMs(), previousWord.endMs(),
							previousWord.leadingSpace()));
					continue;
				}
				if (tokenStartMs == null || tokenEndMs == null || tokenEndMs <= tokenStartMs) {
					return List.of();
				}
				if (previousWord == null || nextLeadingSpace) {
					words.add(new CaptionWord(part, tokenStartMs, tokenEndMs,
							!words.isEmpty() && nextLeadingSpace));
				} else {
					words.set(words.size() - 1, new CaptionWord(
							previousWord.text() + part, previousWord.startMs(),
							Math.max(previousWord.endMs(), tokenEndMs), previousWord.leadingSpace()));
				}
				nextLeadingSpace = false;
			}
		}
		List<CaptionWord> filtered = new ArrayList<>();
		for (CaptionWord word : words) {
			if (!word.text().trim().isEmpty()) {
				filtered.add(word);
			}
		}
		return List.copyOf(filtered);
	}

	private static @Nullable Long finiteLong(JsonNode node) {
		if (node == null || !node.isNumber()) {
			return null;
		}
		double value = node.asDouble();
		if (!Double.isFinite(value)) {
			return null;
		}
		return Math.round(value);
	}

	/** {@code parseWhisperJsonCues}: whisper.cpp {@code -ojf} transcription to cues. */
	public static @Nullable List<CaptionCue> parseWhisperJsonCues(String content) {
		try {
			JsonNode parsed = mapper().readTree(content);
			JsonNode transcription = parsed == null ? null : parsed.path("transcription");
			if (transcription == null || !transcription.isArray()) {
				return List.of();
			}
			List<CaptionCue> cues = new ArrayList<>();
			int index = 0;
			for (JsonNode segment : transcription) {
				if (segment == null || !segment.isObject()) {
					continue;
				}
				Long startMs = finiteLong(segment.path("offsets").path("from"));
				Long endMs = finiteLong(segment.path("offsets").path("to"));
				String segmentText = segment.path("text").isString()
						? segment.path("text").asString().trim()
						: "";
				if (startMs == null || endMs == null || endMs <= startMs) {
					continue;
				}
				List<CaptionWord> words = parseWhisperJsonWords(segment.path("tokens"));
				String text = words.isEmpty() ? segmentText : buildCaptionTextFromWords(words);
				if (text.isEmpty()) {
					continue;
				}
				cues.add(new CaptionCue("caption-" + (++index), startMs, endMs, text, words));
			}
			return List.copyOf(cues);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING,
					"Failed to parse Whisper JSON output: " + error);
			return List.of();
		}
	}

	/** {@code parseSrtTimestamp}: {@code HH:MM:SS,mmm} to milliseconds, or null. */
	public static @Nullable Long parseSrtTimestamp(String value) {
		if (value == null) {
			return null;
		}
		java.util.regex.Matcher matcher = SRT_TIMESTAMP.matcher(value.trim());
		if (!matcher.matches()) {
			return null;
		}
		long hours = Long.parseLong(matcher.group(1));
		long minutes = Long.parseLong(matcher.group(2));
		long seconds = Long.parseLong(matcher.group(3));
		long millis = Long.parseLong(matcher.group(4));
		return hours * 60 * 60 * 1000 + minutes * 60 * 1000 + seconds * 1000 + millis;
	}

	/** {@code parseSrtCues}. */
	public static List<CaptionCue> parseSrtCues(String content) {
		if (content == null) {
			return List.of();
		}
		List<CaptionCue> cues = new ArrayList<>();
		String[] blocks = content.split("\r?\n\r?\n");
		for (int index = 0; index < blocks.length; index++) {
			String[] rawLines = blocks[index].split("\r?\n");
			List<String> lines = new ArrayList<>();
			for (String line : rawLines) {
				lines.add(line.trim());
			}
			int timingIndex = -1;
			for (int line = 0; line < lines.size(); line++) {
				if (lines.get(line).contains("-->")) {
					timingIndex = line;
					break;
				}
			}
			if (timingIndex < 0) {
				continue;
			}
			String[] timing = lines.get(timingIndex).split("-->");
			if (timing.length != 2) {
				continue;
			}
			Long startMs = parseSrtTimestamp(timing[0]);
			Long endMs = parseSrtTimestamp(timing[1]);
			if (startMs == null || endMs == null || endMs <= startMs) {
				continue;
			}
			StringBuilder text = new StringBuilder();
			for (int line = timingIndex + 1; line < lines.size(); line++) {
				String value = lines.get(line);
				if (value.isEmpty()) {
					continue;
				}
				if (text.length() > 0) {
					text.append('\n');
				}
				text.append(value);
			}
			String cueText = text.toString().trim();
			if (cueText.isEmpty()) {
				continue;
			}
			cues.add(new CaptionCue("caption-" + (index + 1), startMs, endMs, cueText));
		}
		return List.copyOf(cues);
	}

	/** {@code shouldRetryWhisperWithoutJson}: older runtimes reject {@code -ojf}. */
	public static boolean shouldRetryWhisperWithoutJson(@Nullable String message) {
		return message != null && RETRY_WITHOUT_JSON.matcher(message).find();
	}
}
