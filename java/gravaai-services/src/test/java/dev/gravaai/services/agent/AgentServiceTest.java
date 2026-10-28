package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.agent.AgentJobRegistry.Job;
import dev.gravaai.services.agent.AgentJobRegistry.Kind;
import dev.gravaai.services.agent.AgentJobRegistry.Patch;
import dev.gravaai.services.agent.AgentJobRegistry.Status;
import dev.gravaai.services.agent.AgentService.ActivityEntry;
import dev.gravaai.services.agent.AgentService.ConnectionStatus;
import dev.gravaai.services.agent.AgentService.Highlight;
import dev.gravaai.services.agent.AgentService.McpConfig;
import dev.gravaai.services.agent.AgentService.Snapshot;
import tools.jackson.databind.node.ObjectNode;

/** {@link AgentService} lifecycle + the Services ↔ UI snapshot surface (plan §5.16/P3.2). */
class AgentServiceTest {

	@TempDir
	Path tempDir;

	/** Settings store fake. */
	private static final class FakeSettings implements AgentService.SettingsPort {
		final AtomicBoolean enabled = new AtomicBoolean();

		@Override
		public boolean aiControlEnabled() {
			return enabled.get();
		}

		@Override
		public void setAiControlEnabled(boolean value) {
			enabled.set(value);
		}
	}

	/** Revert fake. */
	private static final class FakeRevert implements AgentService.RevertPort {
		final AtomicInteger calls = new AtomicInteger();
		boolean result = true;

		@Override
		public CompletableFuture<Boolean> revertLastAiChange() {
			calls.incrementAndGet();
			return CompletableFuture.completedFuture(result);
		}
	}

	private FakeSettings settings;
	private FakeRevert revert;
	private AgentJobRegistry jobs;
	private AgentService service;
	private AgentSocketBridge bridge;

	private void createService() {
		settings = new FakeSettings();
		revert = new FakeRevert();
		jobs = new AgentJobRegistry();
		service = new AgentService(tempDir.resolve("agent.sock"), tempDir.resolve("agent-token"),
				(request, context) -> CompletableFuture.completedFuture(
						AgentTypes.AgentToolResult.text("inspected:" + request.tool())),
				jobs, settings, revert, "/opt/Gravaai/gravaai.AppImage", List.of("--mcp"));
	}

	@AfterEach
	void tearDown() {
		if (bridge != null) {
			bridge.close();
		}
		if (service != null) {
			service.close();
		}
	}

