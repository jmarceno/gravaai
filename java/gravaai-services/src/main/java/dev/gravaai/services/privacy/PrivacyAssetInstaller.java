package dev.gravaai.services.privacy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.IntConsumer;

import dev.gravaai.services.meeting.ports.FileDownloader;

/**
 * Downloads the GLiNER asset on first use, verifying every file's pinned SHA-256 before it is
 * moved into place (the {@link dev.gravaai.services.meeting.AssetInstaller} pattern: download to
 * {@code <name>.download}, verify, then an atomic move).
 *
 * <p>A file that is already present and hashes correctly is skipped, so a retry after a dropped
 * connection only fetches what is missing. Nothing is left behind that the detector could mistake
 * for a complete model: the directory is only usable once every file passed its hash.</p>
 */
public final class PrivacyAssetInstaller {

	private final FileDownloader downloader;
	private final PrivacyAssets assets;

	public PrivacyAssetInstaller(FileDownloader downloader, PrivacyAssets assets) {
		this.downloader = downloader;
		this.assets = assets;
	}

	/** True when the model directory holds a verified, complete asset set. */
	public boolean isInstalled() {
		return assets.isInstalled();
	}

	/**
	 * Installs whatever is missing and returns the model directory.
	 *
	 * <p>Concurrent callers are serialised so the asset is downloaded once. Progress is reported
	 * per file (0-100 of the whole set), weighted by file size so the 580 MB graph dominates.</p>
	 *
	 * @param onProgress 0-100 install progress; called from the calling thread
	 */
	public synchronized Path ensure(IntConsumer onProgress) {
		Path modelDir = assets.glinerModelDir();
		try {
			Files.createDirectories(modelDir);
		} catch (IOException error) {
			throw new UncheckedIOException("could not create " + modelDir, error);
		}
		long totalBytes = assets.files().stream().mapToLong(PrivacyAssets.AssetFile::sizeBytes).sum();
		long uploadedBytes = 0;
		for (PrivacyAssets.AssetFile file : assets.files()) {
			Path target = file.path(modelDir);
			if (Files.isRegularFile(target) && file.sha256().equals(sha256(target))) {
				uploadedBytes += file.sizeBytes();
				report(onProgress, uploadedBytes, totalBytes);
				continue;
			}
			download(file, target, onProgress, uploadedBytes, totalBytes);
			uploadedBytes += file.sizeBytes();
			report(onProgress, uploadedBytes, totalBytes);
		}
		onProgress.accept(100);
		return modelDir;
	}

	private void download(PrivacyAssets.AssetFile file, Path target, IntConsumer onProgress,
			long completedBytes, long totalBytes) {
		Path partial = target.resolveSibling(target.getFileName() + ".download");
		try {
			Files.deleteIfExists(partial);
			downloader.download(URI.create(file.url()), partial, percent -> {
				// Fold this file's own percentage into the whole-set percentage.
				long thisFile = file.sizeBytes() * percent / 100;
				report(onProgress, completedBytes + thisFile, totalBytes);
			});
			String actual = sha256(partial);
			if (!actual.equals(file.sha256())) {
				Files.deleteIfExists(partial);
				throw new IllegalStateException(file.fileName() + " failed its checksum (got "
						+ actual + ").");
			}
			Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException error) {
			// A dropped connection mid-download must not leave a partial behind: the next attempt
			// would have to re-fetch it anyway, and the class contract says nothing is left over.
			deleteQuietly(partial);
			throw new UncheckedIOException("could not download " + file.fileName(), error);
		} catch (InterruptedException interrupted) {
			deleteQuietly(partial);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("download of " + file.fileName() + " was interrupted",
					interrupted);
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// The download failed already; a stuck temp file is not worth masking that failure.
		}
	}

	private static void report(IntConsumer onProgress, long completed, long total) {
		if (total <= 0) {
			return;
		}
		onProgress.accept(Math.clamp((int) (completed * 100 / total), 0, 100));
	}

	/** The SHA-256 of a file, lowercase hex. */
	static String sha256(Path file) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (var input = Files.newInputStream(file)) {
				byte[] buffer = new byte[1 << 16];
				int read;
				while ((read = input.read(buffer)) > 0) {
					digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException error) {
			throw new UncheckedIOException("could not hash " + file, error);
		} catch (NoSuchAlgorithmException error) {
			// SHA-256 is mandatory in every JRE.
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	/** Diagnostic: the model directory, for error messages and the download row. */
	public Path modelDir() {
		return assets.glinerModelDir();
	}
}
