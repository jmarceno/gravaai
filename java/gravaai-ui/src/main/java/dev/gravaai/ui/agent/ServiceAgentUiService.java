package dev.gravaai.ui.agent;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import dev.gravaai.services.agent.AgentService;

/**
 * {@link AgentUiService} adapter over the platform's {@code services.agent.AgentService}
 * (P3.2, plan §5.16): the Settings page, the editor header chip and the timeline highlights talk
 * only to the UI port; this class maps the service snapshots/highlights one-to-one.
 *
 * <p>Lifecycle: the adapter does NOT own the {@link AgentService} - {@code AppContext} owns it
 * (start/stop with the app/window) and passes it in. Adapter {@link #close()} only stops forwarding;
 * closing the service is the app's call.
 */
public final class ServiceAgentUiService implements AgentUiService {

	private final AgentService agentService;

	public ServiceAgentUiService(AgentService agentService) {
		this.agentService = Objects.requireNonNull(agentService, "agentService");
	}

	/** The wrapped platform service (AppContext wiring/diagnostics). */
	public AgentService agentService() {
		return agentService;
	}

	@Override
	public Snapshot snapshot() {
		return map(agentService.snapshot());
	}

	@Override
	public AutoCloseable subscribe(Consumer<Snapshot> listener) {
		if (listener == null) {
			return () -> { };
		}
		return agentService.subscribe(next -> listener.accept(map(next)));
	}

	@Override
	public void setEnabled(boolean enabled) {
		agentService.setEnabled(enabled);
	}

	@Override
	public void regenerateToken() {
		agentService.regenerateToken();
	}

	@Override
	public void revertLastAiChange() {
		// The service records the revert activity and clears the last summary on completion; the
		// snapshot subscription updates the chip.
		agentService.revertLastAiChange();
	}

	@Override
	public void applyHighlights(List<AgentHighlightUpdate> updates) {
		if (updates == null || updates.isEmpty()) {
			return;
		}
		agentService.applyHighlights(toServiceHighlights(updates));
	}

	@Override
	public AutoCloseable onHighlights(Consumer<List<AgentHighlightUpdate>> listener) {
		if (listener == null) {
			return () -> { };
		}
		return agentService.onHighlights(highlights -> listener.accept(mapHighlights(highlights)));
	}

	@Override
	public void close() {
		// The service is AppContext-owned; do not stop AI control when a page/chip is disposed.
	}

	/** Maps one service snapshot to the UI port snapshot. */
	static Snapshot map(AgentService.Snapshot source) {
		List<Client> clients = source.clients().stream()
				.map(client -> new Client(client.client(), client.sinceMillis()))
				.toList();
		List<ActivityEntry> activity = source.recentActivity().stream()
				.map(entry -> new ActivityEntry(entry.atMillis(), entry.label(), entry.failed()))
				.toList();
		return new Snapshot(source.enabled(), source.listening(), source.editorReady(), clients,
				source.token(), source.socketPath(), source.mcpCommand(), source.claudeCodeCommand(),
				source.jsonConfig(), source.lastSummary(), activity);
	}

	/** Maps the service highlights to UI highlight updates. */
	static List<AgentHighlightUpdate> mapHighlights(List<AgentService.Highlight> highlights) {
		if (highlights == null) {
			return List.of();
		}
		return highlights.stream()
				.map(highlight -> new AgentHighlightUpdate(
						AgentHighlightUpdate.Kind.fromWire(highlight.kind().wire()),
						highlight.startMs(), highlight.endMs(), highlight.label()))
				.toList();
	}

	/** Maps UI highlight updates to the service highlight records. */
	static List<AgentService.Highlight> toServiceHighlights(List<AgentHighlightUpdate> updates) {
		if (updates == null) {
			return List.of();
		}
		return updates.stream()
				.map(update -> new AgentService.Highlight(
						AgentService.Highlight.Kind.fromWire(update.kind().wire()), update.startMs(),
						update.endMs(), update.label()))
				.toList();
	}
}