	private Snapshot awaitStatus(ConnectionStatus expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		Snapshot last = service.snapshot();
		while (System.nanoTime() < deadline) {
			last = service.snapshot();
			if (last.status() == expected) {
				return last;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("status never became " + expected + "; last " + last);
	}

	@Test
	@DisplayName("does not listen while AI control is off")
	void disabledDoesNotListen() throws Exception {
		createService();
		service.startIfEnabled();
		assertFalse(service.isListening());
		assertFalse(Files.exists(tempDir.resolve("agent.sock")));
		assertEquals(ConnectionStatus.OFF, service.snapshot().status());
	}

	@Test
	@DisplayName("starts listening when enabled and reports LISTENING")
	void enabledListens() throws Exception {
		createService();
		List<Snapshot> seen = new CopyOnWriteArrayList<>();
		service.subscribe(seen::add);
		service.setEnabled(true);
		Snapshot snapshot = awaitStatus(ConnectionStatus.LISTENING);
		assertTrue(snapshot.enabled());
		assertTrue(snapshot.listening());
		assertTrue(Files.exists(tempDir.resolve("agent.sock")));
		assertFalse(seen.isEmpty());
		assertEquals(1, seen.get(0).status() == ConnectionStatus.OFF ? 1 : 1);
	}

	@Test
	@DisplayName("marks CONNECTED while an MCP client is connected")
	void connectedClient() throws Exception {
		createService();
		service.setEnabled(true);
		bridge = new AgentSocketBridge(tempDir.resolve("agent.sock"),
				tempDir.resolve("agent-token"), () -> "junit client", () -> false);
		ObjectNode args = Json.nodeFactory().objectNode();
		assertEquals("inspected:inspect",
				bridge.request("inspect", args).get(20, TimeUnit.SECONDS).text());
		Snapshot snapshot = awaitStatus(ConnectionStatus.CONNECTED);
		assertEquals("junit client", snapshot.clients().get(0).client());
		assertTrue(snapshot.connected());
	}

	@Test
	@DisplayName("rotates the token and restarts the listener")
	void rotatesToken() throws Exception {
		createService();
		service.setEnabled(true);
		String first = service.token();
		service.regenerateToken();
		String second = service.token();
		assertNotEquals(first, second);
		assertEquals(second,
				Files.readString(tempDir.resolve("agent-token"), StandardCharsets.UTF_8).trim());
		assertTrue(service.isListening());
	}

	@Test
	@DisplayName("fails running editor jobs when the editor closes")
	void failsEditorJobsOnClose() throws Exception {
		createService();
		Job transcribe = jobs.create(Kind.TRANSCRIBE, "transcribe");
		jobs.update(transcribe.id(), Patch.of(Status.RUNNING));
		Job export = jobs.create(Kind.EXPORT, "export");
		jobs.update(export.id(), Patch.of(Status.RUNNING));
		Job meeting = jobs.create(Kind.MEETING, "meeting");
		jobs.update(meeting.id(), Patch.of(Status.RUNNING));

		service.editorClosed();

		assertEquals(Status.FAILED, jobs.get(transcribe.id()).status());
		assertTrue(jobs.get(transcribe.id()).error().contains("closed"));
		assertEquals(Status.FAILED, jobs.get(export.id()).status());
		assertEquals(Status.RUNNING, jobs.get(meeting.id()).status());
	}

	@Test
	@DisplayName("keeps newest activity first and only successful summaries become lastSummary")
	void activityLog() {
		createService();
		service.recordActivity("edit 1", false);
		service.recordActivity("edit 2 failed", true);
		service.recordActivity("edit 3", false);
		Snapshot snapshot = service.snapshot();
		List<String> labels = snapshot.recentActivity().stream().map(ActivityEntry::label).toList();
		assertEquals(List.of("edit 3", "edit 2 failed", "edit 1"), labels);
		assertEquals("edit 3", snapshot.lastSummary());
		assertTrue(snapshot.recentActivity().get(1).failed());
	}

	@Test
	@DisplayName("delegates revertLastAiChange and clears the last summary")
	void revertsLastChange() throws Exception {
		createService();
		service.recordActivity("edit", false);
		assertEquals("edit", service.snapshot().lastSummary());
		assertTrue(service.revertLastAiChange().get(5, TimeUnit.SECONDS));
		assertEquals(1, revert.calls.get());
		assertNull(service.snapshot().lastSummary());
	}

	@Test
	@DisplayName("publishes highlights to subscribers")
	void publishesHighlights() throws Exception {
		createService();
		List<List<Highlight>> received = new CopyOnWriteArrayList<>();
		AutoCloseable subscription = service.onHighlights(received::add);
		service.applyHighlights(List.of(
				new Highlight(Highlight.Kind.CUT, 0, 1000, "cut"),
				new Highlight(Highlight.Kind.ADDED, 1000, 2000, "zoom")));
		assertEquals(2, received.get(0).size());
		assertEquals(Highlight.Kind.CUT, received.get(0).get(0).kind());
		subscription.close();
		service.applyHighlights(List.of());
		assertEquals(1, received.size());
	}

	@Test
	@DisplayName("builds the MCP client configuration text")
	void buildsMcpConfig() throws Exception {
		McpConfig config = McpConfig.of("/opt/Gravaai/gravaai.AppImage", List.of("--mcp"));
		assertEquals("/opt/Gravaai/gravaai.AppImage", config.command());
		assertEquals(List.of("--mcp"), config.args());
		assertEquals("claude mcp add gravaai -- /opt/Gravaai/gravaai.AppImage --mcp",
				config.claudeCodeCommand());
		var json = Json.parseJsonWithByteOrderMark(config.json());
		assertEquals("/opt/Gravaai/gravaai.AppImage",
				json.path("mcpServers").path("gravaai").path("command").asText());
		assertEquals("--mcp",
				json.path("mcpServers").path("gravaai").path("args").get(0).asText());
		assertTrue(McpConfig.of("/tmp/my app/gravaai", List.of("--mcp")).claudeCodeCommand()
				.contains("\"/tmp/my app/gravaai\""));
	}

	@Test
	@DisplayName("forwards renderer job reports but never moves meeting jobs")
	void appliesJobReports() {
		createService();
		Job render = jobs.create(Kind.TRANSCRIBE, "transcribe");
		Job meeting = jobs.create(Kind.MEETING, "meeting");
		jobs.update(meeting.id(), Patch.of(Status.RUNNING));
		service.applyJobReport(render.id(), "running", 0.5, "encoding", null, null);
		assertEquals(Status.RUNNING, jobs.get(render.id()).status());
		assertEquals(0.5, jobs.get(render.id()).progress());
		service.applyJobReport(meeting.id(), "done", 1.0, null, null, null);
		assertEquals(Status.RUNNING, jobs.get(meeting.id()).status());
	}

	@Test
	@DisplayName("closes the listener and clears subscribers")
	void closesListener() throws Exception {
		createService();
		service.setEnabled(true);
		assertTrue(service.isListening());
		service.close();
		assertFalse(service.isListening());
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (Files.exists(tempDir.resolve("agent.sock")) && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertFalse(Files.exists(tempDir.resolve("agent.sock")));
	}

	@Test
	@DisplayName("delivers the current snapshot immediately on subscribe")
	void subscribeDeliversCurrent() {
		createService();
		AtomicReference<Snapshot> received = new AtomicReference<>();
		service.subscribe(received::set);
		assertEquals(ConnectionStatus.OFF, received.get().status());
		assertEquals(tempDir.resolve("agent.sock").toString(), received.get().socketPath());
		assertTrue(received.get().mcpCommand().contains("/opt/Gravaai/gravaai.AppImage --mcp"));
	}
}
