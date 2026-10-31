package dev.gravaai.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Command line (plan §5.1). Flag names match {@code electron/main.ts} and {@code gravaai-ctl};
 * the launch actions are resolved by the global shortcut dispatcher (P1.12, plan §5.17).
 *
 * <p>A positional argument naming an existing file is a launch file (a {@code .gravaai} project or
 * a raw recording) so double-clicking an associated file in the file manager opens it; a second
 * launch forwards {@code open <path>} over the control socket instead.
 */
public record CliArgs(boolean mcp, boolean uiKit, boolean dashboard, @Nullable String launchFile,
		List<String> raw) {

	public static CliArgs parse(String[] args) {
		boolean mcp = false;
		boolean uiKit = false;
		boolean dashboard = false;
		String launchFile = null;
		for (String arg : args) {
			switch (arg) {
				case "--mcp" -> mcp = true;
				case "--ui-kit" -> uiKit = true;
				case "--dashboard" -> dashboard = true;
				default -> {
					// Launch actions (--toggle-recording, ...) are handled by the shortcut dispatch.
					if (launchFile == null && !arg.startsWith("-") && isExistingFile(arg)) {
						launchFile = arg;
					}
				}
			}
		}
		return new CliArgs(mcp, uiKit, dashboard, launchFile, List.of(args));
	}

	/** A positional arg counts as a launch file only when it names a real, readable file. */
	private static boolean isExistingFile(String arg) {
		try {
			return Files.isRegularFile(Path.of(arg));
		} catch (RuntimeException error) {
			return false;
		}
	}
}
