package dev.gravaai.ui.agent;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * In-memory {@link AgentUiService} used until Lead wires {@code services.agent} (plan §5.16,
 * P3.2): a deterministic demo state for the Settings page, the header chip and the timeline
 * highlights, plus the same behaviour the real adapter must provide.
 */
public final class PlaceholderAgentUiService implements AgentUiService {

	private static final AtomicLong TOKEN_SERIAL = new AtomicLong(1);

	private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<List<AgentHighlightUpdate>>> highlightListeners =
			new CopyOnWriteArrayList<>();
	private final List<ActivityEntry> activity = new ArrayList<>();

	private boolean enabled = true;
	private boolean listening = true;
	private boolean editorReady = true;
	private String token;
	private @Nullable String lastSummary = "Trimmed 4 silences and added 2 zooms";
	private List<Client> clients = List.of(new Client("Claude Code", System.currentTimeMillis() - 45_000));
	private String socketPath = "~/.local/share/Gravaai/agent.sock";
	private String mcpCommand = "gravaai --mcp --ozone-platform=headless";
	private String claudeCodeCommand =
			"claude mcp add gravaai -- gravaai --mcp --ozone-platform=headless";
	private String jsonConfig = """
			{
			  "mcpServers": {
			    "gravaai": {
			      "command": "gravaai",
			      "args": ["--mcp", "--ozone-platform=headless"]
			    }
			  }
			}""";

	/** Demo state with a connected client and a little recent activity. */
	public PlaceholderAgentUiService() {
		this.token = "gv_" + Long.toHexString(0x5eed_0000L + TOKEN_SERIAL.getAndIncrement());
		long now = System.currentTimeMillis();
		// Newest first, like the ActivityEntry contract.
		activity.add(new ActivityEntry(now - 8_000, "edit captions (2 cues)", false));
		activity.add(new ActivityEntry(now - 20_000, "frames 1280x720 (3 frames)", false));
		activity.add(new ActivityEntry(now - 38_000, "add zoom regions (2)", false));
		activity.add(new ActivityEntry(now - 40_000, "apply cut_pauses (4 pauses)", false));
		activity.add(new ActivityEntry(now - 45_000, "inspect timeline", false));
	}

	/** An empty service: AI control off, no clients and no activity (fresh install). */
	public static PlaceholderAgentUiService disabled() {
		PlaceholderAgentUiService service = new PlaceholderAgentUiService();
		service.enabled = false;
		service.listening = false;
		service.clients = List.of();
		service.lastSummary = null;
		service.activity.clear();
		return service;
	}

	@Override
	public Snapshot snapshot() {
		return new Snapshot(enabled, listening, editorReady, clients, token, socketPath, mcpCommand,
				claudeCodeCommand, jsonConfig, lastSummary, activity);
	}

	@Override
	public AutoCloseable subscribe(Consumer<Snapshot> listener) {
		if (listener == null) {
			return () -> { };
		}
		listeners.add(listener);
		listener.accept(snapshot());
		return () -> listeners.remove(listener);
	}

	@Override
	public void setEnabled(boolean nextEnabled) {
		if (enabled == nextEnabled) {
			return;
		}
		enabled = nextEnabled;
		listening = nextEnabled;
		clients = nextEnabled ? clients : List.of();
		record(nextEnabled ? "AI control enabled" : "AI control disabled", false);
		publish();
	}

	@Override
	public void regenerateToken() {
		token = "gv_" + Long.toHexString(0x5eed_0000L + TOKEN_SERIAL.getAndIncrement());
		record("Control token regenerated", false);
		publish();
	}

	@Override
	public void revertLastAiChange() {
		if (lastSummary == null) {
			return;
		}
		record("Reverted: " + lastSummary, false);
		lastSummary = null;
		publish();
	}

	@Override
	public void applyHighlights(List<AgentHighlightUpdate> updates) {
		if (updates == null || updates.isEmpty()) {
			return;
		}
		for (AgentHighlightUpdate update : updates) {
			record("Highlight " + update.kind().wire() + ": " + update.label(), false);
		}
		List<AgentHighlightUpdate> batch = List.copyOf(updates);
		for (Consumer<List<AgentHighlightUpdate>> listener : highlightListeners) {
			listener.accept(batch);
		}
		publish();
	}

	@Override
	public AutoCloseable onHighlights(Consumer<List<AgentHighlightUpdate>> listener) {
		if (listener == null) {
			return () -> { };
		}
		highlightListeners.add(listener);
		return () -> highlightListeners.remove(listener);
	}

	/** Test/snapshot helper: simulate a client connecting or disconnecting. */
	public void setClients(List<Client> nextClients) {
		clients = nextClients == null ? List.of() : List.copyOf(nextClients);
		publish();
	}

	/** Test/snapshot helper: simulate the editor bridge reporting readiness. */
	public void setEditorReady(boolean ready) {
		editorReady = ready;
		publish();
	}

	private void record(String label, boolean failed) {
		// Newest first.
		activity.add(0, new ActivityEntry(System.currentTimeMillis(), label, failed));
	}

	private void publish() {
		Snapshot snapshot = snapshot();
		for (Consumer<Snapshot> listener : listeners) {
			listener.accept(snapshot);
		}
	}
}