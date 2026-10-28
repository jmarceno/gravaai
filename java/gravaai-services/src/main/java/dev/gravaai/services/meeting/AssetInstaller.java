package dev.gravaai.services.meeting;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import dev.gravaai.services.meeting.MeetingAssets.MeetingAsset;
import dev.gravaai.services.meeting.ports.FileDownloader;
import dev.gravaai.services.meeting.ports.ProcessRunnerPort;

/**
 * Downloads, verifies and extracts meeting assets exactly like
 * electron/ipc/meeting/assets.ts: nothing is installed twice, a checksum mismatch is an error,
 * archives extract with {@code tar -xzf} and get a {@code .installed} marker.
 */
public final class AssetInstaller {

	private static final Duration EXTRACT_TIMEOUT = Duration.ofMinutes(10);

	private final FileDownloader downloader;
	private final ProcessRunnerPort processes;

	public AssetInstaller(FileDownloader downloader, ProcessRunnerPort processes) {
		this.downloader = downloader;
		this.processes = processes;
	}

	public boolean isInstalled(MeetingAsset asset) {
		Path target = asset.isArchive()
				? MeetingAssets.installedMarker(asset)
				: asset.path();
		return Files.isRegularFile(target) || Files.exists(target);
	}

	/**
	 * Installs the asset when missing and returns its path. Concurrent callers are serialised, so
	 * the same asset is downloaded once.
	 */
	public synchronized Path ensure(MeetingAsset asset, IntConsumer onProgress) {
		if (isInstalled(asset)) {
			return asset.path();
		}
		Path download = Path.of(asset.path() + ".download");
		try {
			Path parent = asset.path().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.deleteIfExists(download);
			downloader.download(URI.create(asset.url()), download, onProgress);
			String actual = sha256(download);
			if (!actual.equals(asset.sha256())) {
				throw new IllegalStateException(
						asset.label() + " failed its checksum (got " + actual + ").");
			}
			if (asset.isArchive()) {
				FileTrees.deleteRecursively(asset.path());
				Files.createDirectories(asset.path());
				ProcessRunnerPort.ProcessResult result = processes.run(
						List.of("tar", "-xzf", download.toString(), "-C", asset.path().toString()),
						EXTRACT_TIMEOUT, Map.of());
				if (result.exitCode() != 0) {
					throw new IllegalStateException("Could not extract " + asset.label() + ": "
							+ Texts.lastLines(result.stderr(), 3));
				}
				Files.writeString(MeetingAssets.installedMarker(asset), asset.sha256(),
						StandardCharsets.UTF_8);
				Files.deleteIfExists(download);
			} else {
				Files.move(download, asset.path(), StandardCopyOption.REPLACE_EXISTING);
			}
			return asset.path();
		} catch (IOException error) {
			deleteQuietly(download);
			throw new UncheckedIOException(error);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			deleteQuietly(download);
			throw new IllegalStateException("Interrupted while installing " + asset.label(),
					interrupted);
		} catch (RuntimeException error) {
			deleteQuietly(download);
			throw error;
		}
	}

	public void remove(MeetingAsset asset) {
		FileTrees.deleteRecursively(asset.path());
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// Best effort cleanup.
		}
	}

	static String sha256(Path file) {
		try (InputStream input = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is not available.", error);
		}
	}
}
