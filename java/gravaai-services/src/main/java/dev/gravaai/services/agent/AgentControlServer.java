package dev.gravaai.services.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.agent.AgentTypes.AgentConnectionInfo;
import dev.gravaai.services.agent.AgentTypes.AgentControlStatus;
import dev.gravaai.services.agent.AgentTypes.AgentProgressUpdate;
import dev.gravaai.services.agent.AgentTypes.AgentToolContext;
import dev.gravaai.services.agent.AgentTypes.AgentToolRequest;
import dev.gravaai.services.agent.AgentTypes.AgentToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Local UNIX-socket control server the MCP bridge talks to (port of
 * {@code electron/agent/controlServer.ts}, plan §5.16).
 *
 * <p>It only listens while Settings → AI control is on ({@link #setEnabled(boolean)}), requires the
 * per-install token as the first line of every connection, never opens a TCP port, and speaks
 * newline-delimited JSON request/response with progress lines exactly like the Electron server.
 */
public final class AgentControlServer implements AutoCloseable {

	/** Receives the authenticated client list whenever it changes. */
	public interface ConnectionsListener {
		void onConnectionsChanged(List<AgentConnectionInfo> clients);
	}

	private static final System.Logger LOG = System.getLogger("gravaai.agent.control");
	private static final int MAX_LINE_CHARS = 4 * 1024 * 1024;
	private static final int MAX_CLIENT_CHARS = 120;
	private static final int MAX_PROGRESS_MESSAGE_CHARS = 200;

	private final Path socketPath;
	private final Path tokenFile;
	private final AgentTypes.AgentToolHandler handler;
	private final ConnectionsListener connectionsListener;
	private final Set<SocketChannel> sockets = ConcurrentHashMap.newKeySet();
	private final Map<SocketChannel, AgentConnectionInfo> clients = new ConcurrentHashMap<>();
	private final AtomicBoolean running = new AtomicBoolean();

	private volatile ServerSocketChannel server;
	private volatile Thread acceptThread;
	private volatile String currentToken;
	private volatile boolean enabled;

	public AgentControlServer(Path socketPath, Path tokenFile, AgentTypes.AgentToolHandler handler,
			ConnectionsListener connectionsListener) {
		this.socketPath = socketPath;
		this.tokenFile = tokenFile;
		this.handler = handler;
		this.connectionsListener = connectionsListener;
	}

	/** Starts listening (idempotent). */
	public synchronized void start() throws IOException {
		if (running.get()) {
			return;
		}
		Path parent = socketPath.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		currentToken = AgentToken.readOrCreate(tokenFile);
		Files.deleteIfExists(socketPath);
		ServerSocketChannel created = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		try {
			created.bind(UnixDomainSocketAddress.of(socketPath));
		} catch (IOException error) {
			try {
				created.close();
			} catch (IOException ignored) {
				// Best effort.
			}
			throw error;
		}
		try {
			Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "could not chmod the agent socket: " + error);
		}
		server = created;
		running.set(true);
		acceptThread = Thread.ofPlatform().name("agent-control-accept").daemon(true)
				.start(this::acceptLoop);
	}

	/** Stops listening and destroys every connection (idempotent). */
	public synchronized void stop() {
		enabled = false;
		if (!running.getAndSet(false)) {
			return;
		}
		ServerSocketChannel active = server;
		server = null;
		if (active != null) {
			try {
				active.close();
			} catch (IOException error) {
				LOG.log(System.Logger.Level.DEBUG, "closing the agent socket: " + error);
			}
		}
		for (SocketChannel socket : sockets) {
			closeQuietly(socket);
		}
		sockets.clear();
		if (!clients.isEmpty()) {
			clients.clear();
			notifyClients();
		}
		Thread thread = acceptThread;
		acceptThread = null;
		if (thread != null) {
			try {
				thread.join(1_000);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		}
		try {
			Files.deleteIfExists(socketPath);
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "removing the stale agent socket: " + error);
		}
	}

	/** Starts/stops listening as the {@code aiControlEnabled} setting changes. */
	public synchronized void setEnabled(boolean nextEnabled) throws IOException {
		enabled = nextEnabled;
		if (nextEnabled) {
			start();
		} else {
			stop();
		}
	}

	public boolean isEnabled() {
		return enabled;
	}

	/** {@code restart}. */
	public synchronized void restart() throws IOException {
		boolean wasEnabled = enabled;
		stop();
		enabled = wasEnabled;
		if (wasEnabled) {
			start();
		}
	}

	/** {@code status}. */
	public AgentControlStatus status() {
		return new AgentControlStatus(running.get(), socketPath.toString(),
				List.copyOf(clients.values()));
	}

	/** {@code regenerateToken}. */
	public synchronized String regenerateToken() throws IOException {
		String token = AgentToken.generate();
		AgentToken.write(tokenFile, token);
		currentToken = token;
		return token;
	}

	/** {@code token}. */
	public synchronized String token() throws IOException {
		String token = currentToken;
		if (token != null) {
			return token;
		}
		return AgentToken.readOrCreate(tokenFile);
	}

	@Override
	public void close() {
		stop();
	}

	private void acceptLoop() {
		while (running.get()) {
			ServerSocketChannel active = server;
			if (active == null) {
				return;
			}
			try {
				SocketChannel socket = active.accept();
				Thread.ofVirtual().name("agent-control-client").start(() -> handleConnection(socket));
			} catch (ClosedChannelException error) {
				return;
			} catch (IOException error) {
				if (!running.get()) {
					return;
				}
				LOG.log(System.Logger.Level.WARNING, "accepting an agent connection failed: " + error);
			}
		}
	}

	private void handleConnection(SocketChannel channel) {
		sockets.add(channel);
		Object writeLock = new Object();
		try (channel) {
			InputStream input = Channels.newInputStream(channel);
			OutputStream output = Channels.newOutputStream(channel);
			StringBuilder pending = new StringBuilder();
			boolean[] authenticated = {false};
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				pending.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
				if (pending.length() > MAX_LINE_CHARS) {
					return;
				}
				int newline;
				while ((newline = pending.indexOf("\n")) >= 0) {
					String line = pending.substring(0, newline).trim();
					pending.delete(0, newline + 1);
					if (!line.isEmpty()) {
						handleLine(channel, output, writeLock, line, authenticated);
					}
				}
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "agent connection ended: " + error);
		} finally {
			sockets.remove(channel);
			if (clients.remove(channel) != null) {
				notifyClients();
			}
		}
	}

	private void handleLine(SocketChannel channel, OutputStream output, Object writeLock, String line,
			boolean[] authenticated) {
		JsonNode message;
		try {
			message = Json.MAPPER.readTree(line);
		} catch (RuntimeException error) {
			writeLine(output, writeLock, errorNode("invalid json"));
			return;
		}
		if (message == null || !message.isObject()) {
			writeLine(output, writeLock, errorNode("invalid json"));
			return;
		}
		if (!authenticated[0]) {
			authenticate(channel, output, writeLock, message, authenticated);
			return;
		}
		long id = message.path("id").isNumber() ? message.path("id").asLong() : -1;
		String tool = message.path("tool").isString() ? message.path("tool").asText() : null;
		if (id < 0 || tool == null || tool.isEmpty()) {
			ObjectNode error = Json.nodeFactory().objectNode();
			error.put("id", id);
			error.put("ok", false);
			error.put("error", "expected {id, tool, args}");
			writeLine(output, writeLock, error);
			return;
		}
		JsonNode args = message.path("args").isObject()
				? message.path("args")
				: Json.nodeFactory().objectNode();
		AgentToolContext context = update -> writeProgress(output, writeLock, id, update);
		CompletableFuture<AgentToolResult> future;
		try {
			future = handler.handle(new AgentToolRequest(id, tool, args), context);
		} catch (RuntimeException error) {
			writeError(output, writeLock, id, errorMessage(error));
			return;
		}
		if (future == null) {
			writeError(output, writeLock, id, "the tool handler returned no result");
			return;
		}
		future.whenComplete((result, error) -> {
			if (error != null) {
				writeError(output, writeLock, id, errorMessage(error));
				return;
			}
			AgentToolResult resolved = result == null ? AgentToolResult.text("") : result;
			ObjectNode response = Json.nodeFactory().objectNode();
			response.put("id", id);
			response.put("ok", true);
			ObjectNode payload = response.putObject("result");
			payload.put("text", resolved.text());
			if (!resolved.images().isEmpty()) {
				var images = payload.putArray("images");
				for (AgentTypes.AgentImage image : resolved.images()) {
					ObjectNode entry = images.addObject();
					entry.put("mimeType", image.mimeType());
					entry.put("data", image.data());
				}
			}
			writeLine(output, writeLock, response);
		});
	}

	private void authenticate(SocketChannel channel, OutputStream output, Object writeLock,
			JsonNode message, boolean[] authenticated) {
		String expected;
		try {
			expected = currentToken != null ? currentToken : AgentToken.readOrCreate(tokenFile);
		} catch (IOException error) {
			writeLine(output, writeLock, errorNode("could not read the agent token"));
			closeQuietly(channel);
			return;
		}
		JsonNode token = message.path("token");
		if (!AgentToken.tokensMatch(expected, token.isString() ? token.asText() : null)) {
			writeLine(output, writeLock, errorNode(
					"unauthorized: restart Gravaai or regenerate the token in Settings → AI control"));
			closeQuietly(channel);
			return;
		}
		String client = message.path("client").isString()
				? message.path("client").asText().trim()
				: "";
		if (client.isEmpty()) {
			client = "MCP client";
		}
		if (client.length() > MAX_CLIENT_CHARS) {
			client = client.substring(0, MAX_CLIENT_CHARS);
		}
		clients.put(channel, new AgentConnectionInfo(client, System.currentTimeMillis()));
		notifyClients();
		authenticated[0] = true;
		ObjectNode response = Json.nodeFactory().objectNode();
		response.put("ok", true);
		ObjectNode result = response.putObject("result");
		result.put("authenticated", true);
		writeLine(output, writeLock, response);
	}

	private void writeProgress(OutputStream output, Object writeLock, long id,
			AgentProgressUpdate update) {
		ObjectNode line = Json.nodeFactory().objectNode();
		line.put("id", id);
		ObjectNode progress = line.putObject("progress");
		double value = update == null ? 0 : update.progress();
		progress.put("progress", Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0);
		if (update != null && update.total() != null && Double.isFinite(update.total())) {
			progress.put("total", update.total());
		}
		if (update != null && update.message() != null && !update.message().isBlank()) {
			String message = update.message().trim();
			progress.put("message", message.length() > MAX_PROGRESS_MESSAGE_CHARS
					? message.substring(0, MAX_PROGRESS_MESSAGE_CHARS)
					: message);
		}
		writeLine(output, writeLock, line);
	}

	private void writeError(OutputStream output, Object writeLock, long id, String message) {
		ObjectNode line = Json.nodeFactory().objectNode();
		line.put("id", id);
		line.put("ok", false);
		line.put("error", message);
		writeLine(output, writeLock, line);
	}

	private static ObjectNode errorNode(String message) {
		ObjectNode line = Json.nodeFactory().objectNode();
		line.put("ok", false);
		line.put("error", message);
		return line;
	}

	private void writeLine(OutputStream output, Object writeLock, ObjectNode line) {
		synchronized (writeLock) {
			try {
				output.write((Json.MAPPER.writeValueAsString(line) + "\n")
						.getBytes(StandardCharsets.UTF_8));
				output.flush();
			} catch (IOException error) {
				// The client went away; the connection thread cleans up.
			}
		}
	}

	private void notifyClients() {
		if (connectionsListener == null) {
			return;
		}
		try {
			connectionsListener.onConnectionsChanged(List.copyOf(clients.values()));
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "connections listener failed: " + error);
		}
	}

	private static String errorMessage(Throwable error) {
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}

	private static void closeQuietly(SocketChannel channel) {
		try {
			channel.close();
		} catch (IOException ignored) {
			// Already closed.
		}
	}
}
