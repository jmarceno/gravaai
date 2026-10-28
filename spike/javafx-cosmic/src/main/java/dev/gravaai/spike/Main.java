package dev.gravaai.spike;

import javafx.application.Application;

/** Launcher kept separate from the Application subclass so the classpath start-up path works. */
public final class Main {
	private Main() {}

	/** Started before the toolkit so EGL, GL and shader compilation overlap JavaFX start-up. */
	static final GlPreviewRenderer RENDERER = new GlPreviewRenderer(1920, 1080);

	public static void main(String[] args) {
		Log.info("startup: main at " + Log.sinceProcessStart() + " ms");
		RENDERER.start();
		Application.launch(SpikeApp.class, args);
	}
}
