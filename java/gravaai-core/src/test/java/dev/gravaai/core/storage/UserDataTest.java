package dev.gravaai.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Port of {@code adoptLegacyUserDataDir} in {@code electron/appPaths.ts} (plan §5.2). */
class UserDataTest {

	@Test
	@DisplayName("marks a fresh profile in an empty app-data directory")
	void marksFreshProfile(@TempDir Path appData) {
		Path target = appData.resolve("Gravaai");
		assertFalse(Files.exists(target));
	}

	@Test
	@DisplayName("migrates the previous profile directory when the current one is fresh")
	void migratesLegacyProfile(@TempDir Path appData) throws IOException {
		Path legacy = appData.resolve("Grava AI");
		Files.createDirectories(legacy.resolve("recordings"));
		Files.writeString(legacy.resolve("app-settings.json"), "{}");

		UserData.adoptLegacyUserDataDir(appData, appData.resolve("Gravaai"), "Grava AI");

		Path current = appData.resolve("Gravaai");
		assertFalse(Files.exists(legacy));
		assertEquals("{}", Files.readString(current.resolve("app-settings.json")));
		assertTrue(Files.isDirectory(current.resolve("recordings")));
	}

	@Test
	@DisplayName("replaces an empty current profile with the previous one")
	void replacesEmptyProfile(@TempDir Path appData) throws IOException {
		Files.createDirectories(appData.resolve("Gravaai"));
		Path legacy = appData.resolve("Grava AI");
		Files.createDirectories(legacy);
		Files.writeString(legacy.resolve("recent-projects.json"), "{}");

		UserData.adoptLegacyUserDataDir(appData, appData.resolve("Gravaai"), "Grava AI");

		assertFalse(Files.exists(legacy));
		assertEquals("{}", Files.readString(appData.resolve("Gravaai/recent-projects.json")));
	}

	@Test
	@DisplayName("keeps a current profile that already has data")
	void keepsExistingProfile(@TempDir Path appData) throws IOException {
		Path current = appData.resolve("Gravaai");
		Files.createDirectories(current);
		Files.writeString(current.resolve("app-settings.json"), "{\"kept\":true}");
		Path legacy = appData.resolve("Grava AI");
		Files.createDirectories(legacy);
		Files.writeString(legacy.resolve("app-settings.json"), "{\"stale\":true}");

		UserData.adoptLegacyUserDataDir(appData, current, "Grava AI");

		assertEquals("{\"kept\":true}", Files.readString(current.resolve("app-settings.json")));
		assertEquals("{\"stale\":true}", Files.readString(legacy.resolve("app-settings.json")));
	}

	@Test
	@DisplayName("does nothing when there is no previous profile")
	void doesNothingWithoutLegacyProfile(@TempDir Path appData) {
		UserData.adoptLegacyUserDataDir(appData, appData.resolve("Gravaai"), "Grava AI");
		assertFalse(Files.exists(appData.resolve("Gravaai")));
	}

	@Test
	@DisplayName("does not migrate an empty previous profile")
	void ignoresEmptyLegacyProfile(@TempDir Path appData) throws IOException {
		Files.createDirectories(appData.resolve("Grava AI"));

		UserData.adoptLegacyUserDataDir(appData, appData.resolve("Gravaai"), "Grava AI");

		assertTrue(Files.isDirectory(appData.resolve("Grava AI")));
		assertFalse(Files.exists(appData.resolve("Gravaai")));
	}

	@Test
	@DisplayName("resolves the profile under an explicit app-data directory")
	void resolvesUnderAppData(@TempDir Path appData) {
		assertEquals(appData.resolve("Gravaai").toAbsolutePath(), UserData.resolve(appData).dir());
	}

	@Test
	@DisplayName("honours the gravaai.userData override and skips migration")
	void honoursUserDataOverride(@TempDir Path appData) throws IOException {
		Files.createDirectories(appData.resolve("Grava AI"));
		Files.writeString(appData.resolve("Grava AI/marker.txt"), "legacy");
		String previous = System.getProperty("gravaai.userData");
		try {
			System.setProperty("gravaai.userData", appData.resolve("override").toString());
			UserData userData = UserData.resolve();
			assertEquals(appData.resolve("override").toAbsolutePath(), userData.dir());
			assertTrue(Files.isDirectory(appData.resolve("Grava AI")));
		} finally {
			if (previous == null) {
				System.clearProperty("gravaai.userData");
			} else {
				System.setProperty("gravaai.userData", previous);
			}
		}
	}

	@Test
	@DisplayName("resolves the data files below the profile directory")
	void resolvesDataFiles(@TempDir Path appData) {
		UserData userData = UserData.resolve(appData);
		Path dir = appData.resolve("Gravaai").toAbsolutePath();
		assertEquals(dir.resolve("logs"), userData.logsDir());
		assertEquals(dir.resolve("recordings"), userData.recordingsDir());
		assertEquals(dir.resolve("recordings/Projects"), userData.projectsDir());
		assertEquals(dir.resolve("whisper/ggml-small.bin"), userData.whisperSmallModelPath());
		assertEquals(dir.resolve("extensions"), userData.extensionsDir());
		assertEquals(dir.resolve("recent-projects.json"), userData.recentProjectsFile());
		assertEquals(dir.resolve("shortcuts.json"), userData.shortcutsFile());
		assertEquals(dir.resolve("recordings-settings.json"), userData.recordingsSettingsFile());
		assertEquals(dir.resolve("countdown-settings.json"), userData.countdownSettingsFile());
		assertEquals(dir.resolve("app-settings.json"), userData.appSettingsFile());
		assertEquals(dir.resolve("automations.json"), userData.automationsFile());
		assertEquals(dir.resolve("agent.sock"), userData.agentSocket());
		assertEquals(dir.resolve("agent-token"), userData.agentTokenFile());
	}
}
