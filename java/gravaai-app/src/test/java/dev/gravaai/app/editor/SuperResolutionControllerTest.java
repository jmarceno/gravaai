package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.services.meeting.ports.FileDownloader;
import dev.gravaai.services.superres.SuperResAssets;
import dev.gravaai.services.superres.SuperResInstaller;
import dev.gravaai.ui.editor.SuperResolutionUiService;
import javafx.application.Platform;

/**
 * The controller's published state: an untouched machine never probes the GPU, and once the
 * feature matters the provider row appears without disturbing the install state or the toggle.
 *
 * <p>The probe itself is the real one, but on a machine without the model the controller must not
 * reach it at all — that is the point of the first test. With the model stubbed as installed the
 * probe runs; whatever it finds (WebGPU or the CPU fallback) must land in the state and survive
 * later mutations.</p>
 */
class SuperResolutionControllerTest {

	/** A downloader the tests never use: no install is triggered here. */
	private static final FileDownloader NO_DOWNLOAD = (uri, target, progress) -> {
		throw new AssertionError("no download expected");
	};

	@BeforeAll
	static void startToolkit() {
		// publish() hops to the FX thread; without a toolkit it throws before the state lands.
		try {
			Platform.startup(() -> { });
		} catch (IllegalStateException alreadyRunning) {
			// Another test class in this JVM started the toolkit first.
		}
	}

	private static SuperResolutionController controller(Path userDataDir) {
		return new SuperResolutionController(null, new SuperResAssets(userDataDir),
				new SuperResInstaller(NO_DOWNLOAD, new SuperResAssets(userDataDir)));
	}

	/** Puts a sparse model file of the pinned size in place so {@code isInstalled()} is true. */
	private static void installSparseModel(Path userDataDir) throws Exception {
		SuperResAssets assets = new SuperResAssets(userDataDir);
		Files.createDirectories(assets.modelDir());
		// isInstalled() checks the size, so a sparse file costs no disk and no download.
		try (var channel = java.nio.channels.FileChannel.open(assets.modelFile(),
				java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.WRITE,
				java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
			channel.position(SuperResAssets.MODEL_SIZE_BYTES - 1);
			channel.write(java.nio.ByteBuffer.wrap(new byte[1]));
		}
		// A hole-punched file: the sparse tail holds zeros, which is all isInstalled() checks.
		assertEquals(SuperResAssets.MODEL_SIZE_BYTES, Files.size(assets.modelFile()));
	}

	@Test
	@DisplayName("an untouched machine publishes no provider and never touches the GPU")
	void untouchedMachineDoesNotProbe(@TempDir Path userDataDir) {
		SuperResolutionController controller = controller(userDataDir);
		controller.refresh();

		SuperResolutionUiService.State state = controller.service().state();
		assertFalse(state.enabled());
		assertFalse(state.modelInstalled());
		assertTrue(state.provider().isEmpty(), "the provider is probed only when exports could use it");
		assertTrue(state.providerDetail().isEmpty());
	}

	@Test
	@DisplayName("the provider row survives toggles and reports a self-consistent state")
	void providerSurvivesMutations() throws Exception {
		Path profile = java.nio.file.Files.createTempDirectory("gravaai-superres-state");
		try {
			installSparseModel(profile);
			SuperResolutionController controller = controller(profile);
			var published = new CopyOnWriteArrayList<SuperResolutionUiService.State>();
			var service = controller.service();
			service.addListener(published::add);

			controller.refresh();
			// The probe may be asynchronous (a virtual thread); wait briefly for the row.
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
			while (service.state().provider().isEmpty() && System.nanoTime() < deadline) {
				Thread.sleep(20);
			}
			assertTrue(service.state().provider().isPresent(),
					"an installed model must get a provider row");
			String provider = service.state().provider().get();
			assertTrue(provider.equals("CPU") || provider.startsWith("WebGPU"),
					"unexpected provider label: " + provider);
			assertEquals(!provider.equals("CPU"), service.state().hardwareAccelerated());
			if (!service.state().hardwareAccelerated()) {
				assertTrue(service.state().providerDetail().isPresent(),
						"the CPU fallback must say why it was chosen");
			}

			// A toggle flips only the toggle: the provider is a machine property and stays.
			service.setEnabled(true);
			assertTrue(service.state().enabled());
			assertEquals(java.util.Optional.of(provider), service.state().provider());

			service.setEnabled(false);
			assertFalse(service.state().enabled());
			assertEquals(java.util.Optional.of(provider), service.state().provider());
			assertFalse(published.isEmpty(), "the panel must have been told about each change");
		} finally {
			try (var stream = java.nio.file.Files.walk(profile)) {
				stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
					try {
						java.nio.file.Files.deleteIfExists(path);
					} catch (java.io.IOException ignored) {
						// Best-effort cleanup of a temporary profile.
					}
				});
			}
		}
	}
}
