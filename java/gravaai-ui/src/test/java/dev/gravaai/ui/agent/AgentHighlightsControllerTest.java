package dev.gravaai.ui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link AgentHighlightsController}: mapping, clamping/TTL and clear-on-user-edit seams. */
class AgentHighlightsControllerTest {

	@DisplayName("an agent batch becomes display bands and clears after the React TTL")
	@Test
	void appliesAndExpires() {
		PlaceholderAgentUiService service = new PlaceholderAgentUiService();
		FakeScheduler scheduler = new FakeScheduler();
		try (AgentHighlightsController controller = new AgentHighlightsController(service, scheduler)) {
			service.applyHighlights(List.of(
					new AgentHighlightUpdate(AgentHighlightUpdate.Kind.CUT, 500, 500, "join"),
					new AgentHighlightUpdate(AgentHighlightUpdate.Kind.CHANGED, 800, 1_400, "trim")));
			assertEquals(2, controller.highlights().size());
			assertEquals(AgentHighlightUpdate.Kind.CUT, controller.highlights().get(0).kind());
			assertEquals("✦ AI cut", controller.highlights().get(0).displayLabel());
			assertEquals(2_500, AgentHighlightsController.HIGHLIGHT_DURATION_MS);
			assertEquals(2_500, scheduler.delays.get(0));
			scheduler.runAll();
			assertTrue(controller.highlights().isEmpty());
		}
	}

	@DisplayName("a second batch replaces the bands and cancels the pending clear")
	@Test
	void secondBatchReplaces() {
		PlaceholderAgentUiService service = new PlaceholderAgentUiService();
		FakeScheduler scheduler = new FakeScheduler();
		try (AgentHighlightsController controller = new AgentHighlightsController(service, scheduler)) {
			service.applyHighlights(List.of(
					new AgentHighlightUpdate(AgentHighlightUpdate.Kind.ADDED, 0, 100, "zoom")));
			assertEquals(1, scheduler.pending.size());
			service.applyHighlights(List.of(
					new AgentHighlightUpdate(AgentHighlightUpdate.Kind.CUT, 10, 10, "a"),
					new AgentHighlightUpdate(AgentHighlightUpdate.Kind.CUT, 20, 20, "b")));
			assertEquals(2, controller.highlights().size());
			assertTrue(scheduler.cancelled.get(0), "the first clear must be cancelled");
			scheduler.runAll();
			assertTrue(controller.highlights().isEmpty());
		}
	}

	@DisplayName("clear() removes bands immediately (user edit after the agent batch)")
	@Test
	void explicitClear() {
		PlaceholderAgentUiService service = new PlaceholderAgentUiService();
		try (AgentHighlightsController controller = new AgentHighlightsController(service,
				new FakeScheduler())) {
			service.applyHighlights(List.of(
					new AgentHighlightUpdate(AgentHighlightUpdate.Kind.CHANGED, 0, 100, "x")));
			assertFalse(controller.highlights().isEmpty());
			controller.clear();
			assertTrue(controller.highlights().isEmpty());
		}
	}

	@DisplayName("ids are monotonic and mapping keeps the reported spans")
	@Test
	void mapping() {
		AtomicLong ids = new AtomicLong();
		List<AgentHighlightView> views = AgentHighlightsController.map(List.of(
				new AgentHighlightUpdate(AgentHighlightUpdate.Kind.ADDED, 100, 200, "zoom"),
				new AgentHighlightUpdate(AgentHighlightUpdate.Kind.CUT, 300, 300, "cut")), ids);
		assertEquals("agent-highlight-1", views.get(0).id());
		assertEquals("agent-highlight-2", views.get(1).id());
		assertEquals(100, views.get(0).startMs());
		assertEquals(200, views.get(0).endMs());
		assertEquals("zoom", views.get(0).displayLabel());
	}

	/** Records scheduled tasks; the test decides when time passes. */
	private static final class FakeScheduler implements AgentHighlightsController.Scheduler {
		private final List<Runnable> pending = new ArrayList<>();
		private final List<Long> delays = new ArrayList<>();
		private final List<Boolean> cancelled = new ArrayList<>();

		@Override
		public AutoCloseable schedule(Runnable task, long delayMillis) {
			pending.add(task);
			delays.add(delayMillis);
			int index = cancelled.size();
			cancelled.add(false);
			return () -> cancelled.set(index, true);
		}

		void runAll() {
			List<Runnable> tasks = List.copyOf(pending);
			pending.clear();
			tasks.forEach(Runnable::run);
		}
	}
}
