package dev.gravaai.services.agent;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.agent.AgentTypes.AgentImage;
import dev.gravaai.services.agent.AgentTypes.AgentProgressUpdate;
import dev.gravaai.services.agent.AgentTypes.AgentToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Persistent newline-delimited JSON connection to the running app (port of
 * {@code electron/agent/bridge.ts}, plan §5.16). Reconnects on demand so the MCP bridge survives
 * editor restarts; forwards progress lines to the caller and applies the same call timeouts.
 *
 * <p>{@code --mcp} keeps one bridge and forwards every MCP tool call through it; the app itself
 * builds a second bridge only in tests.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class AgentSocketBridge implements AutoCloseable {

	/** {@code CONNECT_TIMEOUT_MS}. */
	public static final long CONNECT_TIMEOUT_MS = 20_000;
	/** {@code TOOL_TIMEOUT_MS}. */
	public static final long TOOL_TIMEOUT_MS = 120_000;
	/** {@code JOB_WAIT_MAX_S}. */
	public static final int JOB_WAIT_MAX_S = 300;
	/** {@code JOB_WAIT_SLACK_MS}. */
	public static final long JOB_WAIT_SLACK_MS = 15_000;

	/** Thrown when {@code agent-token} does not exist yet (AI control was never enabled). */
	public static final class MissingTokenException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public MissingTokenException() {
			super("AI control is off in Gravaai. Open Settings → AI control, turn it on, then retry"
					+ " (or run the app once with it enabled).");
		}
	}

	private final Path socketPath;
	private final Path tokenFile;
	private final Supplier<String> clientName;
	private final BooleanSupplier launchApp;
	private final Map<Long, Pending> waiting = new ConcurrentHashMap<>();
	private final AtomicLong idCounter = new AtomicLong();
	private final Object writeLock = new Object();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
			runnable -> {
				Thread thread = new Thread(runnable, "agent-bridge-timeout");
				thread.setDaemon(true);
				return thread;
			});

	private volatile @Nullable SocketChannel socket;
	private volatile @Nullable CompletableFuture<SocketChannel> connecting;
	private volatile boolean closed;

	private record Pending(CompletableFuture<AgentToolResult> result,
			Consumer<AgentProgressUpdate> onProgress) {}

	public AgentSocketBridge(Path socketPath, Path tokenFile, Supplier<String> clientName,
			BooleanSupplier launchApp) {
		this.socketPath = socketPath;
		this.tokenFile = tokenFile;
		this.clientName = clientName;
		this.launchApp = launchApp;
	}

	/** {@code toolTimeoutMs}: job waits block for the requested time (max 300 s) plus slack. */
	public static long toolTimeoutMs(String tool, JsonNode args) {
		boolean jobWait = "job".equals(tool) && args != null && args.path("action").isString()
				&& "wait".equals(args.path("action").asString());
		if (!jobWait) {
			return TOOL_TIMEOUT_MS;
		}
		double requested = args.path("timeout").isNumber() && args.path("timeout").asDouble() > 0
				? args.path("timeout").asDouble()
				: 60;
		return (long) (Math.min(requested, JOB_WAIT_MAX_S) * 1000 + JOB_WAIT_SLACK_MS);
	}

	/** Sends one tool call; the returned future completes with the app's result or error. */
	public CompletableFuture<AgentToolResult> request(String tool, JsonNode args) {
		return request(tool, args, null);
	}

	/** Sends one tool call and reports the app's intermediate progress lines. */
	public CompletableFuture<AgentToolResult> request(String tool, JsonNode args,
			Consumer<AgentProgressUpdate> onProgress) {
		CompletableFuture<AgentToolResult> result = new CompletableFuture<>();
		if (closed) {
			result.completeExceptionally(new IllegalStateException("the agent bridge is closed"));
			return result;
		}
		long id = idCounter.incrementAndGet();
		long timeoutMs = toolTimeoutMs(tool, args);
		CompletableFuture<SocketChannel> ensured;
		try {
			ensured = ensureSocket();
		} catch (RuntimeException error) {
			result.completeExceptionally(error);
			return result;
		}
		ensured.whenComplete((connected, connectError) -> {
			if (connectError != null) {
				result.completeExceptionally(unwrap(connectError));
				return;
			}
			ObjectNode line = Json.nodeFactory().objectNode();
			line.put("id", id);
			line.put("tool", tool);
			line.set("args", args == null ? Json.nodeFactory().objectNode() : args);
			waiting.put(id, new Pending(result, onProgress));
			scheduler.schedule(() -> {
				Pending pending = waiting.remove(id);
				if (pending != null) {
					pending.result().completeExceptionally(new IllegalStateException(
							"Gravaai did not answer \"" + tool + "\" within " + timeoutMs / 1000
									+ "s"));
				}
			}, timeoutMs, TimeUnit.MILLISECONDS);
			writeLine(connected, line);
		});
		return result;
	}

	/** Closes the socket; pending calls fail like a dropped connection. */
	@Override
	public void close() {
		closed = true;
		SocketChannel active = socket;
		socket = null;
		if (active != null) {
			try {
				active.close();
			} catch (IOException ignored) {
				// Already closed.
			}
		}
		settleAll("Gravaai closed the connection; start it again and retry");
		scheduler.shutdownNow();
	}

	private CompletableFuture<SocketChannel> ensureSocket() {
		SocketChannel current = socket;
		if (current != null && current.isOpen()) {
			return CompletableFuture.completedFuture(current);
		}
		synchronized (this) {
			if (socket != null && socket.isOpen()) {
				return CompletableFuture.completedFuture(socket);
			}
			CompletableFuture<SocketChannel> existing = connecting;
			if (existing != null) {
				return existing;
			}
			CompletableFuture<SocketChannel> attempt = attemptConnect();
			connecting = attempt;
			return attempt;
		}
	}

	private CompletableFuture<SocketChannel> attemptConnect() {
		CompletableFuture<SocketChannel> result = new CompletableFuture<>();
		Thread.ofVirtual().name("agent-bridge-connect").start(() -> {
			try {
				result.complete(connectOnce());
			} catch (MissingTokenException missing) {
				result.completeExceptionally(missing);
			} catch (IOException | RuntimeException error) {
				if (launchApp != null && launchApp.getAsBoolean()) {
					retryAfterLaunch(result, error);
				} else {
					result.completeExceptionally(error);
				}
			} finally {
				synchronized (AgentSocketBridge.this) {
					connecting = null;
				}
			}
		});
		return result;
	}

	private void retryAfterLaunch(CompletableFuture<SocketChannel> result, Throwable firstError) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS);
		Throwable lastError = firstError;
		while (System.nanoTime() < deadline) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				result.completeExceptionally(interrupted);
				return;
			}
			try {
				result.complete(connectOnce());
				return;
			} catch (MissingTokenException missing) {
				result.completeExceptionally(missing);
				return;
			} catch (IOException | RuntimeException retryError) {
				lastError = retryError;
			}
		}
		result.completeExceptionally(new IllegalStateException(
				"Gravaai did not become reachable within " + CONNECT_TIMEOUT_MS / 1000 + "s: "
						+ messageOf(lastError)));
	}

	private SocketChannel connectOnce() throws IOException {
		Optional<String> token = AgentToken.read(tokenFile);
		if (token.isEmpty()) {
			throw new MissingTokenException();
		}
		SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
		try {
			channel.connect(UnixDomainSocketAddress.of(socketPath));
		} catch (IOException error) {
			try {
				channel.close();
			} catch (IOException ignored) {
				// Best effort.
			}
			throw error;
		}
		ObjectNode handshake = Json.nodeFactory().objectNode();
		handshake.put("token", token.get());
		handshake.put("client", clientName == null ? "MCP client" : clientName.get());
		try {
			writeLine(channel, handshake);
			String ack = readLine(channel);
			JsonNode message = ack == null ? null : Json.MAPPER.readTree(ack);
			boolean ok = message != null && message.path("ok").isBoolean()
					&& message.path("ok").asBoolean();
			if (!ok) {
				String error = message != null && message.path("error").isString()
						? message.path("error").asString()
						: "Gravaai closed the connection; start it again and retry";
				try {
					channel.close();
				} catch (IOException ignored) {
					// Best effort.
				}
				throw new IOException(error);
			}
		} catch (IOException error) {
			try {
				channel.close();
			} catch (IOException ignored) {
				// Best effort.
			}
			throw error;
		} catch (RuntimeException error) {
			try {
				channel.close();
			} catch (IOException ignored) {
				// Best effort.
			}
			throw new IOException("could not authenticate with Gravaai", error);
		}
		socket = channel;
		Thread.ofVirtual().name("agent-bridge-reader").start(() -> readLoop(channel));
		return channel;
	}

	/** Reads one newline-terminated line from the channel (blocking; used for the auth ack). */
	private static @Nullable String readLine(SocketChannel channel) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ByteBuffer chunk = ByteBuffer.allocate(1024);
		while (true) {
			chunk.clear();
			int read = channel.read(chunk);
			if (read < 0) {
				return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8).trim();
			}
			chunk.flip();
			while (chunk.hasRemaining()) {
				byte value = chunk.get();
				if (value == '\n') {
					return buffer.toString(StandardCharsets.UTF_8).trim();
				}
				buffer.write(value);
			}
		}
	}

	private void readLoop(SocketChannel channel) {
		StringBuilder pending = new StringBuilder();
		try (InputStream input = Channels.newInputStream(channel)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				pending.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
				int newline;
				while ((newline = pending.indexOf("\n")) >= 0) {
					String line = pending.substring(0, newline).trim();
					pending.delete(0, newline + 1);
					if (!line.isEmpty()) {
						handleLine(line);
					}
				}
			}
		} catch (IOException error) {
			// Treat as a dropped connection below.
		} finally {
			if (socket == channel) {
				socket = null;
			}
			settleAll("Gravaai closed the connection; start it again and retry");
		}
	}

	private void handleLine(String line) {
		JsonNode message;
		try {
			message = Json.MAPPER.readTree(line);
		} catch (RuntimeException error) {
			return;
		}
		if (message == null || !message.isObject()) {
			return;
		}
		long id = message.path("id").isNumber() ? message.path("id").asLong() : -1;
		Pending pending = id < 0 ? null : waiting.get(id);
		boolean ok = message.path("ok").isBoolean() && message.path("ok").asBoolean();
		if (pending != null && !ok && message.path("progress").isObject()) {
			JsonNode update = message.path("progress");
			Double total = update.path("total").isNumber() ? update.path("total").asDouble() : null;
			String text = update.path("message").isString() ? update.path("message").asString() : null;
			if (pending.onProgress() != null) {
				pending.onProgress().accept(new AgentProgressUpdate(
						update.path("progress").isNumber() ? update.path("progress").asDouble() : 0,
						total, text));
			}
			return;
		}
		if (id >= 0 && pending != null) {
			waiting.remove(id);
			if (message.path("ok").isBoolean() && message.path("ok").asBoolean()) {
				JsonNode result = message.path("result");
				String text = result.path("text").isString() ? result.path("text").asString() : "";
				var images = new java.util.ArrayList<AgentImage>();
				JsonNode imageNodes = result.path("images");
				if (imageNodes.isArray()) {
					for (JsonNode image : imageNodes) {
						if (image.path("mimeType").isString() && image.path("data").isString()
								&& !image.path("data").asString().isEmpty()) {
							images.add(new AgentImage(image.path("mimeType").asString(),
									image.path("data").asString()));
						}
					}
				}
				pending.result().complete(new AgentToolResult(text, images));
			} else {
				String error = message.path("error").isString() ? message.path("error").asString()
						: "the app rejected the request";
				pending.result().completeExceptionally(new IllegalStateException(error));
			}
			return;
		}
		if (message.path("ok").isBoolean() && !message.path("ok").asBoolean()
				&& message.path("error").isString()) {
			// Pre-request rejection (bad token, bad json).
			settleAll(message.path("error").asString());
		}
	}

	private void writeLine(SocketChannel channel, ObjectNode line) {
		synchronized (writeLock) {
			try {
				ByteBuffer buffer = ByteBuffer.wrap((Json.MAPPER.writeValueAsString(line) + "\n")
						.getBytes(StandardCharsets.UTF_8));
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
			} catch (IOException error) {
				settleAll("Gravaai closed the connection; start it again and retry");
			}
		}
	}

	private void settleAll(String message) {
		for (Long id : java.util.List.copyOf(waiting.keySet())) {
			Pending pending = waiting.remove(id);
			if (pending != null) {
				pending.result().completeExceptionally(new IllegalStateException(message));
			}
		}
	}

	private static Throwable unwrap(Throwable error) {
		return error.getCause() != null && error instanceof java.util.concurrent.CompletionException
				? error.getCause()
				: error;
	}

	private static String messageOf(Throwable error) {
		return error == null || error.getMessage() == null ? "connection failed"
				: error.getMessage();
	}
}
