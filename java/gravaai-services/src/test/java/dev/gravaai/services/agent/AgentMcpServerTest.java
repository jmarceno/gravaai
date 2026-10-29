package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.agent.AgentTypes.AgentImage;
import dev.gravaai.services.agent.AgentTypes.AgentToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP stdio transport (port of the {@code startAgentBridge} part of
 * {@code electron/agent/bridge.ts}, plan §5.16): the tools the SDK lists must match the TS schemas
 * and every call must be forwarded through the socket bridge.
 */
// The test harnesses close() by joining reader threads; interruption only matters to the test JVM.
@SuppressWarnings("try")
class AgentMcpServerTest {

	@TempDir
	Path tempDir;

	private final List<AutoCloseable> cleanup = new ArrayList<>();

	@AfterEach
	void tearDown() {
		for (int index = cleanup.size() - 1; index >= 0; index--) {
			try {
				cleanup.get(index).close();
			} catch (Exception ignored) {
				// Best effort.
			}
		}
		cleanup.clear();
	}

	@Test
	@DisplayName("lists the TS tools and forwards calls over the socket bridge")
	void listsToolsAndForwardsCalls() throws Exception {
		AgentControlServer control = new AgentControlServer(tempDir.resolve("agent.sock"),
				tempDir.resolve("agent-token"), (request, context) -> {
					JsonNode args = request.args();
					switch (request.tool()) {
						case "inspect":
							return CompletableFuture.completedFuture(
									AgentToolResult.text("seen:inspect:" + args.path("view").asString()));
						case "frames":
							return CompletableFuture.completedFuture(new AgentToolResult(
									"contact sheet", List.of(new AgentImage("image/jpeg", "AAAA"))));
						case "edit":
							return CompletableFuture.failedFuture(new IllegalStateException(
									"op 1: \"depth\" must be an integer between 1 and 6"));
						default:
							return CompletableFuture.completedFuture(
									AgentToolResult.text("seen:" + request.tool()));
					}
				}, null);
		control.start();
		cleanup.add(control);
		AgentSocketBridge bridge = new AgentSocketBridge(tempDir.resolve("agent.sock"),
				tempDir.resolve("agent-token"), () -> "junit mcp client", () -> false);
		cleanup.add(bridge);

		try (PipedPair pipes = PipedPair.create()) {
			AgentMcpServer server = AgentMcpServer.start(bridge, "Gravaai", "1.4.0",
					pipes.serverInput(), pipes.serverOutput());
			cleanup.add(server);
			McpClientHarness client = new McpClientHarness(pipes.clientInput(),
					pipes.clientOutput());
			cleanup.add(client);

			ObjectNode initialize = client.request("initialize", initializeParams());
			JsonNode initialized = client.awaitResponse(initialize.path("id").asLong());
			assertEquals("Gravaai", initialized.path("result").path("serverInfo").path("name").asString());
			assertTrue(initialized.path("result").path("instructions").asString().length() > 20);
			assertTrue(initialized.path("result").path("capabilities").has("tools"));
			client.notify("notifications/initialized", Json.nodeFactory().objectNode());

			ObjectNode listTools = client.request("tools/list", Json.nodeFactory().objectNode());
			JsonNode toolsResult = client.awaitResponse(listTools.path("id").asLong());
			ArrayNode tools = (ArrayNode) toolsResult.path("result").path("tools");
			assertEquals(List.of("inspect", "edit", "frames", "project", "job"),
					names(tools));
			AgentToolSchemas.ToolDefinition expectedInspect = AgentToolSchemas.find("inspect")
					.orElseThrow();
			assertEquals(expectedInspect.description(),
					tools.get(0).path("description").asString());
			assertEquals("object", tools.get(0).path("inputSchema").path("type").asString());

			ObjectNode callInspect = client.request("tools/call", callParams("inspect",
					Json.nodeFactory().objectNode().put("view", "jobs")));
			JsonNode inspectResult = client.awaitResponse(callInspect.path("id").asLong());
			assertFalse(inspectResult.path("result").path("isError").asBoolean(false));
			assertEquals("seen:inspect:jobs", lastText(inspectResult));

			ObjectNode callFrames = client.request("tools/call",
					callParams("frames", Json.nodeFactory().objectNode().put("count", 2)));
			JsonNode framesResult = client.awaitResponse(callFrames.path("id").asLong());
			ArrayNode content = (ArrayNode) framesResult.path("result").path("content");
			assertNotNull(content);
			assertEquals("image", content.get(0).path("type").asString());
			assertEquals("image/jpeg", content.get(0).path("mimeType").asString());
			assertEquals("AAAA", content.get(0).path("data").asString());

			ObjectNode editArguments = Json.nodeFactory().objectNode();
			editArguments.putArray("ops");
			ObjectNode callEdit = client.request("tools/call", callParams("edit", editArguments));
			JsonNode editResult = client.awaitResponse(callEdit.path("id").asLong());
			assertTrue(editResult.path("result").path("isError").asBoolean(false));
			assertTrue(lastText(editResult).contains("depth"), lastText(editResult));
		}
	}

