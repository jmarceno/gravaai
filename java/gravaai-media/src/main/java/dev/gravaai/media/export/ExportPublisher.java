package dev.gravaai.media.export;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Publishes a finished export temp file to its destination (port of the
 * {@code finalize-exported-video}/{@code discard-exported-temp} semantics and
 * {@code exportSavePolicy.ts}, plan §5.10).
 *
 * <p>The encoder always writes a temp file; this class moves it into place (same directory, so the
 * rename is atomic) and optionally reveals it in the file manager.
 */
public final class ExportPublisher {

	private static final System.Logger LOG = System.getLogger("gravaai.export.publish");

	/** Where the finished file should go, and whether to reveal it afterwards. */
	public record Destination(Path path, boolean reveal) {}

	/** The published file. */
	public record PublishResult(Path path, long bytes) {}

	private final Consumer<Path> revealAction;

	/** @param revealAction platform reveal callback (for example {@code FileManager::reveal}); may be null */
	public ExportPublisher(Consumer<Path> revealAction) {
		this.revealAction = revealAction;
	}

	/** Reveals a file in the file manager (the platform reveal callback). */
	public void reveal(Path file) {
		if (revealAction == null) {
			return;
		}
		try {
			revealAction.accept(file);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING,
					"could not reveal the exported file: " + error.getMessage());
		}
	}

	/** Moves {@code tempFile} to the destination, honouring the overwrite policy. */
	public PublishResult publish(Path tempFile, Destination destination, boolean overwrite) {
		Objects.requireNonNull(tempFile, "tempFile");
		Objects.requireNonNull(destination, "destination");
		if (!Files.isRegularFile(tempFile)) {
			throw new ExportException("export temp file is missing: " + tempFile);
		}
		Path target = destination.path().toAbsolutePath().normalize();
		try {
			if (target.getParent() != null) {
				Files.createDirectories(target.getParent());
			}
			if (Files.exists(target)) {
				if (!overwrite) {
					throw new ExportException("destination already exists: " + target);
				}
				Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.move(tempFile, target);
			}
		} catch (FileAlreadyExistsException exists) {
			throw new ExportException("destination already exists: " + target, exists);
		} catch (FileSystemException crossDevice) {
			try {
				Files.copy(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
				Files.deleteIfExists(tempFile);
			} catch (IOException copyError) {
				throw new ExportException("could not publish the export: " + copyError.getMessage(),
						copyError);
			}
		} catch (IOException error) {
			throw new ExportException("could not publish the export: " + error.getMessage(), error);
		}
		long bytes;
		try {
			bytes = Files.size(target);
		} catch (IOException error) {
			bytes = 0;
		}
		if (destination.reveal() && revealAction != null) {
			try {
				revealAction.accept(target);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING,
						"could not reveal the exported file: " + error.getMessage());
			}
		}
		return new PublishResult(target, bytes);
	}
}
