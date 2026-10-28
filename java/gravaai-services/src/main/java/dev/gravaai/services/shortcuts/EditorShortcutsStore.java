package dev.gravaai.services.shortcuts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.result.Result;
import dev.gravaai.core.storage.StorageFiles;
import dev.gravaai.core.storage.UserData;
import tools.jackson.databind.JsonNode;

/**
 * The editor keyboard-shortcut map in {@code userData/shortcuts.json} (port of the
 * {@code get-shortcuts}/{@code save-shortcuts} handlers in {@code electron/ipc/register/settings.ts}).
 *
 * <p>The renderer owns the shape of this file, so it is stored as an opaque JSON tree: a missing
 * or invalid file reads as {@code null} and the write is {@code JSON.stringify(value, null, 2)}
 * with no trailing newline, exactly like the Electron handler.
 */
public final class EditorShortcutsStore {

	private final Path filePath;

	public EditorShortcutsStore(Path filePath) {
		this.filePath = filePath;
	}

	/** The production path, {@code <userData>/shortcuts.json}. */
	public static EditorShortcutsStore defaultStore() {
		return new EditorShortcutsStore(UserData.resolve().dir().resolve(StorageFiles.SHORTCUTS_FILE_NAME));
	}

	public Path filePath() {
		return filePath;
	}

	/** Port of the {@code get-shortcuts} handler: the stored tree, or null on any read/parse error. */
	public JsonNode read() {
		try {
			return Json.parseJsonWithByteOrderMark(new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException error) {
			return null;
		}
	}

	/** Port of the {@code save-shortcuts} handler. */
	public Result<Void> write(JsonNode shortcuts) {
		try {
			Files.createDirectories(filePath.toAbsolutePath().getParent());
			Json.writeFile(filePath, shortcuts);
			return Result.ok(null);
		} catch (IOException | RuntimeException error) {
			return Result.err(String.valueOf(error));
		}
	}
}
