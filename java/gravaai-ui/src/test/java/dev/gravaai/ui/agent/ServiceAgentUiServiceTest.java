package dev.gravaai.ui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import dev.gravaai.services.agent.AgentJobRegistry;
import dev.gravaai.services.agent.AgentService;
import dev.gravaai.services.agent.AgentTypes.AgentToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** {@link ServiceAgentUiService}: mapping + behaviour over the real services.agent.AgentService. */
@Timeout(30)
class ServiceAgentUiServiceTest {

	@TempDir
	Path tempDir;

	private AgentService service;
	private ServiceAgentUiService ui;
	private boolean enabledSetting;

	@BeforeEach
	void setUp() {
		service = new AgentService(tempDir.resolve("agent.sock"), tempDir.resolve("agent-token"),
				(request, context) -> CompletableFuture.completedFuture(AgentToolResult.text("ok")),
				new AgentJobRegistry(), new AgentService.SettingsPort() {
					@Override
					public boolean aiControlEnabled() {
						return enabledSetting;
					}

					@Override
					public void setAiControlEnabled(boolean enabled) {
						enabledSetting = enabled;
					}
				}, () -> CompletableFuture.completedFuture(true), "gravaai",
				List.of("--mcp", "--ozone-platform=headless"));
		ui = new ServiceAgentUiService(service);
	}

	@AfterEach
	void tearDown() {
		service.close();
	}

	@DisplayName("the service snapshot maps one-to-one to the UI port")
	@Test
	void snapshotMapping() {
		AgentUiService.Snapshot snapshot = ui.snapshot();
		assertEquals(false, snapshot.enabled());
		assertEquals(AgentUiService.Status.OFF, snapshot.status());
		assertTrue(snapshot.clients().isEmpty());
		assertTrue(snapshot.mcpCommand().contains("--mcp"));
		assertTrue(snapshot.claudeCodeCommand().startsWith("claude mcp add gravaai"));
		assertTrue(snapshot.jsonConfig().contains("mcpServers"));
		assertEquals(service.socketPath().toString(), snapshot.socketPath());
	}

	@DisplayName("enabling starts the control server; the snapshot becomes LISTENING")
	@Test
	void enableStartsServer() {
		ui.setEnabled(true);
		assertTrue(service.isListening());
		assertEquals(AgentUiService.Status.LISTENING, ui.snapshot().status());
		ui.setEnabled(false);
		assertFalse(service.isListening());
		assertEquals(AgentUiService.Status.OFF, ui.snapshot().status());
	}

	@DisplayName("subscribers see the current snapshot immediately and later activity")
	@Test
	void subscription() {
		List<AgentUiService.Snapshot> seen = new CopyOnWriteArrayList<>();
		AutoCloseable handle = ui.subscribe(seen::add);
		assertEquals(1, seen.size());
		service.recordActivity("apply cut_pauses (4 pauses)", false);
		assertTrue(seen.size() >= 2);
		assertEquals("apply cut_pauses (4 pauses)",
				seen.get(seen.size() - 1).lastSummary());
		assertEquals("apply cut_pauses (4 pauses)",
				seen.get(seen.size() - 1).recentActivity().get(0).label());
		try {
			handle.close();
		} catch (Exception error) {
			throw new AssertionError(error);
		}
	}

	@DisplayName("regenerateToken rotates the token exposed to the page")
	@Test
	void regenerateToken() {
		String before = service.token();
		ui.regenerateToken();
		String after = ui.snapshot().token();
		assertNotNull(after);
		assertFalse(before.equals(after));
	}

	@DisplayName("revert clears the last summary through the service revert port")
	@Test
	void revert() throws Exception {
		service.recordActivity("apply zoom", false);
		assertEquals("apply zoom", ui.snapshot().lastSummary());
		ui.revertLastAiChange();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (ui.snapshot().lastSummary() != null && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertNull(ui.snapshot().lastSummary());
	}

	@DisplayName("highlights round-trip between the UI updates and the service records")
	@Test
	void highlights() {
		List<List<AgentHighlightUpdate>> batches = new ArrayList<>();
		AutoCloseable handle = ui.onHighlights(batches::add);
		List<AgentHighlightUpdate> updates = List.of(
				new AgentHighlightUpdate(AgentHighlightUpdate.Kind.CUT, 100, 100, "join"),
				new AgentHighlightUpdate(AgentHighlightUpdate.Kind.ADDED, 200, 400, "zoom"));
		ui.applyHighlights(updates);
		assertEquals(1, batches.size());
		assertEquals(2, batches.get(0).size());
		assertEquals(AgentHighlightUpdate.Kind.CUT, batches.get(0).get(0).kind());
		assertEquals(100, batches.get(0).get(0).startMs());
		try {
			handle.close();
		} catch (Exception error) {
			throw new AssertionError(error);
		}
	}

	@DisplayName("the close() of the adapter does not stop the AppContext-owned service")
	@Test
	void closeDoesNotOwnService() {
		ui.setEnabled(true);
		ui.close();
		assertTrue(service.isListening());
		assertTrue(service.isEnabled());
	}
}
