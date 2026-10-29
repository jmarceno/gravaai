package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Atomic project-file writes (port of {@code electron/ipc/project/atomicSave.ts}, plan §5.2).
 *
 * <p>Writes go to a temporary file in the project directory, are fsynced, then renamed over the
 * project. The previous complete generation is preserved as {@code <project>.bak} before the
 * replacement, so a crash cannot lose both generations. Writes to the same project are queued in
 * invocation order; calls for different projects run independently.
 */
public final class AtomicProjectSave {

	private static final System.Logger LOG = System.getLogger("gravaai.project");

	/** Plans the write work off the caller thread; these are blocking file operations (plan §4.1). */
	private static final Executor WRITE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

	private static final Object QUEUE_LOCK = new Object();
	private static final Map<Path, CompletableFuture<Void>> PENDING_WRITES = new HashMap<>();

	/** Directory fsync errors that mean "this filesystem cannot sync a directory" (see atomicSave.ts). */
	private static final String[] UNSUPPORTED_DIRECTORY_SYNC_MESSAGES = {
			"Is a directory",
			"Invalid argument",
			"Operation not supported",
			"Operation not permitted",
			"Function not implemented",
	};

	private AtomicProjectSave() {}

	/** Port of {@code getProjectBackupPath}: the previous generation lives next to the project. */
	public static Path getProjectBackupPath(Path projectPath) {
		return Path.of(projectPath + ".bak");
	}

	/**
	 * Port of {@code writeProjectFileAtomically}.
	 *
	 * @return a future completing when this write (and every earlier write to the same project) has
	 *     finished; it fails like the TS promise, with the original error as the cause
	 */
	public static CompletableFuture<Void> writeProjectFileAtomically(Path projectPath, String contents) {
		Path queueKey = getQueueKey(projectPath);
		synchronized (QUEUE_LOCK) {
			CompletableFuture<Void> previous = PENDING_WRITES.get(queueKey);
			CompletableFuture<Void> base = previous != null ? previous : CompletableFuture.completedFuture(null);
			CompletableFuture<Void> current = base
					.handle((value, error) -> null)
					.thenRunAsync(() -> commitProjectFile(projectPath, contents), WRITE_EXECUTOR);
			PENDING_WRITES.put(queueKey, current);
			current.whenComplete((value, error) -> {
				synchronized (QUEUE_LOCK) {
					PENDING_WRITES.remove(queueKey, current);
				}
			});
			return current;
		}
	}

	private static Path getQueueKey(Path projectPath) {
		// The Electron code lowercases on Windows; the rewrite targets Linux only (plan §2.3).
		return projectPath.toAbsolutePath().normalize();
	}

	private static Path createTemporaryPath(Path parentDir, String label) {
		return parentDir.resolve(
				".gravaai-" + label + "-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID() + ".tmp");
	}

	private static @Nullable Integer getExistingFileMode(Path filePath) throws IOException {
		try {
			return ((Integer) Files.getAttribute(filePath, "unix:mode")) & 0777;
		} catch (NoSuchFileException error) {
			return null;
		}
	}

	private static void writeSyncedTemporaryFile(Path filePath, String contents, @Nullable Integer mode)
			throws IOException {
		byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
		try (FileChannel channel = FileChannel.open(
				filePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			if (mode != null) {
				// fs.chmod after open, so the exact bits win over the process umask.
				Files.setAttribute(filePath, "unix:mode", mode);
			}
			channel.force(true);
		}
	}

	private static void syncExistingFile(Path filePath) throws IOException {
		try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
	}

	private static void syncParentDirectory(Path parentDir) throws IOException {
		if (java.io.File.separatorChar == '\\') {
			return;
		}
		try (FileChannel channel = FileChannel.open(parentDir, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (AccessDeniedException error) {
			// EACCES/EPERM: unsupported directory sync, same as the TS ignore list.
		} catch (FileSystemException error) {
			if (!isUnsupportedDirectorySync(error)) {
				throw error;
			}
		}
	}

	private static boolean isUnsupportedDirectorySync(FileSystemException error) {
		String reason = error.getReason();
		if (reason == null) {
			return false;
		}
		for (String message : UNSUPPORTED_DIRECTORY_SYNC_MESSAGES) {
			if (reason.contains(message)) {
				return true;
			}
		}
		return false;
	}

	private static void preservePreviousGeneration(
			Path targetPath, Path backupPath, Path backupTemporaryPath) throws IOException {
		try {
			// COPY_ATTRIBUTES keeps the previous generation's permission bits, as fs.copyFile does.
			Files.copy(targetPath, backupTemporaryPath, StandardCopyOption.COPY_ATTRIBUTES);
		} catch (NoSuchFileException error) {
			Files.deleteIfExists(backupPath);
			return;
		}

		syncExistingFile(backupTemporaryPath);
		// rename(2) semantics: replace a file, but fail when the target is a directory (fs.rename).
		Files.move(backupTemporaryPath, backupPath, StandardCopyOption.ATOMIC_MOVE);
	}

	private static void commitProjectFile(Path projectPath, String contents) {
		try {
			Path targetPath = projectPath.toAbsolutePath().normalize();
			Path parentDir = targetPath.getParent();
			if (parentDir == null) {
				// A project file always lives in a directory; only a filesystem root has no parent.
				throw new IllegalArgumentException(
						"Project path has no parent directory: " + projectPath);
			}
			Path backupPath = getProjectBackupPath(targetPath);
			Path temporaryPath = createTemporaryPath(parentDir, "project");
			Path backupTemporaryPath = createTemporaryPath(parentDir, "backup");
			Integer existingMode = getExistingFileMode(targetPath);

			try {
				writeSyncedTemporaryFile(temporaryPath, contents, existingMode);
				preservePreviousGeneration(targetPath, backupPath, backupTemporaryPath);
				Files.move(temporaryPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
				syncParentDirectory(parentDir);
			} finally {
				deleteQuietly(temporaryPath);
				deleteQuietly(backupTemporaryPath);
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not save project file " + projectPath + ": " + error);
			throw new UncheckedIOException(error);
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// The TS finally block swallows cleanup failures too.
		}
	}
}
