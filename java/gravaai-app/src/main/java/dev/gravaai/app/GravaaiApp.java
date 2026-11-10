package dev.gravaai.app;

import org.jspecify.annotations.Nullable;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * JavaFX application (plan §5.1).
 *
 * <p>Started through {@link Main} so the launch timestamp is recorded and the GL thread can start
 * before the toolkit (plan §3 gotcha 4; the GL thread arrives with the media layer).
 */
public final class GravaaiApp extends Application {

	/** The app services, created in {@link #start(Stage)}; {@code null} in {@link #init()}. */
	private @Nullable AppContext context;

	@Override
	public void init() {
		Startup.log("toolkit up, Application.init");
		// Plan §5.12: the bundled DM Sans static instances must be loaded before any real Scene.
		// In the direct-editor startup path the lightweight skeleton window is shown first (before
		// the budget lines), so the font join moves after it (AppContext.ensureFonts()).
		if (!Main.isEditorFirstLaunch()) {
			Main.ensureFontsLoaded();
		}
	}

	@Override
	public void start(Stage primaryStage) {
		Startup.log("Application.start");
		context = AppContext.create(Main.launchAction(), Main.launchFile());
		// SIGTERM/SIGINT never reach Application.stop(), so the tray item would otherwise be
		// dropped only when the JVM tears the session socket down. Closing it from a shutdown hook
		// unexports the item and disconnects the bus while the process still owns its name, which
		// is the removal a status-notifier host can observe deterministically.
		Runtime.getRuntime().addShutdownHook(
				Thread.ofPlatform().name("gravaai-shutdown").unstarted(this::closeDesktopForSignal));
		context.start(primaryStage);
		Startup.log("services ready");
		Platform.runLater(() -> Startup.log("first pulse done"));
	}

	/**
	 * JVM shutdown hook (SIGTERM/SIGINT): the JavaFX path never reaches {@link #stop()} then.
	 *
	 * <p>Bounded on purpose: a D-Bus teardown that blocks must not keep the process from exiting,
	 * so the close runs on its own thread and the hook gives up after a couple of seconds.
	 */
	private void closeDesktopForSignal() {
		AppContext current = context;
		if (current == null) {
			return;
		}
		Thread closer = Thread.ofPlatform().name("gravaai-shutdown-desktop")
				.unstarted(current::closeDesktopForSignal);
		closer.start();
		try {
			closer.join(2_000);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			return;
		}
		if (closer.isAlive()) {
			Startup.log("shutdown hook: desktop close timed out");
		}
	}

	@Override
	public void stop() {
		if (context != null) {
			context.shutdown();
		}
		Startup.log("stopped");
	}
}
