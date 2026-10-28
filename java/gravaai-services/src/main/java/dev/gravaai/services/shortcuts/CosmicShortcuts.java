package dev.gravaai.services.shortcuts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reader/writer for COSMIC's keyboard shortcut config
 * ({@code com.system76.CosmicSettings.Shortcuts/v1/{custom,defaults}}, RON).
 *
 * <p>Port of {@code electron/globalShortcuts/cosmicShortcuts.ts}. The parser understands exactly
 * the map shape COSMIC writes. Gravaai only ever splices its own entries in or out by offset, so
 * every other byte of the user's file stays as it was. Any parse error aborts before writing.
 *
 * <p>Offsets are Java {@code String} indices, i.e. UTF-16 code units, exactly like the JavaScript
 * original, so the same fixture offsets hold.
 */
public final class CosmicShortcuts {

	/** Port of {@code GRAVAAI_DESCRIPTION_PREFIX}. */
	public static final String GRAVAAI_DESCRIPTION_PREFIX = "Gravaai: ";

	private static final String CTL_BINARY_NAME = "gravaai-ctl";
	private static final String SHORTCUTS_SUBDIR = "cosmic/com.system76.CosmicSettings.Shortcuts/v1";

	private CosmicShortcuts() {}

	/** A token of the RON subset COSMIC writes; offsets are Java string indices. */
	private enum TokenType {
		PUNCT("punct"),
		STRING("string"),
		IDENT("ident"),
		NUMBER("number");

		private final String label;

		TokenType(String label) {
			this.label = label;
		}
	}

	private record Token(TokenType type, String value, int start, int end) {}

	/** Port of {@code parseCosmicShortcuts}. */
	public static ParsedCosmicShortcuts parseCosmicShortcuts(String text) {
		return new Parser(text).parse();
	}

	/** Port of {@code isGravaaiEntry}. */
	public static boolean isGravaaiEntry(CosmicShortcutEntry entry) {
		return (entry.description() != null && entry.description().startsWith(GRAVAAI_DESCRIPTION_PREFIX))
				|| (entry.spawnCommand() != null && entry.spawnCommand().contains(CTL_BINARY_NAME));
	}

	/** Renders one entry in the pretty form cosmic-settings writes (port of {@code renderCosmicEntry}). */
	public static String renderCosmicEntry(GlobalShortcutBinding binding, String description, String command) {
		List<String> modifiers = bindingModifiers(binding);
		String modifierText;
		if (modifiers.isEmpty()) {
			modifierText = "[]";
		} else {
			StringBuilder text = new StringBuilder("[\n");
			for (String modifier : modifiers) {
				text.append("            ").append(modifier).append(",\n");
			}
			text.append("        ]");
			modifierText = text.toString();
		}
		return "    (\n"
				+ "        modifiers: " + modifierText + ",\n"
				+ "        key: \"" + escapeRonString(binding.key()) + "\",\n"
				+ "        description: Some(\"" + escapeRonString(description) + "\"),\n"
				+ "    ): Spawn(\"" + escapeRonString(command) + "\"),\n";
	}

	/**
	 * Removes every Gravaai entry (by description prefix, or a Spawn that runs gravaai-ctl, which
	 * also cleans up after a moved AppImage) and appends {@code next} before the closing brace.
	 * Throws {@link CosmicShortcutsParseException} on a bad file (port of
	 * {@code replaceGravaaiEntries}).
	 */
	public static String replaceGravaaiEntries(String text, List<GravaaiCosmicEntry> next) {
		String result = text.trim().isEmpty() ? "{\n}\n" : text;
		ParsedCosmicShortcuts parsed = parseCosmicShortcuts(result);
		List<CosmicShortcutEntry> ours = new ArrayList<>();
		for (CosmicShortcutEntry entry : parsed.entries()) {
			if (isGravaaiEntry(entry)) {
				ours.add(entry);
			}
		}
		for (int index = ours.size() - 1; index >= 0; index--) {
			CosmicShortcutEntry entry = ours.get(index);
			result = result.substring(0, entry.start()) + result.substring(entry.end());
		}
		if (next == null || next.isEmpty()) {
			return result;
		}

		ParsedCosmicShortcuts remaining = parseCosmicShortcuts(result);
		CosmicShortcutEntry last =
				remaining.entries().isEmpty() ? null : remaining.entries().get(remaining.entries().size() - 1);
		int closeBrace = remaining.closeBrace();
		if (last != null && !last.hasTrailingComma()) {
			result = result.substring(0, last.valueEnd()) + "," + result.substring(last.valueEnd());
			closeBrace++;
		}
		StringBuilder rendered = new StringBuilder();
		for (GravaaiCosmicEntry entry : next) {
			rendered.append(renderCosmicEntry(entry.binding(), entry.description(), entry.command()));
		}
		int insertAt = lineAlignedStart(result, closeBrace);
		String prefix = insertAt == 0 || result.charAt(insertAt - 1) == '\n' ? "" : "\n";
		return result.substring(0, insertAt) + prefix + rendered + result.substring(insertAt);
	}

