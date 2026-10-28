package dev.gravaai.ui.agent;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.util.Duration;

/**
 * Controller seam for the transient agent timeline highlights (plan §5.16/P3.2, port of
 * {@code useAgentBridge}'s {@code applyHighlights} + {@code AgentTimelineHighlights.tsx}).
 *
 * <p>A future {@code AgentService} feeds the result of every agent batch through
 * {@link AgentUiService#applyHighlights(List)}; this controller turns the batch into display bands,
 * publishes them through {@link #highlights()} (mounted by the timeline overlay) and clears them
 * after {@code HIGHLIGHT_DURATION_MS} like the React bridge ({@code 2_500} ms).
 *
 * <p>Threading: callbacks and {@link #apply(List)} must run on the FX thread (adapters hop); the
 * clearing timer is a JavaFX {@link PauseTransition}. Tests inject a {@link Scheduler}.
 */
public final class AgentHighlightsController implements AutoCloseable {

	/** React {@code HIGHLIGHT_DURATION_MS}. */
	public static final long HIGHLIGHT_DURATION_MS = 2_500;

	/** Schedules the auto-clear; injectable for tests without a JavaFX toolkit. */
	public interface Scheduler {
		/** Runs {@code task} after {@code delayMillis}; closing the handle cancels it. */
		AutoCloseable schedule(Runnable task, long delayMillis);
	}

	/** Default scheduler: a {@link PauseTransition} on the FX thread. */
	static final Scheduler FX_SCHEDULER = (task, delayMillis) -> {
		PauseTransition pause = new PauseTransition(Duration.millis(delayMillis));
		pause.setOnFinished(event -> task.run());
		pause.play();
		return pause::stop;
	};

	private final ObservableList<AgentHighlightView> highlights = FXCollections.observableArrayList();
	private final AtomicLong ids = new AtomicLong();
	private final AutoCloseable subscription;
	private final Scheduler scheduler;
	private AutoCloseable pendingClear;

	public AgentHighlightsController(AgentUiService service) {
		this(service, FX_SCHEDULER);
	}

	AgentHighlightsController(AgentUiService service, Scheduler scheduler) {
		this.scheduler = scheduler;
		this.subscription = service.onHighlights(this::apply);
	}

	/** The bands the timeline overlay draws; FX thread only. */
	public ObservableList<AgentHighlightView> highlights() {
		return highlights;
	}

	/** Turn one agent batch into bands and schedule the auto-clear. */
	public void apply(List<AgentHighlightUpdate> updates) {
		cancelPendingClear();
		if (updates == null || updates.isEmpty()) {
			highlights.clear();
			return;
		}
		highlights.setAll(map(updates, ids));
		pendingClear = scheduler.schedule(this::clear, HIGHLIGHT_DURATION_MS);
	}

	/** Removes all bands (user edit after an AI batch, project switch). */
	public void clear() {
		cancelPendingClear();
		highlights.clear();
	}

	/** Pure mapping (ids are generated in order, spans kept as the agent reported them). */
	static List<AgentHighlightView> map(List<AgentHighlightUpdate> updates, AtomicLong ids) {
		return updates.stream()
				.map(update -> new AgentHighlightView("agent-highlight-" + ids.incrementAndGet(),
						update.kind(), update.startMs(), update.endMs(), update.label()))
				.toList();
	}

	@Override
	public void close() {
		cancelPendingClear();
		closeQuietly(subscription);
	}

	private void cancelPendingClear() {
		if (pendingClear != null) {
			closeQuietly(pendingClear);
			pendingClear = null;
		}
	}

	private static void closeQuietly(AutoCloseable handle) {
		try {
			handle.close();
		} catch (Exception ignored) {
			// cancelling a timer is best-effort
		}
	}
}
