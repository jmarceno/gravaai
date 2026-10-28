package dev.gravaai.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * The per-user data directory (plan §5.2).
 *
 * <p>Byte-compatible with Electron: {@code $XDG_CONFIG_HOME/Gravaai}, defaulting to
 * {@code ~/.config/Gravaai} — Electron's {@code app.getPath("userData")} for
 * {@code productName: "Gravaai"}. A real install at {@code ~/.config/Gravaai} (with
 * {@code app-settings.json}, {@code recent-projects.json}, {@code recordings/}, ...) confirms the
 * folder name. Ported from {@code electron/appPaths.ts}.
 *
 * <p>{@link #adoptLegacyUserDataDir} moves the previous profile over when the current one is fresh
 * ("Grava AI" -> "Gravaai"), so recordings and settings survive product renames.
 *
 * <p>The {@code GRAVAAI_USER_DATA} environment variable / {@code gravaai.userData} system property
 * overrides the directory for tests, screenshots and startup measurements, so those never touch a
 * real user profile; no migration runs when the override is set.
 */
public record UserData(Path dir) {

	private static final System.Logger LOG = System.getLogger("gravaai.storage");

	/** Electron's {@code productName}; also the profile folder name. */
	public static final String PRODUCT_DIRECTORY_NAME = "Gravaai";

	public static UserData resolve() {
		String override = System.getProperty("gravaai.userData");
		if (override == null || override.isBlank()) {
			override = System.getenv("GRAVAAI_USER_DATA");
		}
		if (override != null && !override.isBlank()) {
			return new UserData(Path.of(override).toAbsolutePath());
		}
		return resolve(appDataDir());
	}

	/**
	 * Resolves the profile inside an explicit app-data directory and adopts a legacy profile
	 * ("Grava AI") when the current folder is missing or empty.
	 */
	public static UserData resolve(Path appDataPath) {
		Path target = appDataPath.resolve(PRODUCT_DIRECTORY_NAME);
		adoptLegacyUserDataDir(appDataPath, target, "Grava AI");
		return new UserData(target.toAbsolutePath());
	}

	/** {@code app.getPath("appData")} equivalent: {@code $XDG_CONFIG_HOME}, default {@code ~/.config}. */
	public static Path appDataDir() {
		String xdgConfigHome = System.getenv("XDG_CONFIG_HOME");
		Path configHome = xdgConfigHome != null && !xdgConfigHome.isBlank()
				? Path.of(xdgConfigHome)
				: Path.of(System.getProperty("user.home"), ".config");
		return configHome.toAbsolutePath();
	}

	/**
	 * Port of {@code adoptLegacyUserDataDir} in {@code electron/appPaths.ts}.
	 *
	 * <p>Moves {@code appDataPath/legacyName} to {@code target} only when the target is missing or
	 * empty and the legacy directory exists and is not empty. Failures are logged and swallowed: a
	 * profile migration must never stop the app from starting.
	 */
	public static void adoptLegacyUserDataDir(Path appDataPath, Path target, String legacyName) {
		try {
			if (Files.exists(target) && isNonEmptyDirectory(target)) {
				return;
			}

			Path legacyDir = appDataPath.resolve(legacyName);
			if (!Files.exists(legacyDir) || !isNonEmptyDirectory(legacyDir)) {
				return;
			}

			Files.createDirectories(target.getParent());
			Files.move(legacyDir, target, StandardCopyOption.REPLACE_EXISTING);
			LOG.log(System.Logger.Level.INFO,
					"[app] migrated previous profile: " + legacyName + " -> " + target.getFileName());
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING,
					"[app] Could not migrate the previous profile directory: " + error);
		}
	}

	private static boolean isNonEmptyDirectory(Path directory) throws IOException {
		try (Stream<Path> entries = Files.list(directory)) {
			return entries.findFirst().isPresent();
		}
	}

	public Path logsDir() {
		return dir.resolve("logs");
	}

	/** Default recordings folder; a custom folder can be configured in recordings-settings.json. */
	public Path recordingsDir() {
		return dir.resolve(StorageFiles.RECORDINGS_DIRECTORY_NAME);
	}

	/** Projects inside the default recordings folder (custom recordings folders change this). */
	public Path projectsDir() {
		return StorageFiles.projectsDir(recordingsDir());
	}

	public Path whisperDir() {
		return dir.resolve(StorageFiles.WHISPER_DIRECTORY_NAME);
	}

	public Path whisperSmallModelPath() {
		return whisperDir().resolve(StorageFiles.WHISPER_SMALL_MODEL_FILE_NAME);
	}

	public Path extensionsDir() {
		return dir.resolve(StorageFiles.EXTENSIONS_DIRECTORY_NAME);
	}

	public Path recentProjectsFile() {
		return dir.resolve(StorageFiles.RECENT_PROJECTS_FILE_NAME);
	}

	public Path shortcutsFile() {
		return dir.resolve(StorageFiles.SHORTCUTS_FILE_NAME);
	}

	public Path recordingsSettingsFile() {
		return dir.resolve(StorageFiles.RECORDINGS_SETTINGS_FILE_NAME);
	}

	public Path countdownSettingsFile() {
		return dir.resolve(StorageFiles.COUNTDOWN_SETTINGS_FILE_NAME);
	}

	public Path appSettingsFile() {
		return dir.resolve(StorageFiles.APP_SETTINGS_FILE_NAME);
	}

	public Path automationsFile() {
		return dir.resolve(StorageFiles.AUTOMATIONS_FILE_NAME);
	}

	public Path agentSocket() {
		return dir.resolve(StorageFiles.AGENT_SOCKET_FILE_NAME);
	}

	public Path agentTokenFile() {
		return dir.resolve(StorageFiles.AGENT_TOKEN_FILE_NAME);
	}
}
