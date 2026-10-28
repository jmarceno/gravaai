package dev.gravaai.app;

import java.util.List;

/**
 * Command line (plan §5.1). Flag names match {@code electron/main.ts} and {@code gravaai-ctl};
 * the launch actions are resolved by the global shortcut dispatcher (P1.12, plan §5.17).
 */
public record CliArgs(boolean mcp, boolean uiKit, boolean dashboard, List<String> raw) {

	public static CliArgs parse(String[] args) {
		boolean mcp = false;
		boolean uiKit = false;
		boolean dashboard = false;
		for (String arg : args) {
			switch (arg) {
				case "--mcp" -> mcp = true;
				case "--ui-kit" -> uiKit = true;
				case "--dashboard" -> dashboard = true;
				default -> {
					// Launch actions (--toggle-recording, ...) are handled by the shortcut dispatch.
				}
			}
		}
		return new CliArgs(mcp, uiKit, dashboard, List.of(args));
	}
}
