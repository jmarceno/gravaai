package dev.gravaai.platform.nativehelpers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeHelpersTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("resolves java/native/build in development")
	void resolvesDevelopmentPath() throws Exception {
		Path workingDirectory = Files.createDirectories(tempDir.resolve("checkout"));
		Files.createDirectories(workingDirectory.resolve("java/native/build"));

		Path nativeDir = NativeHelpers.resolveNativeDir(null, workingDirectory);

		assertEquals(workingDirectory.resolve("java/native/build"), nativeDir);
	}

	@Test
	@DisplayName("uses the transitional linux-x64 dev subdirectory when present")
	void resolvesDevelopmentArchDirectory() throws Exception {
		Path workingDirectory = Files.createDirectories(tempDir.resolve("checkout"));
		Path archDirectory = Files.createDirectories(workingDirectory.resolve("java/native/build/linux-x64"));

		assertEquals(archDirectory, NativeHelpers.resolveNativeDir(null, workingDirectory));
	}

	@Test
	@DisplayName("resolves <app image>/lib/native/linux-x64 when packaged")
	void resolvesPackagedPath() {
		Path appImage = tempDir.resolve("Gravaai-linux-x64");

		Path nativeDir = NativeHelpers.resolveNativeDir(appImage, tempDir);

		assertEquals(appImage.resolve("lib/native/linux-x64"), nativeDir);
	}

	@Test
	@DisplayName("derives the app image root from jpackage.app-path")
	void derivesAppImageDirFromJpackageAppPath() {
		Path appImage = tempDir.resolve("image");
		String previous = System.getProperty(NativeHelpers.APP_PATH_PROPERTY);
		try {
			System.setProperty(NativeHelpers.APP_PATH_PROPERTY, appImage.resolve("bin/gravaai").toString());
			assertEquals(appImage, NativeHelpers.appImageDir());
		} finally {
			if (previous == null) {
				System.clearProperty(NativeHelpers.APP_PATH_PROPERTY);
			} else {
				System.setProperty(NativeHelpers.APP_PATH_PROPERTY, previous);
			}
		}
	}

	@Test
	@DisplayName("has no app image directory in development")
	void noAppImageDirInDevelopment() {
		String previous = System.getProperty(NativeHelpers.APP_PATH_PROPERTY);
		try {
			System.clearProperty(NativeHelpers.APP_PATH_PROPERTY);
			assertNull(NativeHelpers.appImageDir());
		} finally {
			if (previous != null) {
				System.setProperty(NativeHelpers.APP_PATH_PROPERTY, previous);
			}
		}
	}

	@Test
	@DisplayName("honours the native directory override and finds helpers")
	void honoursNativeDirectoryOverride() throws Exception {
		Path nativeDir = Files.createDirectories(tempDir.resolve("native"));
		Path helper = Files.createFile(nativeDir.resolve("gravaai-wayland-capture"));
		String previous = System.getProperty(NativeHelpers.NATIVE_DIR_PROPERTY);
		try {
			System.setProperty(NativeHelpers.NATIVE_DIR_PROPERTY, nativeDir.toString());

			assertEquals(nativeDir, NativeHelpers.nativeDir());
			assertEquals(helper, NativeHelpers.path("gravaai-wayland-capture"));
			assertEquals(helper, NativeHelpers.existing("gravaai-wayland-capture").orElseThrow());
			assertTrue(NativeHelpers.existing("gravaai-wayland-cursor").isEmpty());
			assertEquals(helper, NativeHelpers.require("gravaai-wayland-capture"));
		} finally {
			if (previous == null) {
				System.clearProperty(NativeHelpers.NATIVE_DIR_PROPERTY);
			} else {
				System.setProperty(NativeHelpers.NATIVE_DIR_PROPERTY, previous);
			}
		}
	}

	@Test
	@DisplayName("require fails with a readable message when the payload is missing")
	void requireFailsForMissingHelper() throws Exception {
		Path nativeDir = Files.createDirectories(tempDir.resolve("native"));
		String previous = System.getProperty(NativeHelpers.NATIVE_DIR_PROPERTY);
		try {
			System.setProperty(NativeHelpers.NATIVE_DIR_PROPERTY, nativeDir.toString());
			IllegalStateException error = assertThrows(IllegalStateException.class,
					() -> NativeHelpers.require("gravaai-ctl"));
			assertTrue(error.getMessage().contains("gravaai-ctl"));
			assertTrue(error.getMessage().contains("missing"));
		} finally {
			if (previous == null) {
				System.clearProperty(NativeHelpers.NATIVE_DIR_PROPERTY);
			} else {
				System.setProperty(NativeHelpers.NATIVE_DIR_PROPERTY, previous);
			}
		}
	}

	@Test
	@DisplayName("rejects blank and path-like helper names")
	void rejectsInvalidNames() {
		assertThrows(IllegalArgumentException.class, () -> NativeHelpers.path(""));
		assertThrows(IllegalArgumentException.class, () -> NativeHelpers.path("../ffmpeg"));
	}
}
