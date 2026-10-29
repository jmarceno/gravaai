package dev.gravaai.services.agent;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.agent.AgentJobRegistry.Job;
import dev.gravaai.services.agent.AgentJobRegistry.Kind;
import dev.gravaai.services.agent.AgentJobRegistry.Patch;
import dev.gravaai.services.agent.AgentJobRegistry.Status;
import dev.gravaai.services.agent.AgentTypes.AgentConnectionInfo;
import dev.gravaai.services.agent.AgentTypes.AgentToolHandler;
import dev.gravaai.services.agent.AgentTypes.AgentToolResult;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI-control service lifecycle (port of {@code electron/agent/service.ts} and
 * {@code register/agent.ts}, plan §5.16/P3.2): owns the control server, the settings toggle, token
 * rotation, the editor-ready state, the activity log/revert/highlight surfaces the UI needs, and the
 * MCP client configuration text.
 *
 * <p>It is the services-side twin of {@code dev.gravaai.ui.agent.AgentUiService}: the ui-kit adapter
 * maps these immutable {@link Snapshot}s to the UI port. The AppContext supplies the settings and
 * revert ports and forwards editor lifecycle events.
 */
public final class AgentService implements AutoCloseable {

	/** Connection state derived from the control-socket status. */
	public enum ConnectionStatus {
		OFF,
		LISTENING,
		CONNECTED,
		UNAVAILABLE
	}

	/** One connected MCP client. */
	public record Client(String client, long sinceMillis) {}

	/** One entry of the recent-activity list (agent tool call / edit / error). */
	public record ActivityEntry(long atMillis, String label, boolean failed) {}

	/**
	 * One immutable view of the agent service (mirror of the UI port snapshot).
	 *
	 * @param token the current token, or null when the token file is not readable (the UI shows
	 *     its placeholder)
	 * @param lastSummary the last non-failed activity label, or null before any activity
	 */
	public record Snapshot(boolean enabled, boolean listening, boolean editorReady,
			List<Client> clients, @Nullable String token, String socketPath, String mcpCommand,
			String claudeCodeCommand, String jsonConfig, @Nullable String lastSummary,
			List<ActivityEntry> recentActivity) {
		public Snapshot {
			clients = clients == null ? List.of() : List.copyOf(clients);
			recentActivity = recentActivity == null ? List.of() : List.copyOf(recentActivity);
		}

		/** The derived connection status. */
		public ConnectionStatus status() {
			if (!enabled) {
				return ConnectionStatus.OFF;
			}
			if (!listening) {
				return ConnectionStatus.UNAVAILABLE;
			}
			return clients.isEmpty() ? ConnectionStatus.LISTENING : ConnectionStatus.CONNECTED;
		}

		public boolean connected() {
			return status() == ConnectionStatus.CONNECTED;
		}
	}

	/** One transient timeline highlight (port of {@code AgentHighlight}). */
	public record Highlight(Kind kind, long startMs, long endMs, String label) {
		public enum Kind {
			CUT("cut"),
			ADDED("added"),
			CHANGED("changed");

			private final String wire;

			Kind(String wire) {
				this.wire = wire;
			}

			public String wire() {
				return wire;
			}

			public static Kind fromWire(String value) {
				for (Kind kind : values()) {
					if (kind.wire.equals(value)) {
						return kind;
					}
				}
				return CHANGED;
			}
		}

	}

	/** Settings store port for the {@code aiControlEnabled} bit. */
	public interface SettingsPort {
		boolean aiControlEnabled();

		void setAiControlEnabled(boolean enabled);
	}

	/** Reverts the last AI change (the renderer owns the revert stack). */
	public interface RevertPort {
		CompletableFuture<Boolean> revertLastAiChange();
	}

