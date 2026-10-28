package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.services.meeting.MeetingAssets.MeetingAsset;
import dev.gravaai.services.meeting.ports.FileDownloader;
import dev.gravaai.services.meeting.ports.ProcessRunnerPort;

/** Asset download/verify/extract behaviour (electron/ipc/meeting/assets.ts). */
class AssetInstallerTest {

	private static final class Downloader implements FileDownloader {
		final Map<String, byte[]> files = new java.util.LinkedHashMap<>();
		int downloads;

		@Override
		public void download(URI uri, Path target, IntConsumer onProgress) throws java.io.IOException {
			downloads++;
			byte[] bytes = files.get(uri.toString());
			if (bytes == null) {
				throw new java.io.IOException("no fake for " + uri);
			}
			Files.write(target, bytes);
			onProgress.accept(100);
		}
	}

	private static final class Processes implements ProcessRunnerPort {
		final List<List<String>> calls = new ArrayList<>();
		final Deque<ProcessResult> results = new ArrayDeque<>();

		@Override
		public ProcessResult run(List<String> command, java.time.Duration timeout,
				Map<String, String> environment) {
			calls.add(command);
			ProcessResult result = results.poll();
			return result == null ? new ProcessResult(0, "", "") : result;
		}

		@Override
		public RunningProcess spawn(List<String> command, Map<String, String> environment,
				Consumer<String> stderrLines) {
			throw new UnsupportedOperationException();
		}
	}

	private static MeetingAsset fileAsset(Path path, byte[] content) throws Exception {
		Files.createDirectories(path.getParent());
		Files.write(path.resolveSibling("source.bin"), content);
		String hash = AssetInstaller.sha256(path.resolveSibling("source.bin"));
		return new MeetingAsset("test", "Test asset", "https://example.test/a.bin", hash,
				content.length, path, null);
	}

	@Test
	@DisplayName("downloads a plain file once and verifies its checksum")
	void downloadsPlainFileOnce(@TempDir Path directory) throws Exception {
		byte[] bytes = "asset-bytes".getBytes();
		Path target = directory.resolve("models/asset.bin");
		MeetingAsset asset = fileAsset(target, bytes);
		Downloader downloader = new Downloader();
		downloader.files.put(asset.url(), bytes);
		Processes processes = new Processes();
		AssetInstaller installer = new AssetInstaller(downloader, processes);
		assertFalse(installer.isInstalled(asset));
		assertEquals(target, installer.ensure(asset, percent -> {
		}));
		assertTrue(Files.isRegularFile(target));
		assertEquals(1, downloader.downloads);
		assertEquals(target, installer.ensure(asset, percent -> {
		}));
		assertEquals(1, downloader.downloads);
	}

	@Test
	@DisplayName("rejects a download whose checksum does not match")
	void rejectsChecksumMismatch(@TempDir Path directory) throws Exception {
		Path target = directory.resolve("models/bad.bin");
		MeetingAsset asset = fileAsset(target, "real".getBytes());
		Downloader downloader = new Downloader();
		downloader.files.put(asset.url(), "tampered".getBytes());
		AssetInstaller installer = new AssetInstaller(downloader, new Processes());
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> installer.ensure(asset, percent -> {
				}));
		assertTrue(error.getMessage().startsWith("Test asset failed its checksum"));
		assertFalse(Files.exists(Path.of(asset.path() + ".download")));
		assertFalse(Files.exists(target));
	}

	@Test
	@DisplayName("extracts archives and writes the installed marker")
	void extractsArchivesAndWritesMarker(@TempDir Path directory) throws Exception {
		byte[] bytes = "archive".getBytes();
		Path target = directory.resolve("llama/b11829");
		MeetingAsset asset = new MeetingAsset("arch", "Archive", "https://example.test/a.tar.gz",
				AssetInstaller.sha256(Files.write(pathWithContent(directory), bytes)), bytes.length,
				target, "tar.gz");
		Downloader downloader = new Downloader();
		downloader.files.put(asset.url(), bytes);
		Processes processes = new Processes();
		AssetInstaller installer = new AssetInstaller(downloader, processes);
		installer.ensure(asset, percent -> {
		});
		assertEquals(List.of("tar", "-xzf", asset.path() + ".download", "-C",
				asset.path().toString()), processes.calls.get(0));
		assertTrue(Files.isRegularFile(MeetingAssets.installedMarker(asset)));
		assertEquals(asset.sha256(), Files.readString(MeetingAssets.installedMarker(asset)));
		assertFalse(Files.exists(Path.of(asset.path() + ".download")));
	}

	private static Path pathWithContent(Path directory) throws Exception {
		Path source = directory.resolve("source.bin");
		Files.write(source, "archive".getBytes());
		return source;
	}

	@Test
	@DisplayName("removes installed assets")
	void removesInstalledAssets(@TempDir Path directory) throws Exception {
		Path target = directory.resolve("models/removable.bin");
		Files.createDirectories(target.getParent());
		Files.writeString(target, "x");
		MeetingAsset asset = new MeetingAsset("rm", "Removable", "https://example.test/r",
				"00", 1, target, null);
		AssetInstaller installer = new AssetInstaller(new Downloader(), new Processes());
		assertTrue(installer.isInstalled(asset));
		installer.remove(asset);
		assertFalse(Files.exists(target));
	}
}
