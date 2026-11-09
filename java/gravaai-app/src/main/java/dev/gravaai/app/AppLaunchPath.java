package dev.gravaai.app;

import java.nio.file.Path;

import dev.gravaai.platform.nativehelpers.NativeHelpers;

/**
 * The path that launches this application again: an AppImage path, the jpackage launcher, or the
 * running process's own command.
 *
 * <p>Two hand-written resolvers existed ({@code AppContext.appPath} and
 * {@code AgentWiring.appExecutablePath}) and they disagreed: one honoured {@code $APPIMAGE} and
 * {@code jpackage.app-path}, the other honoured {@code $GRAVAAI_APPIMAGE} and nothing else. Both
 * are used to spell out commands a user or another program runs later — global-shortcut bindings
 * and the MCP server registration — so a wrong answer produces a command that fails much later,
 * far from here.
 *
 * <p>The order is: {@code $GRAVAAI_APPIMAGE} (the explicit override used by tooling and tests),
 * {@code $APPIMAGE} (set by the AppImage runtime), {@code jpackage.app-path} (set by the jpackage
 * launcher) and finally this process's own command line.
 */
public final class AppLaunchPath {

	private AppLaunchPath() {}

	/** The command that starts this application; never blank. */
	public static String current() {
		String gravaaiAppImage = System.getenv("GRAVAAI_APPIMAGE");
		if (gravaaiAppImage != null && !gravaaiAppImage.isBlank()) {
			return gravaaiAppImage;
		}
		String appImage = System.getenv("APPIMAGE");
		if (appImage != null && !appImage.isBlank()) {
			return appImage;
		}
		String jpackagePath = System.getProperty(NativeHelpers.APP_PATH_PROPERTY);
		if (jpackagePath != null && !jpackagePath.isBlank()) {
			return jpackagePath;
		}
		return ProcessHandle.current().info().command().orElse("gravaai");
	}

	/** The application's executable as a path, for code that needs to inspect it. */
	public static Path currentPath() {
		return Path.of(current());
	}
}
