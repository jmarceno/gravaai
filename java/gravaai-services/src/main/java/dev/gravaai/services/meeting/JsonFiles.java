package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON helpers that keep Gravaai's meeting files byte-compatible with Electron's
 * {@code JSON.stringify(value, null, 2)} (plan §5.2).
 *
 * <p>core-storage publishes the shared store helpers for settings and projects; meeting metadata
 * (transcript.md / notes.md / meeting.json next to a recording) is written by the meeting service
 * and asserted byte-for-byte in tests, so it owns this small helper.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class JsonFiles {

	private static final ObjectMapper MAPPER = JsonMapper.shared();
	private static final ObjectWriter PRETTY = MAPPER.writer().with(prettyPrinter());

	private JsonFiles() {
	}

	private static DefaultPrettyPrinter prettyPrinter() {
		Separators separators = Separators.createDefaultInstance()
				.withObjectNameValueSpacing(Separators.Spacing.AFTER);
		return new DefaultPrettyPrinter(separators)
				.withArrayIndenter(new DefaultIndenter("  ", "\n"))
				.withObjectIndenter(new DefaultIndenter("  ", "\n"));
	}

	/** {@code JSON.stringify(value, null, 2)}: two-space indent, no space before the colon. */
	public static @Nullable String pretty(Object value) {
		return PRETTY.writeValueAsString(value);
	}

	/** Parses a UTF-8 JSON file; tolerates a BOM; null when it is missing, empty or invalid. */
	public static @Nullable JsonNode readTreeOrNull(Path file) {
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			if (text.startsWith("\uFEFF")) {
				text = text.substring(1);
			}
			if (text.isBlank()) {
				return null;
			}
			return MAPPER.readTree(text);
		} catch (IOException | RuntimeException error) {
			return null;
		}
	}

	/** The JSON object in a file as a map that keeps the file's key order; empty when unreadable. */
	public static Map<String, Object> readObjectOrEmpty(Path file) {
		JsonNode node = readTreeOrNull(file);
		if (node == null || !node.isObject()) {
			return new LinkedHashMap<>();
		}
		return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
		});
	}

	/** Writes through a temporary file and a rename, like Electron's atomicSave. */
	public static void writeAtomic(Path file, String content) {
		Path temporary = Path.of(file + ".tmp");
		try {
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException error) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}

	public static void writeJsonAtomic(Path file, Object value) {
		writeAtomic(file, pretty(value));
	}
}
