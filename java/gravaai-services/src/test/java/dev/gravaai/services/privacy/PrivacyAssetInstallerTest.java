package dev.gravaai.services.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.services.meeting.ports.FileDownloader;

/**
 * The first-use download is the security-sensitive half of auto masking: it fetches hundreds of
 * megabytes over the network and the detector then trusts whatever lands on disk. These tests pin
 * the three promises {@link PrivacyAssetInstaller} makes — a file is only moved into place after
 * its pinned hash matches, a mismatch is fatal, and no partial file survives a failure.
 *
 * <p>The 580 MB graph is never downloaded here: the downloader is faked and the tests assert on
 * the first file, which aborts the whole set the moment it fails. That keeps them fast while still
 * covering the logic that matters.</p>
 */
class PrivacyAssetInstallerTest {

	/** Serves controlled bytes per URL and records which targets were written. */
	private static final class FakeDownloader implements FileDownloader {

		private final Map<String, byte[]> contents = new HashMap<>();
		private final List<Path> requested = new ArrayList<>();
		private IOException failure;

		@Override
		public void download(URI uri, Path target, IntConsumer progress)
				throws IOException, InterruptedException {
			requested.add(target);
			if (failure != null) {
				// A real downloader leaves a partial behind when the connection drops mid-transfer.
				Files.write(target, new byte[] {1, 2, 3});
				throw failure;
			}
			byte[] bytes = contents.get(uri.toString());
			if (bytes == null) {
				throw new IOException("no fixture for " + uri);
			}
			Files.write(target, bytes);
			progress.accept(100);
		}
	}

	private static FakeDownloader downloaderServing(PrivacyAssets assets, byte[] bytes) {
		FakeDownloader downloader = new FakeDownloader();
		downloader.contents.put(assets.files().getFirst().url(), bytes);
		return downloader;
	}

	private static List<String> remainingDownloadFiles(Path modelDir) throws IOException {
		try (Stream<Path> listing = Files.list(modelDir)) {
			return listing.map(path -> path.getFileName().toString())
					.filter(name -> name.endsWith(".download"))
					.toList();
		}
	}

	@Test
	@DisplayName("a file whose bytes do not match the pin is fatal and never installed")
	void checksumMismatchIsFatal(@TempDir Path root) throws Exception {
		PrivacyAssets assets = new PrivacyAssets(root);
		FakeDownloader downloader = downloaderServing(assets,
				"these are not the pinned bytes".getBytes(StandardCharsets.UTF_8));
		PrivacyAssetInstaller installer = new PrivacyAssetInstaller(downloader, assets);

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> installer.ensure(percent -> { }));

		assertTrue(error.getMessage().contains("checksum"),
				"the failure must name the checksum: " + error.getMessage());
		Path target = assets.files().getFirst().path(assets.glinerModelDir());
		assertFalse(Files.exists(target), "unverified bytes must never be moved into place");
		assertEquals(List.of(), remainingDownloadFiles(assets.glinerModelDir()),
				"a rejected download leaves no partial file");
	}

	@Test
	@DisplayName("a dropped connection leaves no partial file behind")
	void failedDownloadLeavesNoPartial(@TempDir Path root) throws Exception {
		PrivacyAssets assets = new PrivacyAssets(root);
		FakeDownloader downloader = new FakeDownloader();
		downloader.failure = new IOException("connection reset mid-transfer");
		PrivacyAssetInstaller installer = new PrivacyAssetInstaller(downloader, assets);

		assertThrows(RuntimeException.class, () -> installer.ensure(percent -> { }));

		assertEquals(List.of(), remainingDownloadFiles(assets.glinerModelDir()),
				"an interrupted download must clean up its partial file");
	}

	@Test
	@DisplayName("an existing file that fails its hash is re-fetched, not trusted")
	void corruptExistingFileIsReplaced(@TempDir Path root) throws Exception {
		PrivacyAssets assets = new PrivacyAssets(root);
		Path modelDir = assets.glinerModelDir();
		Files.createDirectories(modelDir);
		// The corrupt-file case that matters: the file exists, so a naive existence check would
		// call the asset installed and hand the detector a broken model on every run.
		Files.writeString(assets.files().getFirst().path(modelDir), "truncated download");

		FakeDownloader downloader = new FakeDownloader();
		downloader.failure = new IOException("offline");
		PrivacyAssetInstaller installer = new PrivacyAssetInstaller(downloader, assets);

		assertThrows(RuntimeException.class, () -> installer.ensure(percent -> { }));
		assertEquals(1, downloader.requested.size(),
				"an unverified existing file must be re-downloaded, not skipped");
	}

	@Test
	@DisplayName("the asset only counts as installed when every file is present at its pinned size")
	void installedRequiresEveryFile(@TempDir Path root) throws Exception {
		PrivacyAssets assets = new PrivacyAssets(root);
		assertFalse(assets.isInstalled(), "nothing is installed in an empty profile");
		Path modelDir = assets.glinerModelDir();
		Files.createDirectories(modelDir);
		for (PrivacyAssets.AssetFile file : assets.files()) {
			// Sparse files at the pinned sizes: the graph is 580 MB and the check looks at size.
			try (var channel = Files.newByteChannel(file.path(modelDir),
					java.nio.file.StandardOpenOption.CREATE,
					java.nio.file.StandardOpenOption.WRITE)) {
				channel.position(file.sizeBytes() - 1);
				channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
			}
		}
		assertTrue(assets.isInstalled(), "every file at its pinned size must read as installed");
		// Dropping one file's last byte makes it truncated, which must read as not installed.
		PrivacyAssets.AssetFile first = assets.files().getFirst();
		try (var channel = Files.newByteChannel(first.path(modelDir),
				java.nio.file.StandardOpenOption.WRITE)) {
			channel.truncate(first.sizeBytes() - 1);
		}
		assertFalse(assets.isInstalled(), "a truncated file must read as missing");
	}

	@Test
	@DisplayName("a truncated file does not count as installed, so a re-fetch can heal it")
	void truncatedFileIsNotInstalled(@TempDir Path root) throws Exception {
		PrivacyAssets assets = new PrivacyAssets(root);
		Path modelDir = assets.glinerModelDir();
		Files.createDirectories(modelDir);
		// Every file present but at the wrong size: exactly what a crash or a full disk leaves.
		for (PrivacyAssets.AssetFile file : assets.files()) {
			Files.writeString(file.path(modelDir), "truncated");
		}
		assertFalse(assets.isInstalled(),
				"a file at the wrong size must read as missing, not as installed");
	}

	@Test
	@DisplayName("sha256 matches the known digest for known bytes")
	void sha256IsKnownAnswer(@TempDir Path root) throws Exception {
		Path file = root.resolve("abc.txt");
		Files.writeString(file, "abc");
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
				PrivacyAssetInstaller.sha256(file), "sha256(\"abc\") is a published test vector");
	}
}
