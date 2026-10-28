package dev.gravaai.services.automations;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure template and file-name helpers (port of {@code src/lib/automationTemplate.ts}). No file
 * system or platform APIs are used here.
 */
public final class AutomationText {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}");
	private static final Pattern INVALID_FILE_NAME_CHARACTERS = Pattern.compile("[<>:\"|?*\\\\/]");
	private static final Pattern JS_WHITESPACE_RUN = Pattern.compile(
			"[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+");
	private static final Pattern TRAILING_DOTS_OR_SPACES = Pattern.compile("[. ]+$");

	/** Port of the object {@code splitAutomationPath} returns. */
	public record SplitPath(String dir, String base, String name, String ext) {}

	private AutomationText() {}

	/** Port of {@code splitAutomationPath}: handles POSIX and Windows separators, keeps inner dots. */
	public static SplitPath splitAutomationPath(String filePath) {
		String normalized = filePath == null ? "" : filePath;
		int separatorIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
		String directory = separatorIndex >= 0 ? normalized.substring(0, separatorIndex) : "";
		String base = separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
		int dotIndex = base.lastIndexOf('.');
		boolean hasExtension = dotIndex > 0 && dotIndex < base.length() - 1;
		return new SplitPath(
				directory.isEmpty() ? "." : directory,
				base,
				hasExtension ? base.substring(0, dotIndex) : base,
				hasExtension ? base.substring(dotIndex + 1).toLowerCase(java.util.Locale.ROOT) : "");
	}

	/** Port of the TS {@code pad} helper: left-pads with zeroes to {@code length}. */
	private static String pad(int value, int length) {
		String text = String.valueOf(value);
		return text.length() >= length ? text : "0".repeat(length - text.length()) + text;
	}

	/** Port of {@code formatAutomationDate}: local calendar date, e.g. "2026-10-24". */
	public static String formatAutomationDate(Instant at) {
		ZonedDateTime local = at.atZone(ZoneId.systemDefault());
		return local.getYear() + "-" + pad(local.getMonthValue(), 2) + "-" + pad(local.getDayOfMonth(), 2);
	}

	/** Port of {@code formatAutomationTime}: local wall-clock time, e.g. "13-45-06". */
	public static String formatAutomationTime(Instant at) {
		ZonedDateTime local = at.atZone(ZoneId.systemDefault());
		return pad(local.getHour(), 2) + "-" + pad(local.getMinute(), 2) + "-" + pad(local.getSecond(), 2);
	}

	/** Port of the {@code "~"} expansion used by the automation file actions. */
	public static String expandHomeDirectory(String target, java.nio.file.Path homedir) {
		if (target == null) {
			return "";
		}
		if (target.equals("~")) {
			return homedir.toString();
		}
		if (target.startsWith("~/") || target.startsWith("~\\")) {
			return homedir.resolve(target.substring(2)).toString();
		}
		return target;
	}

	/**
	 * Port of {@code expandAutomationTemplate}: replaces {@code {path}}, {@code {dir}}, {@code
	 * {name}}, {@code {ext}}, {@code {date}}, {@code {time}}, {@code {kind}}, {@code {format}} and
	 * {@code {notesPath}}. Unknown placeholders stay as-is; known ones without a value expand to an
	 * empty string.
	 */
	public static String expandAutomationTemplate(String template, AutomationContext context) {
		if (template == null || template.isEmpty()) {
			return "";
		}
		SplitPath parts = splitAutomationPath(context.path());
		Map<String, String> values = new LinkedHashMap<>();
		values.put("path", context.path());
		values.put("dir", parts.dir());
		values.put("name", parts.name());
		values.put("ext", parts.ext());
		values.put("date", formatAutomationDate(context.at()));
		values.put("time", formatAutomationTime(context.at()));
		values.put("kind", context.kind() == null ? null : context.kind().wire());
		values.put("format", context.format() == null ? null : context.format().wire());
		values.put("notesPath", context.notesPath());
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String key = matcher.group(1);
			String replacement;
			if (!values.containsKey(key)) {
				replacement = matcher.group();
			} else {
				replacement = values.get(key) == null ? "" : values.get(key);
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/** Port of {@code sanitizeFileName}: safe and readable on every platform. */
	public static String sanitizeFileName(String name) {
		String cleaned = stripControlCharacters(name == null ? "" : name);
		cleaned = INVALID_FILE_NAME_CHARACTERS.matcher(cleaned).replaceAll("");
		cleaned = JS_WHITESPACE_RUN.matcher(cleaned).replaceAll(" ");
		cleaned = stripLeadingAndTrailingWhitespace(cleaned);
		cleaned = TRAILING_DOTS_OR_SPACES.matcher(cleaned).replaceAll("");
		String sliced = stripLeadingAndTrailingWhitespace(
				cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200));
		if (sliced.isEmpty() || sliced.equals(".") || sliced.equals("..")) {
			return "file";
		}
		return sliced;
	}

	private static String stripControlCharacters(String value) {
		StringBuilder result = new StringBuilder();
		value.codePoints().forEach(codePoint -> {
			if (codePoint >= 0x20 && codePoint != 0x7f) {
				result.appendCodePoint(codePoint);
			}
		});
		return result.toString();
	}

	/** JavaScript {@code String.prototype.trim}: both ends, Unicode whitespace. */
	private static String stripLeadingAndTrailingWhitespace(String value) {
		int start = 0;
		int end = value.length();
		while (start < end && isJsWhitespace(value.charAt(start))) {
			start++;
		}
		while (end > start && isJsWhitespace(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(start, end);
	}

	private static boolean isJsWhitespace(char ch) {
		return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000B' || ch == '\f' || ch == '\r'
				|| ch == '\u00A0' || ch == '\u1680' || (ch >= '\u2000' && ch <= '\u200A')
				|| ch == '\u2028' || ch == '\u2029' || ch == '\u202F' || ch == '\u205F'
				|| ch == '\u3000' || ch == '\uFEFF';
	}
}
