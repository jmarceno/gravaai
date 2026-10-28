package dev.gravaai.spike;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.util.Duration;
import javafx.stage.Screen;
import javafx.stage.Stage;

public final class SpikeApp extends Application {
	private GlPreviewRenderer renderer;

	@Override
	public void init() {
		Log.info("startup: toolkit up, Application.init at " + Log.sinceProcessStart() + " ms");
	}

	@Override
	public void start(Stage primaryStage) {
		Log.info("startup: Application.start at " + Log.sinceProcessStart() + " ms");
		logEnvironment();
		renderer = Main.RENDERER;
		EditorWindow editor = new EditorWindow(primaryStage, renderer);
		editor.show();
		new HudWindow(editor).show();

		// -Dgravaai.spike.exitAfterMs=N quits on its own; used for timing and AOT training runs.
		long exitAfter = Long.getLong("gravaai.spike.exitAfterMs", 0L);
		if (exitAfter > 0) {
			new Timeline(new KeyFrame(Duration.millis(exitAfter), e -> Platform.exit())).play();
		}
	}

	@Override
	public void stop() {
		renderer.stop();
		Log.info("stopped");
	}

	private static void logEnvironment() {
		Log.info("java " + System.getProperty("java.runtime.version") + " (" + System.getProperty("java.vm.name") + ")");
		Log.info("javafx " + System.getProperty("javafx.runtime.version"));
		for (String key : new String[] {"XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", "WAYLAND_DISPLAY", "DISPLAY", "GDK_BACKEND", "GDK_SCALE"}) {
			Log.info("env " + key + "=" + System.getenv(key));
		}
		for (Screen screen : Screen.getScreens()) {
			Log.info("screen bounds=" + screen.getBounds() + " visual=" + screen.getVisualBounds()
					+ " scale=" + screen.getOutputScaleX() + "x" + screen.getOutputScaleY()
					+ " dpi=" + screen.getDpi() + (screen.equals(Screen.getPrimary()) ? " primary" : ""));
		}
	}
}
