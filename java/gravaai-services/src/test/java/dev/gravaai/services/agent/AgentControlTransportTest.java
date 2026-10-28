package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.agent.AgentTypes.AgentConnectionInfo;
import dev.gravaai.services.agent.AgentTypes.AgentImage;
import dev.gravaai.services.agent.AgentTypes.AgentProgressUpdate;
import dev.gravaai.services.agent.AgentTypes.AgentToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Port of {@code electron/agent/transport.test.ts}: the socket transport between
 * {@link AgentControlServer} and {@link AgentSocketBridge} (plan §5.16).
 */
class AgentControlTransportTest {

	@TempDir
	Path tempDir;

	private final List<AutoCloseable> cleanup = new ArrayList<>();

	@AfterEach
	void tearDown() throws Exception {
		for (int index = cleanup.size() - 1; index >= 0; index--) {
			try {
				cleanup.get(index).close();
			} catch (Exception ignored) {
				// Best effort.
			}
		}
		cleanup.clear();
	}

	private AgentControlServer startServer(AgentTypes.AgentToolHandler handler,
			AgentControlServer.ConnectionsListener listener) throws IOException {
		Path socketPath = tempDir.resolve("agent.sock");
		Path tokenFile = tempDir.resolve("agent-token");
		AgentControlServer server = new AgentControlServer(socketPath, tokenFile, handler, listener);
		server.start();
		cleanup.add(server);
		return server;
	}

	private AgentControlServer startServer(AgentTypes.AgentToolHandler handler) throws IOException {
		return startServer(handler, null);
	}

	private AgentSocketBridge bridgeFor(AgentControlServer server) {
		AgentSocketBridge bridge = new AgentSocketBridge(tempDir.resolve("agent.sock"),
				tempDir.resolve("agent-token"), () -> "vitest client", () -> false);
		cleanup.add(bridge);
		return bridge;
	}

	private static AgentToolResult textResult(String text) {
		return AgentToolResult.text(text);
	}

	private static ObjectNode object(String... keysAndValues) {
		ObjectNode node = Json.nodeFactory().objectNode();
		for (int index = 0; index + 1 < keysAndValues.length; index += 2) {
			node.put(keysAndValues[index], keysAndValues[index + 1]);
		}
		return node;
	}

	private static AgentToolResult await(CompletableFuture<AgentToolResult> future)
			throws Exception {
		try {
			return future.get(20, TimeUnit.SECONDS);
		} catch (ExecutionException error) {
			Throwable cause = error.getCause() == null ? error : error.getCause();
			throw new AssertionError("the call failed: " + cause.getMessage(), cause);
		}
	}

	private static Throwable awaitFailure(CompletableFuture<AgentToolResult> future)
			throws Exception {
		try {
			future.get(20, TimeUnit.SECONDS);
		} catch (ExecutionException error) {
			return error.getCause() == null ? error : error.getCause();
		}
		throw new AssertionError("expected the call to fail");
	}

	@Test
	@DisplayName("authenticates the bridge and forwards tool calls")
	void authenticatesAndForwards() throws Exception {
		AgentControlServer server = startServer((request, context) -> CompletableFuture
				.completedFuture(textResult(request.tool())));
		AgentSocketBridge bridge = bridgeFor(server);
		AgentToolResult result = await(bridge.request("inspect", object("view", "jobs")));
		assertTrue(result.text().contains("inspect"));
		assertEquals(List.of("vitest client"),
				server.status().clients().stream().map(AgentConnectionInfo::client).toList());
		assertTrue(server.status().listening());
	}

	@Test
	@DisplayName("passes rendered images through to the bridge")
	void passesImages() throws Exception {
		AgentControlServer server = startServer((request, context) -> CompletableFuture
				.completedFuture(new AgentToolResult("contact sheet of 2 frames",
						List.of(new AgentImage("image/jpeg", "AAAA")))));
		AgentSocketBridge bridge = bridgeFor(server);
		AgentToolResult result = await(bridge.request("frames", object("count", "2")));
		assertEquals("contact sheet of 2 frames", result.text());
		assertEquals(List.of(new AgentImage("image/jpeg", "AAAA")), result.images());
	}

