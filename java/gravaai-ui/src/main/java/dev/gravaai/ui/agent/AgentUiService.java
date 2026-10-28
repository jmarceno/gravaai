package dev.gravaai.ui.agent;

import java.util.List;
import java.util.function.Consumer;

/**
 * UI port for AI control (plan §5.16/P3.2): everything the Settings page and the editor header
 * chip need from the agent control service.
 *
 * <p>The platform/agent service is built separately (control socket + MCP bridge); this port is the
 * only surface the UI knows so it can ship before the service exists. Implementations publish
 * immutable {@link Snapshot}s through {@link #subscribe(Consumer)} and are expected to be cheap to
 * read from the FX thread (state snapshots, not request/response calls). The placeholder adapter in
 * this package drives the page, chip and timeline highlights until Lead wires
 * {@code services.agent}.
 */
public interface AgentUiService extends AutoCloseable {

	/** Connection state derived from the control-socket status (React's enabled/listening/clients). */
	enum Status {
		/** AI control is off. */
		OFF,
		/** The local socket listens but no MCP client is connected. */
		LISTENING,
		/** At least one MCP client is connected. */
		CONNECTED,
		/** The service could not report a status (e.g. the socket is unavailable). */
		UNAVAILABLE
	}

	/** One connected MCP client ({@code clients[].client} in the React source). */
	record Client(String client, long sinceMillis) {
	}

	/** One entry of the recent-activity list (agent tool call / edit / error). */
	record ActivityEntry(long atMillis, String label, boolean failed) {
	}

	/**
	 * One immutable view of the agent service.
	 *
	 * @param enabled whether the local agent socket is enabled by the user setting
	 * @param listening whether the control server is listening on the socket
	 * @param editorReady whether the editor bridge accepted the last request
	 * @param clients connected MCP clients
	 * @param token the current control token (shown masked unless revealed)
	 * @param socketPath the local unix socket path shown as connection info
	 * @param mcpCommand the platform MCP command ({@code <appimage> --mcp} on packaged builds)
	 * @param claudeCodeCommand the one-line {@code claude mcp add ...} command
	 * @param jsonConfig the MCP client JSON configuration block
	 * @param lastSummary summary of the last agent change, or {@code null} when there is none
	 * @param recentActivity newest-first activity entries
	 */
	record Snapshot(boolean enabled, boolean listening, boolean editorReady, List<Client> clients,
			String token, String socketPath, String mcpCommand, String claudeCodeCommand,
			String jsonConfig, String lastSummary, List<ActivityEntry> recentActivity) {

		public Snapshot {
			clients = clients == null ? List.of() : List.copyOf(clients);
			recentActivity = recentActivity == null ? List.of() : List.copyOf(recentActivity);
		}

		/** The derived connection status. */
		public Status status() {
			if (!enabled) {
				return Status.OFF;
			}
			if (!listening) {
				return Status.UNAVAILABLE;
			}
			return clients.isEmpty() ? Status.LISTENING : Status.CONNECTED;
		}

		public boolean connected() {
			return status() == Status.CONNECTED;
		}
	}

	/** The current state; cheap snapshot read. */
	Snapshot snapshot();

	/** Subscribes to state changes (invoked on the publisher's thread; adapters hop to FX). */
	AutoCloseable subscribe(Consumer<Snapshot> listener);

	/** Enables or disables AI control. */
	void setEnabled(boolean enabled);

	/** Rotates the control token; connected agents must reconnect. */
	void regenerateToken();

	/** Reverts the last agent change (undo the AI batch); no-op when there is nothing to revert. */
	void revertLastAiChange();

	/**
	 * Feeds a batch of transient timeline highlights (pipe for the future {@code AgentService}
	 * events: apply result → highlights).
	 */
	void applyHighlights(List<AgentHighlightUpdate> updates);

	/** Subscribes to transient timeline highlights published through {@link #applyHighlights}. */
	AutoCloseable onHighlights(Consumer<List<AgentHighlightUpdate>> listener);

	@Override
	default void close() {
		// UI adapters own no resources by default
	}
}
