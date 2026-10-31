package dev.gravaai.services.shortcuts;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;

/**
 * Global (system-wide) recording shortcuts, COSMIC registration and the control socket (plan
 * §5.17).
 *
 * <p>Port of {@code electron/globalShortcuts/index.ts}. On COSMIC the user's
 * {@code custom} RON file is spliced: only Gravaai's own entries are touched, every other byte is
 * preserved, and a parse error aborts without writing. Elsewhere the actions only work as
 * {@code --action} CLI flags ({@code cli-only}).
 *
 * <p>Dependencies are injected (plan §5.3): the app-settings slot, the bundled
 * {@code gravaai-ctl} locator, the app path the shortcut command runs and the action sink (the
 * dispatcher). There are no static globals.
 */
public final class GlobalShortcutsService implements AutoCloseable {

	/** Port of {@code PARSE_ERROR_MESSAGE}. */
	public static final String PARSE_ERROR_MESSAGE =
			"Could not read your COSMIC shortcuts file; Gravaai did not change it.";

	private static final String INSTALL_ERROR = "Could not install Gravaai's shortcut helper.";
	private static final String SAVE_ERROR = "Could not save your COSMIC shortcuts.";
	private static final String CTL_BINARY_NAME = "gravaai-ctl";
	private static final System.Logger LOG = System.getLogger("gravaai.shortcuts");

	private final GlobalShortcutSettings settings;
	private final Supplier<Path> bundledCtl;
	private final String appPath;
	private final Map<String, String> env;
	private final Consumer<GlobalShortcutAction> actionSink;
	/** Null when a second launch cannot bring the app to the front. */
	private final @Nullable Runnable showSink;
	/** Null when the app cannot open a project/recording passed by a second launch. */
	private final @Nullable Consumer<Path> openFileSink;
	private final Object lock = new Object();

	private ControlSocket.@Nullable Server controlSocket;
	private @Nullable Path installedCtlPath;
	private @Nullable GlobalShortcutsStatus lastStatus;

	/**
	 * @param settings the {@code globalShortcuts} app setting
	 * @param bundledCtl where the packaged {@code gravaai-ctl} lives ({@code NativeHelpers.path})
	 * @param appPath the binary COSMIC should run ({@code $APPIMAGE} or the launcher path)
	 * @param env the process environment (XDG paths, desktop name); a map so tests can fake it
	 * @param actionSink receives control-socket actions (the dispatcher's {@code dispatch})
	 */
	public GlobalShortcutsService(
			GlobalShortcutSettings settings,
			Supplier<Path> bundledCtl,
			String appPath,
			Map<String, String> env,
			Consumer<GlobalShortcutAction> actionSink) {
		this(settings, bundledCtl, appPath, env, actionSink, null);
	}

	/** @param showSink brings the app to the front when a second launch sends {@code show} */
	public GlobalShortcutsService(
			GlobalShortcutSettings settings,
			Supplier<Path> bundledCtl,
			String appPath,
			Map<String, String> env,
			Consumer<GlobalShortcutAction> actionSink,
			@Nullable Runnable showSink) {
		this(settings, bundledCtl, appPath, env, actionSink, showSink, null);
	}

	/**
	 * @param openFileSink opens a project/recording when a second launch sends {@code open <path>}
	 */
	public GlobalShortcutsService(
			GlobalShortcutSettings settings,
			Supplier<Path> bundledCtl,
			String appPath,
			Map<String, String> env,
			Consumer<GlobalShortcutAction> actionSink,
			@Nullable Runnable showSink,
			@Nullable Consumer<Path> openFileSink) {
		this.settings = settings;
		this.bundledCtl = bundledCtl;
		this.appPath = appPath;
		this.env = env == null ? Map.of() : Map.copyOf(env);
		this.actionSink = actionSink;
		this.showSink = showSink;
		this.openFileSink = openFileSink;
	}

	/** Port of {@code isCosmicDesktop}. */
	public boolean isCosmicDesktop() {
		if (!isLinux()) {
			return false;
		}
		String desktop = env.get("XDG_CURRENT_DESKTOP");
		if (desktop == null) {
			return false;
		}
		for (String name : desktop.split(":", -1)) {
			if (name.toLowerCase(Locale.ROOT).contains("cosmic")) {
				return true;
			}
		}
		return false;
	}

