package dev.gravaai.app.mcp;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import dev.gravaai.core.storage.UserData;
import dev.gravaai.services.agent.AgentMcpServer;
import dev.gravaai.services.agent.AgentSocketBridge;

/**
 * {@code gravaai --mcp}: MCP over stdio, forwarded to the running app over {@code agent.sock}
 * (plan §5.16, Phase 3). Starts no JavaFX and no GL.
 *
 * <p>Tool names/descriptions/schemas come from the shared tool-schemas resource; every call is
 * forwarded to the app's agent control server with the token from {@code agent-token}. If the app
 * is not running the bridge reconnects on demand and reports the Electron-compatible error.
 */
public final class McpMain {

	private static final String APP_NAME = "Gravaai";
	private static final String FALLBACK_VERSION = "1.4.0";

	private McpMain() {}

	public static void run(String[] args) {
		UserData userData = UserData.resolve();
		AgentSocketBridge bridge = new AgentSocketBridge(
				userData.agentSocket(),
				userData.agentTokenFile(),
				() -> clientLabel(args),
				(BooleanSupplier) () -> false);
		try (AgentMcpServer server = AgentMcpServer.start(bridge, APP_NAME, version())) {
			server.awaitClose();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException error) {
			System.err.println("gravaai --mcp: " + error.getMessage());
			System.exit(1);
		}
	}

	/** The client name shown in the app's AI-control page (best effort from argv). */
	private static String clientLabel(String[] args) {
		for (String arg : args) {
			if (arg.startsWith("--client-name=")) {
				String name = arg.substring("--client-name=".length()).strip();
				if (!name.isEmpty()) {
					return name;
				}
			}
		}
		return "mcp";
	}

	private static String version() {
		Package appPackage = McpMain.class.getPackage();
		String version = appPackage == null ? null : appPackage.getImplementationVersion();
		return version == null || version.isBlank() ? FALLBACK_VERSION : version;
	}
}
