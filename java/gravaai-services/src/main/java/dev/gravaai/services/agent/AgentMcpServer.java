package dev.gravaai.services.agent;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.agent.AgentTypes.AgentImage;
import dev.gravaai.services.agent.AgentTypes.AgentProgressUpdate;
import dev.gravaai.services.agent.AgentTypes.AgentToolResult;
import dev.gravaai.services.agent.AgentToolSchemas.ToolDefinition;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code gravaai --mcp}: the MCP stdio server (port of {@code startAgentBridge} in
 * {@code electron/agent/bridge.ts}, plan §5.16). Tool names, descriptions and JSON schemas come
 * byte-identically from {@link AgentToolSchemas}; every call is forwarded to the running app over
 * {@link AgentSocketBridge} and progress lines become MCP progress notifications.
 */
public final class AgentMcpServer implements AutoCloseable {

	private final McpSyncServer server;
	private final CountDownLatch closed = new CountDownLatch(1);

	private AgentMcpServer(McpSyncServer server) {
		this.server = server;
	}

	/** Starts the MCP server on stdin/stdout (the process entry point). */
	public static AgentMcpServer start(AgentSocketBridge bridge, String appName, String appVersion) {
		return start(bridge, appName, appVersion, System.in, System.out);
	}

	/** Starts the MCP server on the given stdio streams (tests use piped streams). */
	public static AgentMcpServer start(AgentSocketBridge bridge, String appName, String appVersion,
			InputStream input, OutputStream output) {
		McpJsonMapper mapper = new JacksonMcpJsonMapper(Json.MAPPER);
		List<SyncToolSpecification> specifications = new ArrayList<>();
		for (ToolDefinition definition : AgentToolSchemas.tools()) {
			Tool tool = Tool.builder()
					.name(definition.name())
					.description(definition.description())
					.inputSchema(mapper, definition.inputSchemaJson())
					.build();
			specifications.add(SyncToolSpecification.builder()
					.tool(tool)
					.callHandler((exchange, request) -> callTool(bridge, exchange, request))
					.build());
		}
		ServerCapabilities capabilities = ServerCapabilities.builder().tools(false).build();
		// The SDK transport stops reading at EOF but never tells the server, so the --mcp process
		// outlived every client that closed its pipe. EOF on stdin now closes the server.
		CountDownLatch inputEnded = new CountDownLatch(1);
		InputStream watched = new java.io.FilterInputStream(input) {
			@Override
			public int read() throws java.io.IOException {
				int value = super.read();
				if (value < 0) {
					inputEnded.countDown();
				}
				return value;
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
				int count = super.read(buffer, offset, length);
				if (count < 0) {
					inputEnded.countDown();
				}
				return count;
			}
		};
		StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper, watched,
				output);
		McpSyncServer server = McpServer.sync(transport)
				.serverInfo(appName, appVersion)
				.instructions(AgentToolSchemas.instructions())
				.capabilities(capabilities)
				.validateToolInputs(false)
				.tools(specifications)
				.build();
		AgentMcpServer started = new AgentMcpServer(server);
		Thread.ofVirtual().name("gravaai-mcp-stdin-eof").start(() -> {
			try {
				inputEnded.await();
				started.close();
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			} catch (RuntimeException error) {
				started.closed.countDown();
			}
		});
		return started;
	}

	/** The underlying SDK server (tests and the app entry point). */
	public McpSyncServer server() {
		return server;
	}

	/** Blocks until {@link #close()} is called (the {@code --mcp} process stays alive). */
	public void awaitClose() throws InterruptedException {
		closed.await();
	}

	@Override
	public void close() {
		try {
			server.close();
		} finally {
			closed.countDown();
		}
	}

	private static CallToolResult callTool(AgentSocketBridge bridge, McpSyncServerExchange exchange,
			CallToolRequest request) {
		String name = request.name();
		JsonNode args;
		try {
			Map<String, Object> arguments = request.arguments() == null ? Map.of()
					: request.arguments();
			args = Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(arguments));
		} catch (RuntimeException error) {
			return errorResult(error.getMessage());
		}
		Object progressToken = request.meta() == null ? null
				: request.meta().get("progressToken");
		Consumer<AgentProgressUpdate> onProgress = progressToken == null ? null
				: update -> notifyProgress(exchange, progressToken, update);
		AgentToolResult result;
		try {
			result = bridge.request(name, args, onProgress).join();
		} catch (CompletionException error) {
			Throwable cause = error.getCause() == null ? error : error.getCause();
			return errorResult(cause.getMessage());
		} catch (RuntimeException error) {
			return errorResult(error.getMessage());
		}
		List<Content> content = new ArrayList<>();
		for (AgentImage image : result.images()) {
			content.add(ImageContent.builder(image.data(), image.mimeType()).build());
		}
		content.add(TextContent.builder(result.text()).build());
		return CallToolResult.builder().content(content).build();
	}

	private static void notifyProgress(McpSyncServerExchange exchange, Object progressToken,
			AgentProgressUpdate update) {
		try {
			double progress = update == null || !Double.isFinite(update.progress()) ? 0
					: Math.max(0, Math.min(1, update.progress()));
			ProgressNotification notification = ProgressNotification.builder(progressToken, progress)
					.total(update == null ? null : update.total())
					.message(update == null ? null : update.message())
					.build();
			exchange.progressNotification(notification);
		} catch (RuntimeException error) {
			// Best effort: a client that went away must not fail the call.
		}
	}

	private static CallToolResult errorResult(String message) {
		return CallToolResult.builder()
				.addTextContent(message == null || message.isBlank()
						? "the app rejected the request"
						: message)
				.isError(true)
				.build();
	}
}