	/** Port of {@code readGlobalShortcutsConfig}. */
	public GlobalShortcutsConfig readConfig() {
		JsonNode stored = settings.read();
		return stored == null ? GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS : GlobalShortcuts.normalizeGlobalShortcutsConfig(stored);
	}

	/** Port of {@code getGlobalShortcutsStatus}. */
	public GlobalShortcutsStatus getStatus() {
		synchronized (lock) {
			if (lastStatus != null) {
				return lastStatus;
			}
			return apply(readConfig(), false);
		}
	}

	/** Port of {@code applyGlobalShortcuts(config)}: saves and makes COSMIC's file match. */
	public GlobalShortcutsStatus apply(GlobalShortcutsConfig config) {
		return apply(config, true);
	}

	/** Port of {@code applyGlobalShortcuts(config, { persist })}; calls run one at a time. */
	public GlobalShortcutsStatus apply(GlobalShortcutsConfig config, boolean persist) {
		synchronized (lock) {
			if (persist) {
				settings.write(GlobalShortcuts.toJson(config, tools.jackson.databind.node.JsonNodeFactory.instance));
			}
			lastStatus = isCosmicDesktop() ? syncCosmic(config) : emptyStatus(GlobalShortcutsStatus.Backend.CLI_ONLY);
			return lastStatus;
		}
	}

