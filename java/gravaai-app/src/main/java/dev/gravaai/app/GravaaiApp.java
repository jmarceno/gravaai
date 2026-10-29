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
		if (System.getenv("GRAVAAI_EDITOR_OPEN") == null
				|| System.getenv("GRAVAAI_EDITOR_OPEN").isBlank()) {
			Main.ensureFontsLoaded();
		}
	}

	@Override
	public void start(Stage primaryStage) {
		Startup.log("Application.start");
		context = AppContext.create(Main.launchAction());
		context.start(primaryStage);
		Startup.log("services ready");
		Platform.runLater(() -> Startup.log("first pulse done"));
	}

	@Override
	public void stop() {
		if (context != null) {
			context.shutdown();
		}
		Startup.log("stopped");
	}
}
