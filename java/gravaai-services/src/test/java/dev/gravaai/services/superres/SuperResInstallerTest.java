package dev.gravaai.services.superres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.services.meeting.ports.FileDownloader;

/**
 * The first-use install of the upscaler model: the asset is only usable once its bytes hash to the
 * pin, a mismatch never leaves a file the engine could load, and a retry does not re-download what
 * is already correct.
 */
class SuperResInstallerTest {

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
				// A real downloader leaves a partial behind when the connection drops.
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

	@Test
	@DisplayName("a correct model installs atomically and reports completion")
	void installsAndVerifies(@TempDir Path root) throws Exception {
		SuperResAssets assets = new SuperResAssets(root);
		SuperResAssets.AssetFile pin = assets.files().getFirst();
		byte[] model = "pinned-model-bytes".getBytes();
		// Serve the bytes at the URL, then pin their real hash so the fixture matches.
		FakeDownloader downloader = new FakeDownloader();
		downloader.contents.put(pin.url(), model);
		SuperResAssets withRealPin = assetsWithPin(root, model);

		SuperResInstaller installer = new SuperResInstaller(downloader, withRealPin);
		List<Integer> progress = new ArrayList<>();
		Path installed = installer.ensure(progress::add);

		assertEquals(withRealPin.modelFile(), installed);
		assertTrue(Files.isRegularFile(installed));
		assertTrue(installer.isInstalled());
		assertEquals(100, progress.getLast());
		// No temp file survived the move.
		try (var listing = Files.list(withRealPin.modelDir())) {
			assertTrue(listing.noneMatch(path -> path.getFileName().toString().endsWith(".download")));
		}
	}

	@Test
	@DisplayName("a hash mismatch is fatal and leaves nothing installed")
	void checksumMismatchIsFatal(@TempDir Path root) throws Exception {
		SuperResAssets assets = new SuperResAssets(root);
		SuperResAssets.AssetFile pin = assets.files().getFirst();
		FakeDownloader downloader = new FakeDownloader();
		downloader.contents.put(pin.url(), "not-the-model".getBytes());

		SuperResInstaller installer = new SuperResInstaller(downloader, assets);
		assertThrows(IllegalStateException.class, () -> installer.ensure(percent -> { }));
		assertFalse(installer.isInstalled());
		assertFalse(Files.exists(assets.modelFile()));
	}

	@Test
	@DisplayName("a dropped connection leaves no partial file behind")
	void failedDownloadLeavesNothing(@TempDir Path root) throws Exception {
		SuperResAssets assets = new SuperResAssets(root);
		FakeDownloader downloader = new FakeDownloader();
		downloader.failure = new IOException("connection reset");

		SuperResInstaller installer = new SuperResInstaller(downloader, assets);
		assertThrows(java.io.UncheckedIOException.class, () -> installer.ensure(percent -> { }));
		assertFalse(Files.exists(assets.modelFile()));
		try (var listing = Files.list(assets.modelDir())) {
			assertTrue(listing.noneMatch(path -> path.getFileName().toString().endsWith(".download")),
					"a partial download must be deleted");
		}
	}

	@Test
	@DisplayName("an installed model is not downloaded again")
	void installedModelIsSkipped(@TempDir Path root) throws Exception {
		String model = "already-installed-model";
		SuperResAssets assets = assetsWithPin(root, model.getBytes());
		Files.createDirectories(assets.modelDir());
		Files.writeString(assets.modelFile(), model);

		FakeDownloader downloader = new FakeDownloader();
		SuperResInstaller installer = new SuperResInstaller(downloader, assets);
		assertTrue(installer.isInstalled());
		assertEquals(assets.modelFile(), installer.ensure(percent -> { }));
		assertTrue(downloader.requested.isEmpty(), "the installed model must not be re-fetched");
	}

	@Test
	@DisplayName("a truncated file reads as missing, so the engine is never handed a partial model")
	void truncatedFileIsNotInstalled(@TempDir Path root) throws Exception {
		String model = "0123456789";
		SuperResAssets assets = assetsWithPin(root, model.getBytes());
		Files.createDirectories(assets.modelDir());
		Files.writeString(assets.modelFile(), model.substring(0, 4));
		assertFalse(assets.isInstalled(), "size is checked, not just existence");
	}

	@Test
	@DisplayName("remove deletes the model")
	void removeDeletesModel(@TempDir Path root) throws Exception {
		String model = "model-to-remove";
		SuperResAssets assets = assetsWithPin(root, model.getBytes());
		Files.createDirectories(assets.modelDir());
		Files.writeString(assets.modelFile(), model);
		SuperResInstaller installer = new SuperResInstaller(new FakeDownloader(), assets);

		installer.remove();
		assertFalse(installer.isInstalled());
		assertFalse(Files.exists(assets.modelFile()));
	}

	/**
	 * The production pins the real 4.9 MB model, so fixtures pin their own bytes and size through
	 * the package-private seam instead of downloading it.
	 */
	private static SuperResAssets assetsWithPin(Path root, byte[] bytes) {
		SuperResAssets production = new SuperResAssets(root);
		SuperResAssets.AssetFile real = production.files().getFirst();
		String sha256;
		try {
			sha256 = java.util.HexFormat.of().formatHex(
					java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
		return new SuperResAssets(root, List.of(new SuperResAssets.AssetFile(real.fileName(),
				real.url(), sha256, bytes.length)));
	}
}