	/**
	 * Port of {@code initGlobalShortcuts}: starts the control socket and registers the stored
	 * shortcuts once, after the app is ready.
	 */
	public void init() {
		synchronized (lock) {
			try {
				controlSocket = ControlSocket.start(actionSink, showSink, openFileSink,
						ControlSocket.getControlSocketPath(env));
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "Could not start the control socket: " + error);
			}
			GlobalShortcutsStatus status = apply(readConfig(), false);
			if (status.error() != null) {
				LOG.log(System.Logger.Level.WARNING, status.error());
			}
		}
	}

	/** Port of {@code shutdownGlobalShortcuts}. */
	@Override
	public void close() {
		synchronized (lock) {
			ControlSocket.Server socket = controlSocket;
			controlSocket = null;
			if (socket != null) {
				socket.close();
			}
		}
	}

	/** Port of {@code syncCosmic}. */
	private GlobalShortcutsStatus syncCosmic(GlobalShortcutsConfig config) {
		GlobalShortcutsStatus status = emptyStatus(GlobalShortcutsStatus.Backend.COSMIC);
		CosmicShortcutPaths paths = CosmicShortcuts.getCosmicShortcutPaths(env);
		String customText;
		List<CosmicShortcutEntry> foreignEntries = new ArrayList<>();
		try {
			customText = CosmicShortcuts.readCosmicCustomShortcuts(paths);
			String defaultsText = CosmicShortcuts.readCosmicDefaultShortcuts(paths);
			if (!customText.trim().isEmpty()) {
				foreignEntries.addAll(CosmicShortcuts.parseCosmicShortcuts(customText).entries());
			}
			if (!defaultsText.trim().isEmpty()) {
				foreignEntries.addAll(CosmicShortcuts.parseCosmicShortcuts(defaultsText).entries());
			}
		} catch (IOException | CosmicShortcutsParseException error) {
			LOG.log(System.Logger.Level.WARNING, "Not changing COSMIC shortcuts: " + describe(error));
			return statusWith(status, Map.of(), status.conflicts(), PARSE_ERROR_MESSAGE);
		}

		Map<GlobalShortcutAction, GlobalShortcutBinding> registered = new LinkedHashMap<>();
		Map<GlobalShortcutAction, String> conflicts = new LinkedHashMap<>();
		List<GravaaiCosmicEntry> next = new ArrayList<>();
		if (config.enabled()) {
			if (installedCtlPath == null) {
				try {
					installedCtlPath = installCtl();
				} catch (IOException error) {
					LOG.log(System.Logger.Level.WARNING, "Could not install gravaai-ctl: " + error);
					return statusWith(status, registered, conflicts, INSTALL_ERROR);
				}
			}
			for (GlobalShortcutAction action : GlobalShortcutAction.values()) {
				GlobalShortcutBinding binding = config.bindings().get(action);
				if (binding == null) {
					continue;
				}
				GlobalShortcutAction duplicate = findRegisteredDuplicate(registered, binding);
				if (duplicate != null) {
					conflicts.put(action, GlobalShortcuts.formatGlobalBinding(binding)
							+ " is already used by \"" + GlobalShortcuts.GLOBAL_SHORTCUT_LABELS.get(duplicate) + "\"");
					continue;
				}
				CosmicShortcutEntry conflict = CosmicShortcuts.findCosmicConflict(binding, foreignEntries);
				if (conflict != null) {
					conflicts.put(action, CosmicShortcuts.describeCosmicConflict(binding, conflict));
					continue;
				}
				registered.put(action, binding);
				next.add(new GravaaiCosmicEntry(
						binding,
						CosmicShortcuts.GRAVAAI_DESCRIPTION_PREFIX + GlobalShortcuts.GLOBAL_SHORTCUT_LABELS.get(action),
						CosmicShortcuts.buildCtlCommand(installedCtlPath, action, appPath)));
			}
		}

		try {
			String nextText = CosmicShortcuts.replaceGravaaiEntries(customText, next);
			if (!nextText.equals(customText)) {
				CosmicShortcuts.writeCosmicCustomShortcuts(nextText, paths);
				LOG.log(System.Logger.Level.INFO, "Updated COSMIC shortcuts (" + next.size() + " Gravaai entries)");
			}
		} catch (CosmicShortcutsParseException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not update COSMIC shortcuts: " + error.getMessage());
			return statusWith(status, Map.of(), conflicts, PARSE_ERROR_MESSAGE);
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not update COSMIC shortcuts: " + error);
			return statusWith(status, registered, conflicts, SAVE_ERROR);
		}
		return statusWith(status, registered, conflicts, null);
	}

	/** Port of {@code emptyStatus}. */
	private GlobalShortcutsStatus emptyStatus(GlobalShortcutsStatus.Backend backend) {
		return new GlobalShortcutsStatus(
				backend, Map.of(), Map.of(), null, GlobalShortcuts.cliCommands(appPath));
	}

	private static GlobalShortcutsStatus statusWith(
			GlobalShortcutsStatus base,
			Map<GlobalShortcutAction, GlobalShortcutBinding> registered,
			Map<GlobalShortcutAction, String> conflicts,
			@Nullable String error) {
		return new GlobalShortcutsStatus(base.backend(), registered, conflicts, error, base.commands());
	}

	private static @Nullable GlobalShortcutAction findRegisteredDuplicate(
			Map<GlobalShortcutAction, GlobalShortcutBinding> registered, GlobalShortcutBinding binding) {
		for (GlobalShortcutAction action : GlobalShortcutAction.values()) {
			GlobalShortcutBinding existing = registered.get(action);
			if (existing != null && GlobalShortcuts.bindingsEqual(existing, binding)) {
				return action;
			}
		}
		return null;
	}

	/**
	 * Port of {@code installCtl}: AppImages mount at a new path on every launch, so COSMIC gets a
	 * stable copy of gravaai-ctl under {@code $XDG_DATA_HOME/gravaai/bin}.
	 */
	private Path installCtl() throws IOException {
		Path bundled = bundledCtl.get();
		String dataHome = env.get("XDG_DATA_HOME");
		if (dataHome == null || dataHome.isBlank()) {
			dataHome = Path.of(System.getProperty("user.home"), ".local", "share").toString();
		} else {
			dataHome = dataHome.trim();
		}
		Path target = Path.of(dataHome).resolve("gravaai").resolve("bin").resolve(CTL_BINARY_NAME);
		String bundledHash = sha256(bundled);
		String targetHash = sha256(target);
		if (bundledHash == null) {
			throw new IOException("Bundled " + CTL_BINARY_NAME + " is missing at " + bundled);
		}
		if (!bundledHash.equals(targetHash)) {
			Files.createDirectories(target.getParent());
			Path temporary = Path.of(target + ".tmp");
			Files.copy(bundled, temporary, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwxr-xr-x"));
			} catch (IOException | UnsupportedOperationException error) {
				// Non-POSIX filesystem: keep the copy usable anyway.
			}
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			LOG.log(System.Logger.Level.INFO, "Installed " + CTL_BINARY_NAME + " to " + target);
		}
		return target;
	}

	private static @Nullable String sha256(Path file) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
		} catch (IOException | NoSuchAlgorithmException error) {
			return null;
		}
	}

	private static String describe(Exception error) {
		return error.getMessage() != null ? error.getMessage() : error.toString();
	}

	private static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
	}
}
