package dev.gravaai.ui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link PlaceholderAgentUiService}: behaviour the real services.agent adapter must mirror. */
class PlaceholderAgentUiServiceTest {

	@DisplayName("demo state reports a connected client and recent activity")
	@Test
	void demoState() {
		PlaceholderAgentUiService service = new PlaceholderAgentUiService();
		AgentUiService.Snapshot snapshot = service.snapshot();
		assertTrue(snapshot.enabled());
		assertTrue(snapshot.listening());
		assertTrue(snapshot.editorReady());
		assertEquals(AgentUiService.Status.CONNECTED, snapshot.status());
		assertEquals("Claude Code", snapshot.clients().get(0).client());
		assertEquals(5, snapshot.recentActivity().size());
		assertEquals("Trimmed 4 silences and added 2 zooms", snapshot.lastSummary());
		assertTrue(snapshot.token().startsWith("gv_"));
		assertTrue(snapshot.mcpCommand().contains("--mcp"));
		assertTrue(snapshot.jsonConfig().contains("mcpServers"));
	}

	@DisplayName("disabling clears clients and shows Off; enabling listens again")
	@Test
	void toggleEnabled() {
		PlaceholderAgentUiService service = new PlaceholderAgentUiService();
		service.setEnabled(false);
		AgentUiService.Snapshot off = service.snapshot();
		assertEquals(AgentUiService.Status.OFF, off.status());
		assertTrue(off.clients().isEmpty());
		assertFalse(off.listening());
		service.setEnabled(true);
		assertEquals(AgentUiService.Status.LISTENING, service.snapshot().status());
	}

	@DisplayName("a disabled placeholder starts off with no activity")
	@Test
	void disabledFactory() {
		AgentUiService.Snapshot snapshot = PlaceholderAgentUiService.disabled().snapshot();
		assertEquals(AgentUiService.Status.OFF, snapshot.status());
		assertNull(snapshot.lastSummary());
		assertTrue(snapshot.recentActivity().isEmpty());
	}

	@DisplayName("regenerating rotates the token and records activity")
	@Test
	void regenerateToken() {
		PlaceholderAgentUiService service = new PlaceholderAgentUiService();
		String before = service.snapshot().token();
		int activityBefore = service.snapshot().recentActivity().size();
		service.regenerateToken();
		assertNotEquals(before, service.snapshot().token());
		assertEquals(activityBefore + 1, service.snapshot().recentActivity().size());
	}

	@DisplayName("highlights reach the listener and recent activity")
	@Test
	void applyHighlights() {
		PlaceholderAgentUiService service = new PlaceholderAgentUiService();
		List<List<AgentHighlightUpdate>> batches = new CopyOnWriteArrayList<>();
		service.onHighlights(batches::add);
		List<AgentHighlightUpdate> updates = List.of(
				new AgentHighlightUpdate(AgentHighlightUpdate.Kind.CUT, 1_000, 1_200, "pause"),
				new AgentHighlightUpdate(AgentHighlightUpdate.Kind.ADDED, 2_000, 2_500, "zoom"));
		service.applyHighlights(updates);
		assertEquals(1, batches.size());
		assertEquals(2, batches.get(0).size());
		assertEquals("Highlight added: zoom", service.snapshot().recentActivity().get(0).label());
	}

	@DisplayName("revert clears the last summary and records the revert")
	@Test
	void revertLastChange() {
		PlaceholderAgentUiService service = new PlaceholderAgentUiService();
		service.revertLastAiChange();
		assertNull(service.snapshot().lastSummary());
		assertTrue(service.snapshot().recentActivity().get(0).label().startsWith("Reverted:"));
	}

	@DisplayName("subscribers receive the current snapshot immediately and later changes")
	@Test
	void subscribe() {
		PlaceholderAgentUiService service = new PlaceholderAgentUiService();
		List<AgentUiService.Snapshot> seen = new ArrayList<>();
		AutoCloseable handle = service.subscribe(seen::add);
		assertEquals(1, seen.size());
		service.setEnabled(false);
		assertEquals(2, seen.size());
		try {
			handle.close();
		} catch (Exception error) {
			throw new AssertionError(error);
		}
		service.setEnabled(true);
		assertEquals(2, seen.size());
	}
}