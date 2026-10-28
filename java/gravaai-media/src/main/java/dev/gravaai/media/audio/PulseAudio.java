package dev.gravaai.media.audio;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.gravaai.platform.process.ProcessResult;
import dev.gravaai.platform.process.ProcessRunner;

/**
 * PulseAudio discovery and recording (plan §5.6.2, §5.6.9).
 *
 * <p>Sources are enumerated with {@code pactl -f json list sources} through
 * {@link ProcessRunner} — the same command the Electron app runs — and parsed with a small
 * built-in JSON reader because gravaai-media does not depend on Jackson (dependency direction:
 * JSON belongs to gravaai-core, plan §4.2). Recording uses libpulse-simple through FFM
 * ({@link PulseSimpleRecordStream}).</p>
 */
public final class PulseAudio {

	private static final System.Logger LOG = System.getLogger(PulseAudio.class.getName());
	private static final Duration PACTL_TIMEOUT = Duration.ofSeconds(5);

	/** The PulseAudio pseudo-source that records what the default sink is playing. */
	public static final String DEFAULT_MONITOR = "@DEFAULT_MONITOR@";

	private PulseAudio() {}

	/**
	 * Lists the current sources, monitor sources included. Returns an empty list (and logs a
	 * warning) when {@code pactl} is missing or the server is not reachable, so callers can show
	 * "no devices" instead of failing a recording flow.
	 */
	public static List<PulseSource> listSources() {
		try {
			ProcessResult result = ProcessRunner.run(
					List.of("pactl", "-f", "json", "list", "sources"), PACTL_TIMEOUT);
			if (!result.succeeded()) {
				LOG.log(System.Logger.Level.WARNING,
						() -> "pactl list sources failed: exit " + result.exitCode() + " "
								+ result.stderr().strip());
				return List.of();
			}
			return parseSources(result.stdout());
		} catch (IOException missingPactl) {
			LOG.log(System.Logger.Level.WARNING, "pactl is unavailable: " + missingPactl);
			return List.of();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return List.of();
		}
	}

	/**
	 * Opens a 48 kHz mono float32 record stream (libpulse-simple, ~20 ms latency).
	 *
	 * @param sourceName a source name from {@link #listSources()} or {@link #DEFAULT_MONITOR}
	 * @throws IllegalStateException when PulseAudio cannot open the stream
	 */
	public static PulseRecordStream openRecord(String sourceName) {
		return PulseSimpleRecordStream.open(sourceName);
	}

	/**
	 * Opens the editor-preview playback stream (libpulse-simple, float32le 48 kHz stereo, ~40 ms
	 * target buffer) for the audio-master clock (plan §5.9).
	 *
	 * @throws IllegalStateException when PulseAudio cannot open the stream
	 */
	public static PlaybackAudioSink openPlayback() {
		return PulsePlaybackStream.open();
	}

	static List<PulseSource> parseSources(String json) {
		Object parsed = new JsonReader(json).parse();
		if (!(parsed instanceof List<?> list)) {
			LOG.log(System.Logger.Level.WARNING, "pactl output is not a JSON array");
			return List.of();
		}
		List<PulseSource> sources = new ArrayList<>(list.size());
		for (Object entry : list) {
			if (!(entry instanceof Map<?, ?> object)) {
				continue;
			}
			String name = stringValue(object, "name");
			if (name == null || name.isBlank()) {
				continue;
			}
			String description = stringValue(object, "description");
			sources.add(new PulseSource(name,
					description == null || description.isBlank() ? name : description));
		}
		return List.copyOf(sources);
	}

	private static String stringValue(Map<?, ?> object, String key) {
		Object value = object.get(key);
		return value instanceof String text ? text : null;
	}

	/** Minimal tolerant JSON reader: objects, arrays, strings, numbers, booleans and null. */
	static final class JsonReader {

		private final String text;
		private int position;

		JsonReader(String text) {
			this.text = text == null ? "" : text;
		}

		Object parse() {
			skipWhitespace();
			if (position >= text.length()) {
				return null;
			}
			Object value = readValue();
			skipWhitespace();
			return value;
		}

		private Object readValue() {
			skipWhitespace();
			if (position >= text.length()) {
				throw error("unexpected end of input");
			}
			char current = text.charAt(position);
			return switch (current) {
				case '{' -> readObject();
				case '[' -> readArray();
				case '"' -> readString();
				case 't' -> readLiteral("true", Boolean.TRUE);
				case 'f' -> readLiteral("false", Boolean.FALSE);
				case 'n' -> readLiteral("null", null);
				default -> readNumber();
			};
		}

		private Map<String, Object> readObject() {
			Map<String, Object> object = new java.util.LinkedHashMap<>();
			position++;
			skipWhitespace();
			if (peek() == '}') {
				position++;
				return object;
			}
			while (true) {
				skipWhitespace();
				String key = readString();
				skipWhitespace();
				expect(':');
				object.put(key, readValue());
				skipWhitespace();
				char next = next();
				if (next == '}') {
					return object;
				}
				if (next != ',') {
					throw error("expected , or } in object");
				}
			}
		}

		private List<Object> readArray() {
			List<Object> list = new ArrayList<>();
			position++;
			skipWhitespace();
			if (peek() == ']') {
				position++;
				return list;
			}
			while (true) {
				skipWhitespace();
				list.add(readValue());
				skipWhitespace();
				char next = next();
				if (next == ']') {
					return list;
				}
				if (next != ',') {
					throw error("expected , or ] in array");
				}
			}
		}

		private String readString() {
			expect('"');
			StringBuilder builder = new StringBuilder();
			while (position < text.length()) {
				char current = text.charAt(position++);
				if (current == '"') {
					return builder.toString();
				}
				if (current != '\\') {
					builder.append(current);
					continue;
				}
				char escaped = text.charAt(position++);
				switch (escaped) {
					case '"', '\\', '/' -> builder.append(escaped);
					case 'b' -> builder.append('\b');
					case 'f' -> builder.append('\f');
					case 'n' -> builder.append('\n');
					case 'r' -> builder.append('\r');
					case 't' -> builder.append('\t');
					case 'u' -> {
						builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
						position += 4;
					}
					default -> throw error("invalid escape \\" + escaped);
				}
			}
			throw error("unterminated string");
		}

		private Object readNumber() {
			int start = position;
			while (position < text.length()) {
				char current = text.charAt(position);
				if ((current >= '0' && current <= '9') || current == '-' || current == '+'
						|| current == '.' || current == 'e' || current == 'E') {
					position++;
				} else {
					break;
				}
			}
			String number = text.substring(start, position);
			if (number.isEmpty()) {
				throw error("invalid value");
			}
			return Double.parseDouble(number);
		}

		private Object readLiteral(String literal, Object value) {
			if (text.startsWith(literal, position)) {
				position += literal.length();
				return value;
			}
			throw error("invalid literal");
		}

		private void skipWhitespace() {
			while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
				position++;
			}
		}

		private char peek() {
			return position < text.length() ? text.charAt(position) : '\0';
		}

		private char next() {
			if (position >= text.length()) {
				throw error("unexpected end of input");
			}
			return text.charAt(position++);
		}

		private void expect(char expected) {
			char actual = next();
			if (actual != expected) {
				throw error("expected " + expected + " but found " + actual);
			}
		}

		private IllegalArgumentException error(String message) {
			return new IllegalArgumentException("invalid pactl JSON at " + position + ": " + message);
		}
	}
}