	@Test
	@DisplayName("streams progress while the call is still running")
	void streamsProgress() throws Exception {
		AgentControlServer server = startServer((request, context) -> {
			context.progress(new AgentProgressUpdate(0.25, null, "transcribing"));
			context.progress(new AgentProgressUpdate(0.75, null, "summarising"));
			return CompletableFuture.completedFuture(textResult("done"));
		});
		AgentSocketBridge bridge = bridgeFor(server);
		List<AgentProgressUpdate> updates = new ArrayList<>();
		await(bridge.request("job", object("action", "wait"), updates::add));
		assertEquals(List.of("transcribing", "summarising"),
				updates.stream().map(AgentProgressUpdate::message).toList());
		assertEquals(0.25, updates.get(0).progress(), 1e-9);
	}

	@Test
	@DisplayName("rejects a client holding a stale token")
	void rejectsStaleToken() throws Exception {
		AgentControlServer server = startServer(
				(request, context) -> CompletableFuture.completedFuture(textResult("never")));
		AgentToken.write(tempDir.resolve("agent-token"), "f".repeat(64));
		AgentSocketBridge bridge = bridgeFor(server);
		Throwable error = awaitFailure(bridge.request("inspect", object()));
		assertTrue(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("unauthorized"),
				error.getMessage());
	}

	@Test
	@DisplayName("reports handler failures as errors, not results")
	void reportsHandlerFailures() throws Exception {
		AgentControlServer server = startServer((request, context) -> CompletableFuture
				.failedFuture(new IllegalStateException(
						"op 1: \"depth\" must be an integer between 1 and 6")));
		AgentSocketBridge bridge = bridgeFor(server);
		Throwable error = awaitFailure(bridge.request("edit", object()));
		assertTrue(error.getMessage().contains("depth"), error.getMessage());
	}

	@Test
	@DisplayName("drops connections when the server stops")
	void dropsConnectionsOnStop() throws Exception {
		AgentControlServer server = startServer(
				(request, context) -> CompletableFuture.completedFuture(textResult("ok")));
		AgentSocketBridge bridge = bridgeFor(server);
		assertEquals("ok", await(bridge.request("inspect", object())).text());
		server.stop();
		Throwable error = awaitFailure(bridge.request("inspect", object()));
		String message = error.getMessage() == null ? "" : error.getMessage();
		assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("closed")
				|| message.contains("ENOENT") || message.contains("No such file"), message);
	}

	@Test
	@DisplayName("regenerates the token and restarts cleanly")
	void regeneratesToken() throws Exception {
		AgentControlServer server = startServer(
				(request, context) -> CompletableFuture.completedFuture(textResult("ok")));
		String first = server.token();
		server.regenerateToken();
		String second = server.token();
		assertNotEquals(first, second);
		assertEquals(second,
				Files.readString(tempDir.resolve("agent-token"), StandardCharsets.UTF_8).trim());
		AgentSocketBridge bridge = bridgeFor(server);
		assertEquals("ok", await(bridge.request("inspect", object())).text());
	}

	@Test
	@DisplayName("notifies listeners when clients connect and leave")
	void notifiesConnections() throws Exception {
		AtomicInteger lastCount = new AtomicInteger(-1);
		List<Integer> seen = java.util.Collections.synchronizedList(new ArrayList<>());
		AgentControlServer server = startServer(
				(request, context) -> CompletableFuture.completedFuture(textResult("ok")),
				clients -> {
					lastCount.set(clients.size());
					seen.add(clients.size());
				});
		AgentSocketBridge bridge = bridgeFor(server);
		await(bridge.request("inspect", object()));
		bridge.close();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!server.status().clients().isEmpty() && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertTrue(seen.contains(1), "expected a client-count notification: " + seen);
		assertEquals(0, server.status().clients().size());
	}
}