	/** Port of {@code shellQuote}: single quotes, with {@code '\''} for an embedded quote. */
	public static String shellQuote(String value) {
		return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
	}

	/** The command COSMIC runs through {@code /bin/sh -c} (port of {@code buildCtlCommand}). */
	public static String buildCtlCommand(Path ctlPath, GlobalShortcutAction action, String appPath) {
		return shellQuote(ctlPath.toString()) + " " + action.wire() + " " + shellQuote(appPath);
	}

	/** Port of {@code findCosmicConflict}: another (non-Gravaai) entry using the same keys. */
	public static CosmicShortcutEntry findCosmicConflict(
			GlobalShortcutBinding binding, List<CosmicShortcutEntry> entries) {
		List<String> modifiers = bindingModifiers(binding);
		for (CosmicShortcutEntry entry : entries) {
			if (isGravaaiEntry(entry) || entry.key() == null) {
				continue;
			}
			if (entry.key().equalsIgnoreCase(binding.key()) && sameModifierSet(entry.modifiers(), modifiers)) {
				return entry;
			}
		}
		return null;
	}

	/** Port of {@code describeCosmicConflict}, e.g. "Super + Q is used by COSMIC (Close)". */
	public static String describeCosmicConflict(GlobalShortcutBinding binding, CosmicShortcutEntry entry) {
		String what = entry.description() != null
				? entry.description()
				: entry.action().replaceAll("(?s)\\(.*$", "");
		String label = what.length() > 60 ? what.substring(0, 57) + "..." : what;
		return GlobalShortcuts.formatGlobalBinding(binding) + " is used by COSMIC (" + label + ")";
	}

	/** Port of {@code getCosmicShortcutPaths}. */
	public static CosmicShortcutPaths getCosmicShortcutPaths(Map<String, String> env) {
		return getCosmicShortcutPaths(env, Path.of(System.getProperty("user.home")));
	}

	public static CosmicShortcutPaths getCosmicShortcutPaths(Map<String, String> env, Path home) {
		String configHome = trimToNull(env == null ? null : env.get("XDG_CONFIG_HOME"));
		Path configHomePath = configHome != null ? Path.of(configHome) : home.resolve(".config");
		String dataDirsValue = trimToNull(env == null ? null : env.get("XDG_DATA_DIRS"));
		String dataDirsText = dataDirsValue != null ? dataDirsValue : "/usr/local/share:/usr/share";
		List<String> dataDirs = Arrays.stream(dataDirsText.split(":", -1))
				.filter(entry -> !entry.isEmpty())
				.toList();
		Path userDir = configHomePath.resolve(SHORTCUTS_SUBDIR);
		List<Path> systemDefaults = new ArrayList<>();
		for (String dir : dataDirs) {
			systemDefaults.add(Path.of(dir).resolve(SHORTCUTS_SUBDIR).resolve("defaults"));
		}
		return new CosmicShortcutPaths(
				userDir.resolve("custom"), userDir.resolve("defaults"), systemDefaults);
	}

	/** Reads {@code custom}, or "" when it does not exist (port of {@code readCosmicCustomShortcuts}). */
	public static String readCosmicCustomShortcuts(CosmicShortcutPaths paths) throws IOException {
		String text = readIfExists(paths.custom());
		return text != null ? text : "";
	}

