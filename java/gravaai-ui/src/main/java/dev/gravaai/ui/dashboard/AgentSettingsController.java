package dev.gravaai.ui.dashboard;

import java.util.List;

/**
 * Thin port for Settings -> AI control (agent service is Phase 3, plan §7.3 P3.2; Lead decision:
 * the page ships now against this port so AppContext can wire the real service later).
 */
public interface AgentSettingsController {

	/** One connected MCP client ({@code clients[].client} in the React source). */
	record Client(String client) {
	}

	/**
	 * Port of the {@code getAgentControlStatus} / {@code getAgentMcpConfig} shapes.
	 *
	 * @param enabled whether the local agent socket listens
	 * @param clients connected MCP clients
	 * @param claudeCodeCommand the command to paste into Claude Code
	 * @param jsonConfig the MCP JSON config block
	 */
	record State(boolean enabled, List<Client> clients, String claudeCodeCommand, String jsonConfig) {
		public State {
			clients = List.copyOf(clients);
		}
	}

	State state();

	State setEnabled(boolean enabled);

	State regenerateToken();
}
