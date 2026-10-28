package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code gravaai --mcp} must exit when the MCP client closes stdin. Before the fix the SDK
 * transport stopped reading at EOF without telling the server, so every bridge process outlived
 * its client.
 */
@Timeout(30)
class AgentMcpServerEofTest {

	@Test
	@DisplayName("EOF on stdin closes the server")
	void stdinEofClosesTheServer(@TempDir Path dir) throws Exception {
		AgentSocketBridge bridge = new AgentSocketBridge(dir.resolve("agent.sock"),
				dir.resolve("agent-token"), () -> "junit", () -> false);
		try (bridge) {
			AgentMcpServer server = AgentMcpServer.start(bridge, "Gravaai", "1.4.0",
					new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
			CountDownLatch returned = new CountDownLatch(1);
			Thread waiter = Thread.ofVirtual().start(() -> {
				try {
					server.awaitClose();
					returned.countDown();
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
			});
			try {
				assertTrue(returned.await(10, TimeUnit.SECONDS),
						"awaitClose must return once stdin ends");
			} finally {
				waiter.interrupt();
				server.close();
			}
		}
	}
}