	/** MCP client configuration text shown in Settings → AI control. */
	public record McpConfig(String command, List<String> args, String claudeCodeCommand,
			String json) {
		public McpConfig {
			args = args == null ? List.of() : List.copyOf(args);
		}

		/** Builds the config for an app executable path ({@code <appimage> --mcp}). */
		public static McpConfig of(String executablePath, List<String> mcpArguments) {
			String command = executablePath == null || executablePath.isBlank() ? "gravaai"
					: executablePath;
			List<String> args = mcpArguments == null ? List.of("--mcp")
					: List.copyOf(mcpArguments);
			String quoted = command.contains(" ") ? "\"" + command + "\"" : command;
			String claudeCodeCommand = "claude mcp add gravaai -- " + quoted + " "
					+ String.join(" ", args);
			@Nullable Map<String, Object> server = new java.util.LinkedHashMap<>();
			server.put("command", command);
			server.put("args", args);
			Map<String, Object> root = new java.util.LinkedHashMap<>();
			root.put("mcpServers", Map.of("gravaai", server));
			return new McpConfig(command, args, claudeCodeCommand, Json.stringify(root));
		}
	}

	private static final System.Logger LOG = System.getLogger("gravaai.agent.service");
	private static final int MAX_ACTIVITY = 50;

	private final Path socketPath;
	private final Path tokenFile;
	private final AgentToolHandler handler;
	private final AgentJobRegistry jobs;
	private final SettingsPort settings;
	private final RevertPort revertPort;
	private final McpConfig mcpConfig;
	private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<List<Highlight>>> highlightListeners = new CopyOnWriteArrayList<>();
	private final List<ActivityEntry> activity = new CopyOnWriteArrayList<>();

	/** Null until the control server is started (or after a failed start). */
	private volatile @Nullable AgentControlServer server;
	private volatile boolean listening;
	private volatile boolean editorReady;
	private volatile @Nullable String lastSummary;

	public AgentService(Path socketPath, Path tokenFile, AgentToolHandler handler,
			AgentJobRegistry jobs, SettingsPort settings, RevertPort revertPort,
			String mcpExecutablePath, List<String> mcpArguments) {
		this.socketPath = socketPath;
		this.tokenFile = tokenFile;
		this.handler = handler;
		this.jobs = jobs;
		this.settings = settings;
		this.revertPort = revertPort;
		this.mcpConfig = McpConfig.of(mcpExecutablePath, mcpArguments);
	}

	/** Starts the control server only when the setting is on (launch path). */
	public synchronized void startIfEnabled() {
		if (settings.aiControlEnabled()) {
			start();
		}
	}