	private static ObjectNode initializeParams() {
		ObjectNode params = Json.nodeFactory().objectNode();
		params.put("protocolVersion", "2025-06-18");
		params.putObject("capabilities");
		ObjectNode clientInfo = params.putObject("clientInfo");
		clientInfo.put("name", "junit");
		clientInfo.put("version", "1.0");
		return params;
	}

	private static ObjectNode callParams(String name, JsonNode arguments) {
		ObjectNode params = Json.nodeFactory().objectNode();
		params.put("name", name);
		params.set("arguments", arguments);
		return params;
	}

	private static List<String> names(ArrayNode tools) {
		List<String> names = new ArrayList<>();
		for (JsonNode tool : tools) {
			names.add(tool.path("name").asString());
		}
		return names;
	}

	private static String lastText(JsonNode response) {
		ArrayNode content = (ArrayNode) response.path("result").path("content");
		if (content == null || content.isEmpty()) {
			return "";
		}
		return content.get(content.size() - 1).path("text").asString("");
	}

	/** A raw newline-delimited JSON-RPC client over the piped stdio streams. */
	private static final class McpClientHarness implements AutoCloseable {
		private final OutputStream output;
		private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		private final Thread reader;
		private long nextId = 1;

		McpClientHarness(InputStream input, OutputStream output) throws IOException {
			this.output = output;
			BufferedReader bufferedReader = new BufferedReader(
					new InputStreamReader(input, StandardCharsets.UTF_8));
			this.reader = Thread.ofVirtual().name("mcp-test-reader").start(() -> {
				try {
					String line;
					while ((line = bufferedReader.readLine()) != null) {
						lines.add(line);
					}
				} catch (IOException ignored) {
					// Stream closed.
				}
			});
		}

		ObjectNode request(String method, JsonNode params) throws IOException {
			ObjectNode line = Json.nodeFactory().objectNode();
			line.put("jsonrpc", "2.0");
			line.put("id", nextId++);
			line.put("method", method);
			line.set("params", params);
			send(line);
			return line;
		}

		void notify(String method, JsonNode params) throws IOException {
			ObjectNode line = Json.nodeFactory().objectNode();
			line.put("jsonrpc", "2.0");
			line.put("method", method);
			line.set("params", params);
			send(line);
		}

		JsonNode awaitResponse(long id) throws Exception {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
			while (System.nanoTime() < deadline) {
				String line = lines.poll(200, TimeUnit.MILLISECONDS);
				if (line == null) {
					continue;
				}
				JsonNode message;
				try {
					message = Json.MAPPER.readTree(line);
				} catch (RuntimeException error) {
					continue;
				}
				if (message != null && message.path("id").isNumber()
						&& message.path("id").asLong() == id) {
					if (message.has("error")) {
						throw new AssertionError("MCP error: " + message.path("error"));
					}
					return message;
				}
			}
			throw new AssertionError("no MCP response for id " + id);
		}

		private void send(ObjectNode line) throws IOException {
			synchronized (output) {
				output.write((Json.MAPPER.writeValueAsString(line) + "\n")
						.getBytes(StandardCharsets.UTF_8));
				output.flush();
			}
		}

		@Override
		public void close() throws Exception {
			output.close();
			reader.join(1_000);
		}
	}

	/** Two piped stream pairs: client -> server and server -> client. */
	private static final class PipedPair implements AutoCloseable {
		private final java.io.PipedInputStream serverInput = new java.io.PipedInputStream(1 << 16);
		private final java.io.PipedOutputStream clientOutput;
		private final java.io.PipedInputStream clientInput = new java.io.PipedInputStream(1 << 16);
		private final java.io.PipedOutputStream serverOutput;

		private PipedPair() throws IOException {
			this.clientOutput = new java.io.PipedOutputStream(serverInput);
			this.serverOutput = new java.io.PipedOutputStream(clientInput);
		}

		static PipedPair create() throws IOException {
			return new PipedPair();
		}

		InputStream serverInput() {
			return serverInput;
		}

		OutputStream serverOutput() {
			return serverOutput;
		}

		InputStream clientInput() {
			return clientInput;
		}

		OutputStream clientOutput() {
			return clientOutput;
		}

		@Override
		public void close() throws Exception {
			serverInput.close();
			clientOutput.close();
			clientInput.close();
			serverOutput.close();
		}
	}
}
