package dev.gravaai.services.agent;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.JsonNode;

/** Shared agent-tool shapes (port of {@code electron/agent/types.ts}, plan §5.16). */
public final class AgentTypes {

	/** A rendered image handed to the MCP client: base64 data without the data: prefix. */
	public record AgentImage(String mimeType, String data) {}

	/** What a tool call returns: text the model reads, plus images it can look at. */
	public record AgentToolResult(String text, List<AgentImage> images) {
		public AgentToolResult {
			text = text == null ? "" : text;
			images = images == null ? List.of() : List.copyOf(images);
		}

		public static AgentToolResult text(String text) {
			return new AgentToolResult(text, List.of());
		}
	}

	/** Progress reported while a tool call (usually {@code job wait}) runs. */
	public record AgentProgressUpdate(double progress, Double total, String message) {
		public static AgentProgressUpdate of(double progress) {
			return new AgentProgressUpdate(progress, null, null);
		}
	}

	/** One tool request on the socket ({@code AgentToolRequest}). */
	public record AgentToolRequest(long id, String tool, JsonNode args) {}

	/** Progress sink handed to a tool handler. */
	public interface AgentToolContext {
		void progress(AgentProgressUpdate update);
	}

	/** Server-side tool handler ({@code AgentToolHandler}). */
	public interface AgentToolHandler {
		CompletableFuture<AgentToolResult> handle(AgentToolRequest request,
				AgentToolContext context);
	}

	/** One authenticated client shown in Settings → AI control. */
	public record AgentConnectionInfo(String client, long since) {}

	/** {@code AgentControlStatus}. */
	public record AgentControlStatus(boolean listening, String socketPath,
			List<AgentConnectionInfo> clients) {
		public AgentControlStatus {
			clients = List.copyOf(clients);
		}
	}

	private AgentTypes() {}
}
