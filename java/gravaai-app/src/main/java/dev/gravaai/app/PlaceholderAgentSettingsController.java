package dev.gravaai.app;

import java.util.List;
import dev.gravaai.ui.dashboard.AgentSettingsController;

/**
 * Settings -> AI control before the agent service lands (plan §5.16 is Phase 3, P3.2). The page
 * still shows the MCP command for the AppImage, which is what the plan asks users to copy; the
 * toggle and token regeneration are no-ops until the agent socket exists. The Lead wires the real
 * controller in P3.2.
 */
public final class PlaceholderAgentSettingsController implements AgentSettingsController {

	private final String mcpCommand;
	private final String jsonConfig;
	private volatile State state;

	public PlaceholderAgentSettingsController(String appPath) {
		this.mcpCommand = appPath + " --mcp";
		this.jsonConfig = "{\n"
				+ "  \"mcpServers\": {\n"
				+ "    \"gravaai\": {\n"
				+ "      \"command\": \"" + appPath + "\",\n"
				+ "      \"args\": [\"--mcp\"]\n"
				+ "    }\n"
				+ "  }\n"
				+ "}";
		this.state = new State(false, List.of(), mcpCommand, jsonConfig);
	}

	@Override
	public State state() {
		return state;
	}

	@Override
	public State setEnabled(boolean enabled) {
		// Phase 3 (P3.2) starts/stops the agent control socket; the setting shape is ready now.
		state = new State(enabled, state.clients(), mcpCommand, jsonConfig);
		return state;
	}

	@Override
	public State regenerateToken() {
		return state;
	}
}