	/** Starts listening (idempotent); broadcast a fresh snapshot. */
	public synchronized void start() {
		if (listening) {
			return;
		}
		try {
			if (server == null) {
				server = new AgentControlServer(socketPath, tokenFile, handler,
						clients -> broadcast());
			}
			server.start();
			listening = true;
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "could not start the agent control server: " + error);
			listening = false;
		}
		broadcast();
	}

	/** Stops listening (idempotent); broadcast a fresh snapshot. */
	public synchronized void stop() {
		if (server != null) {
			server.stop();
		}
		listening = false;
		broadcast();
	}

	/** Settings → AI control toggle: persists the setting, then starts/stops. */
	public synchronized void setEnabled(boolean enabled) {
		settings.setAiControlEnabled(enabled);
		if (enabled) {
			start();
		} else {
			stop();
		}
	}

	public boolean isEnabled() {
		try {
			return settings.aiControlEnabled();
		} catch (RuntimeException error) {
			return false;
		}
	}

	public boolean isListening() {
		return listening;
	}

	/** Rotates the token and drops connected agents (they reconnect with the new token). */
	public synchronized void regenerateToken() {
		try {
			if (server != null) {
				server.regenerateToken();
				server.restart();
			} else {
				AgentToken.write(tokenFile, AgentToken.generate());
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "could not regenerate the agent token: " + error);
		}
		broadcast();
	}

	/** Reverts the last AI change through the renderer-owned revert stack. */
	public CompletableFuture<Boolean> revertLastAiChange() {
		if (revertPort == null) {
			return CompletableFuture.completedFuture(false);
		}
		return revertPort.revertLastAiChange().thenApply(reverted -> {
			if (Boolean.TRUE.equals(reverted)) {
				// The revert entry is activity, not "the last AI change" the chip offers to revert.
				recordActivity("Reverted last AI change", false);
				lastSummary = null;
				broadcast();
			}
			return reverted;
		});
	}

	/** Forwards a renderer job report into the registry (service.ts {@code agent:job-report}). */
	public void applyJobReport(String jobId, String statusWire, Double progress, String message,
			String error, Object result) {
		Job job = jobs.get(jobId);
		if (job == null || job.kind() == Kind.MEETING) {
			return;
		}
		Status status = statusWire == null ? null : Status.fromWire(statusWire);
		jobs.update(jobId, new Patch(status, progress, message, error, result));
		broadcast();
	}

	/** The editor mounted its bridge. */
	public void editorReady(boolean ready) {
		editorReady = ready;
		broadcast();
	}

	/** The editor window closed before a job finished. */
	public void editorClosed() {
		editorReady = false;
		jobs.failRunning(List.of(Kind.TRANSCRIBE, Kind.EXPORT),
				"the editor window closed before the job finished");
		broadcast();
	}

	/** The editor unmounted/reloaded before a job finished. */
	public void editorReloaded() {
		editorReady = false;
		jobs.failRunning(List.of(Kind.TRANSCRIBE, Kind.EXPORT),
				"the editor reloaded before the job finished");
		broadcast();
	}

	/** Appends one activity entry (newest first) and broadcasts. */
	public void recordActivity(String label, boolean failed) {
		activity.add(0, new ActivityEntry(System.currentTimeMillis(),
				label == null ? "" : label, failed));
		while (activity.size() > MAX_ACTIVITY) {
			activity.remove(activity.size() - 1);
		}
		if (!failed) {
			lastSummary = label;
		}
		broadcast();
	}

	/** Feeds transient highlights from an edit result. */
	public void applyHighlights(List<Highlight> highlights) {
		List<Highlight> copy = highlights == null ? List.of() : List.copyOf(highlights);
		for (Consumer<List<Highlight>> listener : highlightListeners) {
			try {
				listener.accept(copy);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "highlight listener failed: " + error);
			}
		}
	}

	/** Subscribes to transient highlights. */
	public AutoCloseable onHighlights(Consumer<List<Highlight>> listener) {
		highlightListeners.add(listener);
		return () -> highlightListeners.remove(listener);
	}

	/** Subscribes to state snapshots; the current snapshot is delivered immediately. */
	public AutoCloseable subscribe(Consumer<Snapshot> listener) {
		listeners.add(listener);
		listener.accept(snapshot());
		return () -> listeners.remove(listener);
	}

	/** Cheap immutable snapshot for the FX thread. */
	public Snapshot snapshot() {
		String token = null;
		try {
			token = AgentToken.read(tokenFile).orElse(null);
		} catch (RuntimeException error) {
			// Token unreadable: leave it null; the UI shows the placeholder.
		}
		List<Client> clients = new ArrayList<>();
		AgentControlServer active = server;
		if (active != null && listening) {
			for (AgentConnectionInfo client : active.status().clients()) {
				clients.add(new Client(client.client(), client.since()));
			}
		}
		return new Snapshot(isEnabled(), listening, editorReady, clients, token,
				socketPath.toString(), mcpConfig.command() + " " + String.join(" ", mcpConfig.args()),
				mcpConfig.claudeCodeCommand(), mcpConfig.json(), lastSummary, activity);
	}

	/** @return the MCP client configuration surfaced in Settings → AI control */
	public McpConfig mcpConfig() {
		return mcpConfig;
	}

	public Path socketPath() {
		return socketPath;
	}

	/** The current token, creating it when AI control was never enabled (settings display). */
	public String token() {
		try {
			return AgentToken.readOrCreate(tokenFile);
		} catch (IOException error) {
			throw new IllegalStateException("could not read the agent token", error);
		}
	}

	private void broadcast() {
		Snapshot snapshot = snapshot();
		for (Consumer<Snapshot> listener : listeners) {
			try {
				listener.accept(snapshot);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "agent snapshot listener failed: " + error);
			}
		}
	}

	@Override
	public synchronized void close() {
		stop();
		listeners.clear();
		highlightListeners.clear();
		server = null;
	}
}
