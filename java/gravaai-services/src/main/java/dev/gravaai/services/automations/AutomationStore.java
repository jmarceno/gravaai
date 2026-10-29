package dev.gravaai.services.automations;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.StorageFiles;
import dev.gravaai.core.storage.UserData;
import tools.jackson.databind.JsonNode;

/**
 * Reads and writes {@code userData/automations.json} (port of {@code electron/automations/store.ts}).
 *
 * <p>Reads tolerate a UTF-8 BOM and treat invalid or missing files as an empty rule list. Writes
 * normalise first, format with {@link Json} ({@code JSON.stringify(x, null, 2)} + newline) and
 * commit with a temporary file plus rename. Writes to one file run in call order on a single
 * thread, so concurrent saves cannot interleave.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class AutomationStore implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.automations");

	private final Path filePath;
	private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "gravaai-automations-store");
		thread.setDaemon(true);
		return thread;
	});

	public AutomationStore(Path filePath) {
		this.filePath = filePath;
	}

	/** The production path, {@code <userData>/automations.json}. */
	public static AutomationStore defaultStore() {
		return new AutomationStore(UserData.resolve().dir().resolve(StorageFiles.AUTOMATIONS_FILE_NAME));
	}

	public Path filePath() {
		return filePath;
	}

	/** Port of {@code readAutomationsFile}. */
	public AutomationsFile read() {
		try {
			JsonNode content = Json.readFile(filePath);
			return Automations.normalizeAutomationsFile(content);
		} catch (NoSuchFileException error) {
			return Automations.createEmptyAutomationsFile();
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "[automations] Could not read rules: " + error);
			return Automations.createEmptyAutomationsFile();
		}
	}

	/** Port of {@code writeAutomationsFile}: normalises, writes and resolves with what was persisted. */
	public CompletableFuture<AutomationsFile> writeAsync(JsonNode value) {
		AutomationsFile normalised = Automations.normalizeAutomationsFile(value);
		String contents = Json.stringify(Automations.toJson(normalised)) + "\n";
		return CompletableFuture.supplyAsync(() -> {
			try {
				commit(contents);
				return normalised;
			} catch (IOException error) {
				throw new UncheckedIOException(error);
			}
		}, writeExecutor);
	}

	/** Blocking convenience for {@link #writeAsync(JsonNode)}; throws the original I/O error. */
	public AutomationsFile write(JsonNode value) throws IOException {
		try {
			return writeAsync(value).join();
		} catch (CompletionException error) {
			if (error.getCause() instanceof IOException ioException) {
				throw ioException;
			}
			if (error.getCause() instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw error;
		}
	}

	@Override
	public void close() {
		writeExecutor.shutdown();
	}

	private void commit(String contents) throws IOException {
		Path targetPath = filePath.toAbsolutePath();
		Path parentDir = targetPath.getParent();
		Path temporaryPath = parentDir.resolve(
				".gravaai-automations-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID() + ".tmp");
		Files.createDirectories(parentDir);
		try {
			Files.writeString(temporaryPath, contents, StandardCharsets.UTF_8);
			Files.move(temporaryPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporaryPath);
		}
	}
}
