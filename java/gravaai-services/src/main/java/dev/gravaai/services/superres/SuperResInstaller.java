package dev.gravaai.services.superres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.IntConsumer;

import dev.gravaai.services.meeting.ports.FileDownloader;

/**
 * Downloads the super-resolution model on first use, verifying its pinned SHA-256 before the file
 * is moved into place (the {@link dev.gravaai.services.privacy.PrivacyAssetInstaller} pattern:
 * download to {@code <name>.download}, verify, then an atomic move).
 *
 * <p>One 4.9 MB file, so the whole install is a single request and the progress callback maps
 * straight onto it. A file already present and hashing correctly is skipped, so a retry after a
 * dropped connection costs nothing.</p>
 */
public final class SuperResInstaller {

	private final FileDownloader downloader;
	private final SuperResAssets assets;

	public SuperResInstaller(FileDownloader downloader, SuperResAssets assets) {
		this.downloader = downloader;
		this.assets = assets;
	}

	/** True when the model directory holds the verified model. */
	public boolean isInstalled() {
		return assets.isInstalled();
	}

	/**
	 * Installs the model when missing and returns its path. Concurrent callers are serialised so
	 * the asset is downloaded once.
	 *
	 * @param onProgress 0-100 install progress; called from the calling thread
	 */
	public synchronized Path ensure(IntConsumer onProgress) {
		Path target = assets.modelFile();
		try {
			Files.createDirectories(assets.modelDir());
		} catch (IOException error) {
			throw new UncheckedIOException("could not create " + assets.modelDir(), error);
		}
		for (SuperResAssets.AssetFile file : assets.files()) {
			Path path = file.path(assets.modelDir());
			if (Files.isRegularFile(path) && file.sha256().equals(sha256(path))) {
				continue;
			}
			download(file, path, onProgress);
		}
		onProgress.accept(100);
		return target;
	}

	/** Deletes the installed model (the UI's Remove action). */
	public synchronized void remove() {
		try {
			Files.deleteIfExists(assets.modelFile());
		} catch (IOException error) {
			throw new UncheckedIOException("could not remove " + assets.modelFile(), error);
		}
	}

	private void download(SuperResAssets.AssetFile file, Path target, IntConsumer onProgress) {
		Path partial = target.resolveSibling(target.getFileName() + ".download");
		try {
			Files.deleteIfExists(partial);
			downloader.download(URI.create(file.url()), partial, onProgress);
			String actual = sha256(partial);
			if (!actual.equals(file.sha256())) {
				Files.deleteIfExists(partial);
				throw new IllegalStateException(file.fileName() + " failed its checksum (got "
						+ actual + ").");
			}
			Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException error) {
			deleteQuietly(partial);
			throw new UncheckedIOException("could not download " + file.fileName(), error);
		} catch (InterruptedException interrupted) {
			deleteQuietly(partial);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("download of " + file.fileName() + " was interrupted",
					interrupted);
		} catch (RuntimeException error) {
			deleteQuietly(partial);
			throw error;
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// The download failed already; a stuck temp file is not worth masking that failure.
		}
	}

	/** The SHA-256 of a file, lowercase hex. */
	static String sha256(Path file) {
		try (var input = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 16];
			int read;
			while ((read = input.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException error) {
			throw new UncheckedIOException("could not hash " + file, error);
		} catch (NoSuchAlgorithmException error) {
			// SHA-256 is mandatory in every JRE.
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}
}
