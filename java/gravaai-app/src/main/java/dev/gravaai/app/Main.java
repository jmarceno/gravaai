package dev.gravaai.app;

import dev.gravaai.core.storage.UserData;
import dev.gravaai.services.shortcuts.ControlSocket;
import dev.gravaai.services.shortcuts.GlobalShortcutAction;
import dev.gravaai.services.shortcuts.GlobalShortcuts;
import javafx.application.Application;

/**
 * Process entry point (plan §5.1).
 *
 * <ol>
 *   <li>Record the launch time.</li>
 *   <li>Parse args.</li>
 *   <li>Single-instance check: when another process holds the user-data instance lock, forward the
 *       launch action (or {@code show}) over the control socket and exit; otherwise this process
 *       becomes the app.</li>
 *   <li>Start the {@code gl-preview} thread (P1.5, §5.7) - before {@link Application#launch}.</li>
 *   <li>{@link Application#launch(GravaaiApp.class)}.</li>
 * </ol>
 */
public final class Main {

	private static volatile GlobalShortcutAction launchAction;
	private static Thread fontPreload;

	private Main() {
	}

	public static void main(String[] args) {
		Startup.mark();
		UserData userData = UserData.resolve();
		Logging.configure(userData.logsDir().resolve("gravaai.log"));
		Startup.log("main");

		CliArgs cli = CliArgs.parse(args);
		if (cli.mcp()) {
			dev.gravaai.app.mcp.McpMain.run(args);
			return;
		}
		if (cli.uiKit()) {
			System.setProperty("gravaai.uiKit", "true");
		}
		if (cli.dashboard()) {
			System.setProperty("gravaai.dashboard", "true");
		}

		launchAction = GlobalShortcuts.parseCliAction(args);
		if (!SingleInstance.acquire(userData.dir().resolve("instance.lock"))) {
			// Another Gravaai is running: never open a second one. Hand it the launch action, or
			// ask it to come to the front, and exit.
			String line = launchAction != null ? launchAction.wire() : ControlSocket.SHOW_LINE;
			if (SingleInstance.forward(line)) {
				Startup.log("forwarded " + line + " to the running instance");
			} else {
				Startup.log("Gravaai is already running but did not answer " + line + "; exiting");
			}
			System.exit(0);
			return;
		}
		if (launchAction != null) {
			// No other instance holds the lock; a stale socket from a crashed run is harmless.
			Startup.log("no running instance for " + launchAction.wire() + "; starting the app");
		}

		// Plan §5.12 wants the bundled DM Sans faces loaded before any Scene. Loading them on a
		// platform thread overlaps the ~60 ms it takes with JavaFX toolkit start-up (the init()
		// thread joins this thread before building a Scene).
		String editorOpen = System.getenv("GRAVAAI_EDITOR_OPEN");
		boolean editorFirst = editorOpen != null && !editorOpen.isBlank();
		if (!editorFirst) {
			// The direct-editor fast path defers the font join past its budget lines; starting the
			// preload thread there only adds CPU/IO contention before the skeleton is visible.
			fontPreload = Thread.ofPlatform().name("font-preload").start(() -> {
				try {
					dev.gravaai.ui.AppFonts.load();
				} catch (Throwable error) {
					System.getLogger("gravaai").log(System.Logger.Level.WARNING,
							"font preload failed: " + error);
				}
			});
		}

		Application.launch(GravaaiApp.class, args);
	}

	static Thread fontPreload() {
		return fontPreload;
	}

	/** Joins the preload thread and loads the DM Sans faces if they are not ready yet. */
	static void ensureFontsLoaded() {
		Thread preload = fontPreload;
		if (preload != null) {
			try {
				preload.join(2_000);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		}
		if (!dev.gravaai.ui.AppFonts.isLoaded()) {
			dev.gravaai.ui.AppFonts.load();
		}
	}

	/** The launch action that must be dispatched once the HUD is ready, or null. */
	static GlobalShortcutAction launchAction() {
		return launchAction;
	}
}