	/** The user's {@code defaults} override wins over the system file, as in cosmic-config. */
	public static String readCosmicDefaultShortcuts(CosmicShortcutPaths paths) throws IOException {
		List<Path> files = new ArrayList<>();
		files.add(paths.userDefaults());
		files.addAll(paths.systemDefaults());
		for (Path file : files) {
			String text = readIfExists(file);
			if (text != null) {
				return text;
			}
		}
		return "";
	}

	/**
	 * Atomically replaces {@code custom}. Before the first Gravaai write ever, the user's file is
	 * copied to {@code custom.gravaai-backup} (port of {@code writeCosmicCustomShortcuts}).
	 */
	public static void writeCosmicCustomShortcuts(String nextText, CosmicShortcutPaths paths) throws IOException {
		Path custom = paths.custom();
		Files.createDirectories(custom.getParent());
		Path backup = Path.of(custom + ".gravaai-backup");
		String current = readIfExists(custom);
		if (current != null && readIfExists(backup) == null) {
			Files.writeString(backup, current, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		}
		Path temporary = Path.of(custom + ".tmp-gravaai");
		Files.writeString(temporary, nextText, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		Files.move(temporary, custom, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Port of {@code escapeRonString}. */
	static String escapeRonString(String value) {
		return value
				.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	/** Port of {@code bindingModifiers}. */
	static List<String> bindingModifiers(GlobalShortcutBinding binding) {
		List<String> modifiers = new ArrayList<>();
		if (binding.sup()) {
			modifiers.add("Super");
		}
		if (binding.ctrl()) {
			modifiers.add("Ctrl");
		}
		if (binding.alt()) {
			modifiers.add("Alt");
		}
		if (binding.shift()) {
			modifiers.add("Shift");
		}
		return modifiers;
	}

	/** Port of {@code sameModifierSet}: case-insensitive set comparison, order does not matter. */
	private static boolean sameModifierSet(List<String> left, List<String> right) {
		Set<String> a = new LinkedHashSet<>();
		for (String modifier : left) {
			a.add(modifier.toLowerCase(Locale.ROOT));
		}
		Set<String> b = new LinkedHashSet<>();
		for (String modifier : right) {
			b.add(modifier.toLowerCase(Locale.ROOT));
		}
		return a.equals(b);
	}

	/** Widens an entry to whole lines when it sits on lines of its own (port of
	 * {@code lineAlignedStart}). */
	private static int lineAlignedStart(String text, int offset) {
		int i = offset;
		while (i > 0 && (text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) {
			i--;
		}
		return i == 0 || text.charAt(i - 1) == '\n' ? i : offset;
	}

	/** Port of {@code lineAlignedEnd}. */
	private static int lineAlignedEnd(String text, int offset) {
		int i = offset;
		while (i < text.length()
				&& (text.charAt(i) == ' ' || text.charAt(i) == '\t' || text.charAt(i) == '\r')) {
			i++;
		}
		return i < text.length() && text.charAt(i) == '\n' ? i + 1 : offset;
	}

	private static String readIfExists(Path file) throws IOException {
		try {
			return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		} catch (NoSuchFileException error) {
			return null;
		}
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** The recursive-descent parser of {@link #parseCosmicShortcuts(String)}. */
	private static final class Parser {

		private final String text;
		private final List<Token> tokens;
		private int pos;

		Parser(String text) {
			this.text = text == null ? "" : text;
			this.tokens = tokenize(this.text);
		}

		ParsedCosmicShortcuts parse() {
			Token openToken = expectPunct("{");
			List<CosmicShortcutEntry> entries = new ArrayList<>();
			Token closeToken = null;
			while (closeToken == null) {
				if (isPunct("}")) {
					closeToken = next();
					break;
				}
				Token entryOpen = expectPunct("(");
				List<String> modifiers = new ArrayList<>();
				String key = null;
				String description = null;
				while (!isPunct(")")) {
					String field = expectType(TokenType.IDENT).value();
					expectPunct(":");
					if (field.equals("modifiers")) {
						expectPunct("[");
						while (!isPunct("]")) {
							modifiers.add(expectType(TokenType.IDENT).value());
							if (!isPunct("]")) {
								expectPunct(",");
							}
						}
						expectPunct("]");
					} else if (field.equals("key")) {
						key = expectType(TokenType.STRING).value();
					} else if (field.equals("keycode")) {
						expectType(TokenType.NUMBER);
					} else if (field.equals("description")) {
						String variant = expectType(TokenType.IDENT).value();
						if (variant.equals("Some")) {
							expectPunct("(");
							description = expectType(TokenType.STRING).value();
							expectPunct(")");
						} else if (!variant.equals("None")) {
							pos--;
							throw fail("Expected Some or None");
						}
					} else {
						skipValue();
					}
					if (!isPunct(")")) {
						expectPunct(",");
					}
				}
				expectPunct(")");
				expectPunct(":");

				Token actionToken = expectType(TokenType.IDENT);
				String spawnCommand = null;
				int valueEnd = actionToken.end();
				if (isPunct("(")) {
					int groupStart = pos;
					valueEnd = skipGroup(")");
					List<Token> inner = tokens.subList(groupStart + 1, pos - 1);
					if (actionToken.value().equals("Spawn")
							&& inner.size() == 1
							&& inner.get(0).type() == TokenType.STRING) {
						spawnCommand = inner.get(0).value();
					}
				}
				boolean hasTrailingComma = isPunct(",");
				int rawEnd = valueEnd;
				if (hasTrailingComma) {
					rawEnd = next().end();
				} else if (!isPunct("}")) {
					throw fail("Expected \",\" or \"}\"");
				}
				entries.add(new CosmicShortcutEntry(
						modifiers,
						key,
						description,
						text.substring(actionToken.start(), valueEnd),
						spawnCommand,
						lineAlignedStart(text, entryOpen.start()),
						lineAlignedEnd(text, rawEnd),
						valueEnd,
						hasTrailingComma));
			}
			if (pos != tokens.size()) {
				throw fail("Unexpected content after the closing brace");
			}
			return new ParsedCosmicShortcuts(entries, openToken.start(), closeToken.start());
		}

		/** Skips any value; returns the offset just past it. */
		private int skipValue() {
			Token token = next();
			if (token.type() == TokenType.STRING || token.type() == TokenType.NUMBER) {
				return token.end();
			}
			if (token.type() == TokenType.IDENT) {
				return isPunct("(") ? skipGroup(")") : token.end();
			}
			if (token.value().equals("(")) {
				return skipGroupBody(")");
			}
			if (token.value().equals("[")) {
				return skipGroupBody("]");
			}
			pos--;
			throw fail("Expected a value");
		}

		private int skipGroup(String close) {
			next();
			return skipGroupBody(close);
		}

		private int skipGroupBody(String close) {
			for (;;) {
				if (isPunct(close)) {
					return next().end();
				}
				if (isPunct(",") || isPunct(":")) {
					next();
					continue;
				}
				skipValue();
			}
		}

		private Token peek() {
			return pos < tokens.size() ? tokens.get(pos) : null;
		}

		private CosmicShortcutsParseException fail(String message) {
			Token token = peek();
			return new CosmicShortcutsParseException(message, token != null ? token.start() : text.length());
		}

		private Token next() {
			if (pos >= tokens.size()) {
				throw fail("Unexpected end of file");
			}
			return tokens.get(pos++);
		}

		private boolean isPunct(String value) {
			Token token = peek();
			return token != null && token.type() == TokenType.PUNCT && token.value().equals(value);
		}

		private Token expectPunct(String value) {
			Token token = next();
			if (token.type() != TokenType.PUNCT || !token.value().equals(value)) {
				pos--;
				throw fail("Expected \"" + value + "\"");
			}
			return token;
		}

		private Token expectType(TokenType type) {
			Token token = next();
			if (token.type() != type) {
				pos--;
				throw fail("Expected " + type.label);
			}
			return token;
		}
	}

	/** Port of {@code tokenize}. */
	private static List<Token> tokenize(String text) {
		List<Token> tokens = new ArrayList<>();
		int i = 0;
		while (i < text.length()) {
			char ch = text.charAt(i);
			if (isJsWhitespace(ch)) {
				i++;
			} else if (text.startsWith("//", i)) {
				int newline = text.indexOf("\n", i);
				i = newline == -1 ? text.length() : newline + 1;
			} else if (text.startsWith("/*", i)) {
				int close = text.indexOf("*/", i + 2);
				if (close == -1) {
					throw new CosmicShortcutsParseException("Unterminated comment", i);
				}
				i = close + 2;
			} else if (ch == '"') {
				int j = i + 1;
				while (j < text.length() && text.charAt(j) != '"') {
					j += text.charAt(j) == '\\' ? 2 : 1;
				}
				if (j >= text.length()) {
					throw new CosmicShortcutsParseException("Unterminated string", i);
				}
				tokens.add(new Token(
						TokenType.STRING,
						unescapeRonString(text.substring(i + 1, j), i + 1),
						i,
						j + 1));
				i = j + 1;
			} else if ("{}()[]:,".indexOf(ch) >= 0) {
				tokens.add(new Token(TokenType.PUNCT, String.valueOf(ch), i, i + 1));
				i++;
			} else {
				int start = i;
				boolean ident;
				if (isIdentStart(ch)) {
					i++;
					while (i < text.length() && isIdentPart(text.charAt(i))) {
						i++;
					}
					ident = true;
				} else if (ch == '-' && i + 1 < text.length() && isDigit(text.charAt(i + 1))) {
					i += 2;
					while (i < text.length() && isNumberPart(text.charAt(i))) {
						i++;
					}
					ident = false;
				} else if (isDigit(ch)) {
					i++;
					while (i < text.length() && isNumberPart(text.charAt(i))) {
						i++;
					}
					ident = false;
				} else {
					throw new CosmicShortcutsParseException("Unexpected character", i);
				}
				tokens.add(new Token(ident ? TokenType.IDENT : TokenType.NUMBER, text.substring(start, i), start, i));
			}
		}
		return tokens;
	}

	/** Port of {@code unescapeRonString}. */
	private static String unescapeRonString(String raw, int offset) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			char ch = raw.charAt(i);
			if (ch != '\\') {
				out.append(ch);
				continue;
			}
			i++;
			if (i >= raw.length()) {
				throw new CosmicShortcutsParseException("Unknown string escape", offset + i);
			}
			char next = raw.charAt(i);
			switch (next) {
				case '"', '\\', '\'' -> out.append(next);
				case 'n' -> out.append('\n');
				case 't' -> out.append('\t');
				case 'r' -> out.append('\r');
				case '0' -> out.append('\0');
				case 'u' -> {
					int codePoint = 0;
					int digits = 0;
					int index = i + 1;
					if (index >= raw.length() || raw.charAt(index) != '{') {
						throw new CosmicShortcutsParseException("Bad unicode escape", offset + i);
					}
					index++;
					while (index < raw.length() && digits < 6 && isLowerHex(raw.charAt(index))) {
						codePoint = codePoint * 16 + Character.digit(raw.charAt(index), 16);
						digits++;
						index++;
					}
					if (digits == 0 || index >= raw.length() || raw.charAt(index) != '}') {
						throw new CosmicShortcutsParseException("Bad unicode escape", offset + i);
					}
					out.appendCodePoint(codePoint);
					i = index;
				}
				default -> throw new CosmicShortcutsParseException("Unknown string escape", offset + i);
			}
		}
		return out.toString();
	}

	private static boolean isIdentStart(char ch) {
		return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch == '_';
	}

	private static boolean isIdentPart(char ch) {
		return isIdentStart(ch) || isDigit(ch);
	}

	private static boolean isNumberPart(char ch) {
		return isDigit(ch) || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch == '_' || ch == '.';
	}

	private static boolean isDigit(char ch) {
		return ch >= '0' && ch <= '9';
	}

	private static boolean isLowerHex(char ch) {
		return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
	}

	/** JavaScript's {@code \s}: Unicode whitespace, including the non-breaking ones. */
	private static boolean isJsWhitespace(char ch) {
		return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000B' || ch == '\f' || ch == '\r'
				|| ch == '\u00A0' || ch == '\u1680' || (ch >= '\u2000' && ch <= '\u200A')
				|| ch == '\u2028' || ch == '\u2029' || ch == '\u202F' || ch == '\u205F'
				|| ch == '\u3000' || ch == '\uFEFF';
	}
}
