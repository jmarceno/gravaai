package dev.gravaai.services.recording.ports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.platform.compositor.CaptureBackend;
import dev.gravaai.platform.compositor.CursorBackend;

/**
 * The compositor-aware environment must be invisible on COSMIC (same helper as before) and must
 * resolve the portal helper on portal-only desktops.
 */
class CompositorRecordingEnvironmentTest {

	/** Records which members are asked for, so delegation can be asserted. */
	private static final class RecordingDelegate implements RecordingEnvironment {

		private final Path capture;
		private final Path cursor;

		RecordingDelegate(Path capture, Path cursor) {
			this.capture = capture;
			this.cursor = cursor;
		}

		@Override
		public Path captureHelper() {
			return capture;
		}

		@Override
		public Path cursorHelper() {
			return cursor;
		}

		@Override
		public Path ffmpeg() {
			return Path.of("/ffmpeg");
		}

		@Override
		public Path rnnoiseModel() {
			return null;
		}

		@Override
		public Path command(String name) throws IOException {
			return Path.of("/bin").resolve(name);
		}
	}

	private static CompositorRecordingEnvironment environment(
			RecordingEnvironment delegate, String desktop, Path nativeDir) {
		Map<String, String> env = wayland(desktop);
		return new CompositorRecordingEnvironment(
				delegate, CaptureBackend.detect(env), CursorBackend.detect(env), nativeDir);
	}

	@Test
	@DisplayName("GNOME captures through the portal and reads the pointer through its shell extension bridge")
	void gnomeUsesTheShellExtensionBridge(@TempDir Path nativeDir) throws IOException {
		Path cosmic = Files.createFile(nativeDir.resolve("gravaai-wayland-capture"));
		Path portal = Files.createFile(nativeDir.resolve("gravaai-portal-capture"));
		Files.createFile(nativeDir.resolve("gravaai-kwin-cursor"));
		Path gnomeCursor = Files.createFile(nativeDir.resolve("gravaai-gnome-cursor"));
		Path cursor = Path.of("/cosmic-cursor");
		CompositorRecordingEnvironment environment =
				environment(new RecordingDelegate(cosmic, cursor), "GNOME", nativeDir);

		assertEquals(portal, environment.captureHelper());
		assertEquals(CursorBackend.GNOME_BRIDGE, environment.cursorBackend());
		assertEquals(gnomeCursor, environment.cursorHelper());
	}

	private static Map<String, String> wayland(String desktop) {
		Map<String, String> env = new HashMap<>();
		env.put("XDG_SESSION_TYPE", "wayland");
		env.put("XDG_CURRENT_DESKTOP", desktop);
		return env;
	}

	@Test
	@DisplayName("COSMIC delegates the capture helper exactly as before")
	void cosmicDelegatesCaptureHelper(@TempDir Path nativeDir) throws IOException {
		Path cosmic = Files.createFile(nativeDir.resolve("gravaai-wayland-capture"));
		RecordingDelegate delegate = new RecordingDelegate(cosmic, Path.of("/cursor"));
		CompositorRecordingEnvironment environment =
				environment(delegate, "COSMIC", nativeDir);

		assertEquals(CaptureBackend.EXT_IMAGE_COPY, environment.backend());
		assertSame(cosmic, environment.captureHelper());
	}

	@Test
	@DisplayName("KDE resolves the portal helper from the native payload")
	void kdeUsesPortalHelper(@TempDir Path nativeDir) throws IOException {
		Path cosmic = Files.createFile(nativeDir.resolve("gravaai-wayland-capture"));
		Path portal = Files.createFile(nativeDir.resolve("gravaai-portal-capture"));
		RecordingDelegate delegate = new RecordingDelegate(cosmic, Path.of("/cursor"));
		CompositorRecordingEnvironment environment =
				environment(delegate, "KDE", nativeDir);

		assertEquals(CaptureBackend.PORTAL, environment.backend());
		assertEquals(portal, environment.captureHelper());
	}

	@Test
	@DisplayName("a missing portal helper fails loudly instead of capturing a different surface")
	void missingPortalHelperFails(@TempDir Path nativeDir) throws IOException {
		Path cosmic = Files.createFile(nativeDir.resolve("gravaai-wayland-capture"));
		RecordingDelegate delegate = new RecordingDelegate(cosmic, Path.of("/cursor"));
		CompositorRecordingEnvironment environment =
				environment(delegate, "KDE", nativeDir);

		IllegalStateException error =
				assertThrows(IllegalStateException.class, environment::captureHelper);
		assertTrue(error.getMessage().contains("gravaai-portal-capture"), error.getMessage());
	}

	@Test
	@DisplayName("on a portal compositor the cursor helper is the KWin scripting bridge")
	void portalUsesKwinCursorHelper(@TempDir Path nativeDir) throws IOException {
		Path cosmic = Files.createFile(nativeDir.resolve("gravaai-wayland-capture"));
		Files.createFile(nativeDir.resolve("gravaai-portal-capture"));
		Path kwinCursor = Files.createFile(nativeDir.resolve("gravaai-kwin-cursor"));
		RecordingDelegate delegate = new RecordingDelegate(cosmic, Path.of("/cosmic-cursor"));
		CompositorRecordingEnvironment environment =
				environment(delegate, "KDE", nativeDir);

		// ext-image-copy-capture cursor sessions do not exist on KWin, so the COSMIC helper
		// would report no position at all; the scripting bridge is what makes the pointer real.
		assertEquals(kwinCursor, environment.cursorHelper());
	}

	@Test
	@DisplayName("COSMIC keeps the ext-image-copy-capture cursor helper")
	void cosmicKeepsItsCursorHelper(@TempDir Path nativeDir) throws IOException {
		Path cosmic = Files.createFile(nativeDir.resolve("gravaai-wayland-capture"));
		Path cursor = Files.createFile(nativeDir.resolve("gravaai-wayland-cursor"));
		RecordingDelegate delegate = new RecordingDelegate(cosmic, cursor);
		CompositorRecordingEnvironment environment =
				environment(delegate, "COSMIC", nativeDir);

		assertEquals(cursor, environment.cursorHelper());
	}

	@Test
	@DisplayName("everything except the helpers is delegated unchanged")
	void otherMembersDelegate(@TempDir Path nativeDir) throws IOException {
		Path cosmic = Files.createFile(nativeDir.resolve("gravaai-wayland-capture"));
		Files.createFile(nativeDir.resolve("gravaai-portal-capture"));
		Files.createFile(nativeDir.resolve("gravaai-kwin-cursor"));
		RecordingDelegate delegate = new RecordingDelegate(cosmic, Path.of("/cursor"));
		CompositorRecordingEnvironment environment =
				environment(delegate, "KDE", nativeDir);

		assertEquals(Path.of("/ffmpeg"), environment.ffmpeg());
		assertEquals(Path.of("/bin/parec"), environment.command("parec"));
	}
}
