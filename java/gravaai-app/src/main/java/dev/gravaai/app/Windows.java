package dev.gravaai.app;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

import dev.gravaai.services.shortcuts.GlobalShortcutAction;
import dev.gravaai.services.shortcuts.GlobalShortcutHud;
import dev.gravaai.ui.windows.EditorWindow;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * Owns the application windows (plan §5.1, §5.4).
 *
 * <p>P1 wiring: the HUD overlay is the startup window (owned by {@code AppContext}, registered here
 * so the shortcut dispatcher can address it), the primary stage hosts the dashboard, and the tray
 * callbacks drive both.
 */
public final class Windows {

	private static final System.Logger LOG = System.getLogger("gravaai");

	/** Opens one project in the real editor window (implemented by {@code AppContext}). */
	@FunctionalInterface
	public interface EditorLauncher {
		void open(Path projectPath);
	}

	private final Stage primaryStage;
	private final boolean uiKitMode;
	private @Nullable EditorWindow editorWindow;
	private @Nullable EditorLauncher editorLauncher;
	private @Nullable GlobalShortcutHud hud;

	public Windows(Stage primaryStage, boolean uiKitMode) {
		this.primaryStage = primaryStage;
		this.uiKitMode = uiKitMode;
	}

	public Stage primaryStage() {
		return primaryStage;
	}

	/** Registers the HUD so the shortcut dispatcher can reach it (P1.7). */
	public void setHud(GlobalShortcutHud hud) {
		this.hud = hud;
	}

	/** The HUD for the global-shortcut dispatcher; null until it is built. */
	public @Nullable GlobalShortcutHud hud() {
		return hud;
	}

	/** Shows the dashboard window (editor placeholder in P1.1, dashboard in P1.10). */
	public void showMainWindow() {
		if (uiKitMode) {
			showUiKit();
			return;
		}
		if (editorWindow == null) {
			editorWindow = new EditorWindow(primaryStage);
		}
		if (!primaryStage.isShowing()) {
			editorWindow.show();
		}
		primaryStage.setIconified(false);
		primaryStage.toFront();
		primaryStage.requestFocus();
	}

	/** Installs the app editor factory (P2.10); without it the dashboard falls back to a log. */
	public void setEditorLauncher(EditorLauncher launcher) {
		this.editorLauncher = launcher;
	}

	/** Opens a project in the real editor window (dashboard Actions + tray/home flows). */
	public void openEditor(Path projectPath) {
		if (editorLauncher != null) {
			editorLauncher.open(projectPath);
		} else {
			LOG.log(System.Logger.Level.INFO, "open in editor (P2.10 not wired): " + projectPath);
		}
	}

	/** Dashboard "open project/recording" (kept for the P1 placeholder signature). */
	public void showEditorPlaceholder(String name) {
		Path path = name == null ? null : Path.of(name);
		if (path != null) {
			openEditor(path);
		}
	}

	/** Tray "Show Overlay": the HUD stage is owned by {@code AppContext}. */
	public void showOverlay() {
		LOG.log(System.Logger.Level.INFO, "show overlay requested (HUD is shown by AppContext)");
	}

	public void deliverShortcut(GlobalShortcutAction action) {
		LOG.log(System.Logger.Level.INFO, "shortcut action: " + action.wire());
	}

	public void shutdown() {
		if (editorWindow != null) {
			editorWindow.stage().close();
			editorWindow = null;
		}
	}

	private void showUiKit() {
		// Loaded reflectively so the app module keeps compiling while the showcase is being
		// rewritten: it is a development-only window (plan §5.12 P1.3), not part of the app.
		try {
			Class<?> showcase = Class.forName("dev.gravaai.ui.kit.UiKitShowcase");
			showcase.getMethod("show", Stage.class).invoke(null, primaryStage);
			LOG.log(System.Logger.Level.INFO, "--ui-kit: kit showcase shown");
		} catch (ReflectiveOperationException error) {
			LOG.log(System.Logger.Level.WARNING, "--ui-kit requested but UiKitShowcase is not available: " + error);
		}
	}

	static {
		// The app lives in the tray: closing the last window (the countdown while the HUD is hidden,
		// a capture picker, the main window) must not exit. Quitting goes through the tray or an
		// explicit application shutdown.
		Platform.setImplicitExit(false);
	}
}
