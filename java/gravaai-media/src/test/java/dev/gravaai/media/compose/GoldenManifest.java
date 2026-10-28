package dev.gravaai.media.compose;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reader for the golden-fixture manifest published by task-13 (platform) at
 * {@code java/parity/golden/manifest.json}.
 *
 * <p>Agreed format (one entry per fixture case):</p>
 * <pre>{@code
 * [
 *   {
 *     "projectPath": "projects/fixture.gravaai",
 *     "outputWidth": 1920,
 *     "outputHeight": 1080,
 *     "times": [0.0, 1.0, 2.0],
 *     "referenceImages": ["fixture/0.png", "fixture/1000.png", "fixture/2000.png"]
 *   }
 * ]
 * }</pre>
 *
 * <p>The reader is tolerant: a top-level object with a {@code cases} array and a single case
 * object are accepted too, so the platform can publish either shape. Reference paths are
 * resolved relative to the manifest's directory. A missing manifest means the goldens have not
 * landed yet; tests must skip (self-consistency tests cover the compositor until then).</p>
 */
final class GoldenManifest {

	private GoldenManifest() {}

	/**
	 * One golden case.
	 *
	 * @param projectPath the fixture project, resolved against the manifest directory
	 * @param outputWidth the compositor output width the references were rendered at
	 * @param outputHeight the compositor output height
	 * @param times the project times (seconds) of the reference frames, in order
	 * @param referenceImages the reference PNGs, in the same order as {@code times}
	 */
	record Case(Path projectPath, int outputWidth, int outputHeight, double[] times,
			List<Path> referenceImages) {
		Case {
			times = times.clone();
			referenceImages = List.copyOf(referenceImages);
			if (times.length != referenceImages.size()) {
				throw new IllegalArgumentException("times and referenceImages must have the same"
						+ " length, got " + times.length + " and " + referenceImages.size());
			}
		}
	}

	/** The default manifest path relative to the media module (Maven Surefire working directory). */
	static Path defaultManifest() {
		return Path.of("..", "parity", "golden", "manifest.json").toAbsolutePath().normalize();
	}

	/** Loads the default manifest, or empty when the platform has not published it yet. */
	static java.util.Optional<List<Case>> loadDefault() {
		Path manifest = defaultManifest();
		if (!Files.isRegularFile(manifest)) {
			return java.util.Optional.empty();
		}
		try {
			return java.util.Optional.of(load(manifest));
		} catch (IOException failure) {
			throw new IllegalStateException("cannot read golden manifest " + manifest, failure);
		}
	}

	/** Parses a manifest file. */
	static List<Case> load(Path manifest) throws IOException {
		String json = Files.readString(manifest, StandardCharsets.UTF_8);
		Path directory = manifest.toAbsolutePath().getParent();
		return parse(json, directory);
	}

	/** Parses manifest JSON; {@code directory} resolves project and reference paths. */
	static List<Case> parse(String json, Path directory) {
		Object root = new JsonReader(json).parse();
		List<Object> entries;
		if (root instanceof List<?> list) {
			entries = new ArrayList<>(list);
		} else if (root instanceof Map<?, ?> object && object.get("cases") instanceof List<?> cases) {
			entries = new ArrayList<>(cases);
		} else if (root instanceof Map<?, ?>) {
			entries = List.of(root);
		} else {
			throw new IllegalArgumentException("golden manifest must be an array of cases");
		}
		List<Case> result = new ArrayList<>(entries.size());
		for (Object entry : entries) {
			if (!(entry instanceof Map<?, ?> object)) {
				throw new IllegalArgumentException("golden case must be a JSON object: " + entry);
			}
			String projectPath = requireString(object, "projectPath");
			int outputWidth = requireInt(object, "outputWidth");
			int outputHeight = requireInt(object, "outputHeight");
			List<Double> times = requireNumbers(object, "times");
			List<String> references = requireStrings(object, "referenceImages");
			if (times.size() != references.size()) {
				throw new IllegalArgumentException("case " + projectPath + ": times/referenceImages"
						+ " length mismatch (" + times.size() + " vs " + references.size() + ")");
			}
			double[] timeArray = new double[times.size()];
			List<Path> referencePaths = new ArrayList<>(references.size());
			for (int i = 0; i < times.size(); i++) {
				timeArray[i] = times.get(i);
				referencePaths.add(directory.resolve(references.get(i)).normalize());
			}
			result.add(new Case(directory.resolve(projectPath).normalize(), outputWidth,
					outputHeight, timeArray, referencePaths));
		}
		return List.copyOf(result);
	}

	private static String requireString(Map<?, ?> object, String key) {
		Object value = object.get(key);
		if (!(value instanceof String text) || text.isBlank()) {
			throw new IllegalArgumentException("golden case is missing string field " + key);
		}
		return text;
	}

	private static int requireInt(Map<?, ?> object, String key) {
		Object value = object.get(key);
		if (!(value instanceof Number number)) {
			throw new IllegalArgumentException("golden case is missing number field " + key);
		}
		int result = number.intValue();
		if (result <= 0) {
			throw new IllegalArgumentException("golden case field " + key + " must be positive");
		}
		return result;
	}

	private static List<Double> requireNumbers(Map<?, ?> object, String key) {
		if (!(object.get(key) instanceof List<?> list)) {
			throw new IllegalArgumentException("golden case is missing array field " + key);
		}
		List<Double> numbers = new ArrayList<>(list.size());
		for (Object entry : list) {
			if (!(entry instanceof Number number)) {
				throw new IllegalArgumentException("golden case field " + key + " must be numbers");
			}
			numbers.add(number.doubleValue());
		}
		return numbers;
	}

	private static List<String> requireStrings(Map<?, ?> object, String key) {
		if (!(object.get(key) instanceof List<?> list)) {
			throw new IllegalArgumentException("golden case is missing array field " + key);
		}
		List<String> strings = new ArrayList<>(list.size());
		for (Object entry : list) {
			if (!(entry instanceof String text)) {
				throw new IllegalArgumentException("golden case field " + key + " must be strings");
			}
			strings.add(text);
		}
		return strings;
	}

	/** Parses any JSON text into maps/lists/scalars (shared with the fixture metadata reader). */
	static Object parseJson(String json) {
		return new JsonReader(json).parse();
	}

	/** Minimal JSON reader (manifest only: objects, arrays, strings, numbers, booleans, null). */
	private static final class JsonReader {
		private final String text;
		private int position;

		JsonReader(String text) {
			this.text = text == null ? "" : text;
		}

		Object parse() {
			skipWhitespace();
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
					default -> throw error("invalid escape");
				}
			}
			throw error("unterminated string");
		}

		private Object readNumber() {
			int start = position;
			while (position < text.length()) {
				char current = text.charAt(position);
				if (Character.isDigit(current) || current == '-' || current == '+' || current == '.'
						|| current == 'e' || current == 'E') {
					position++;
				} else {
					break;
				}
			}
			if (start == position) {
				throw error("invalid value");
			}
			return Double.parseDouble(text.substring(start, position));
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
			if (next() != expected) {
				throw error("expected " + expected);
			}
		}

		private IllegalArgumentException error(String message) {
			return new IllegalArgumentException(
					"invalid golden manifest JSON at " + position + ": " + message);
		}
	}
}
