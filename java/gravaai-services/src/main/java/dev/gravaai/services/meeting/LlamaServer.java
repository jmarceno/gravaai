package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.gravaai.services.meeting.MeetingAssets.MeetingAsset;
import dev.gravaai.services.meeting.ports.ProcessRunnerPort;

/**
 * Runs llama.cpp's server on demand for local summarization, ported from
 * electron/ipc/meeting/llamaServer.ts. It is started for a job and stopped right after, so it
 * never holds memory between meetings and never runs at the same time as whisper (the pipeline
 * transcribes first, then starts this only for the summarisation stage).
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class LlamaServer implements AutoCloseable {

	/** Context window for local runs. ~1 h of talk; longer meetings are chunked. */
	public static final int LOCAL_CONTEXT_TOKENS = 16_384;

	private static final Duration START_TIMEOUT = Duration.ofMinutes(2);
	private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration HEALTH_POLL = Duration.ofMillis(500);
	private static final Duration STOP_GRACE = Duration.ofSeconds(5);

	private record Running(ProcessRunnerPort.RunningProcess process, String modelId,
			String baseUrl) {
	}

	private final MeetingAssets assets;
	private final AssetInstaller installer;
	private final ProcessRunnerPort processes;
	private final HttpClient healthClient;
	private @Nullable Running running;

	public LlamaServer(MeetingAssets assets, AssetInstaller installer,
			ProcessRunnerPort processes) {
		this.assets = assets;
		this.installer = installer;
		this.processes = processes;
		this.healthClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.build();
	}

	/** llama-server arguments; the order is part of the port. */
	public static List<String> llamaServerArgs(String modelPath, int port) {
		return List.of(
				"-m",
				modelPath,
				"--host",
				"127.0.0.1",
				"--port",
				String.valueOf(port),
				"-c",
				String.valueOf(LOCAL_CONTEXT_TOKENS),
				// One request at a time; more slots only split the context.
				"-np",
				"1",
				// Offload as much as fits in free VRAM, the rest stays on the CPU.
				"--fit",
				"on",
				"--no-webui");
	}

	/** Downloads what is missing, starts the server and returns its OpenAI base URL. */
	public synchronized String start(String modelId, MeetingStatusCallback onStatus) {
		MeetingStatusCallback status = onStatus == null ? MeetingStatusCallback.IGNORING : onStatus;
		Running current = running;
		if (current != null && current.modelId().equals(modelId) && current.process().isAlive()) {
			return current.baseUrl();
		}
		stop();
		MeetingAsset model = assets.localLlmModels().get(modelId);
		if (model == null) {
			throw new IllegalArgumentException("Unknown local model " + modelId + ".");
		}
		installer.ensure(assets.llamaRuntime(),
				percent -> status.onStatus("Downloading the local model runtime…", percent));
		installer.ensure(model,
				percent -> status.onStatus("Downloading " + model.label() + "…", percent));

		status.onStatus("Loading " + model.label() + "…", null);
		int port = freePort();
		Path binary = assets.llamaServerPath();
		List<String> command = new ArrayList<>();
		command.add(binary.toString());
		command.addAll(llamaServerArgs(model.path().toString(), port));
		ProcessRunnerPort.RunningProcess process;
		try {
			process = processes.spawn(command,
					Map.of("LD_LIBRARY_PATH", binary.getParent().toString()), line -> {
					});
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
		String baseUrl = "http://127.0.0.1:" + port;
		try {
			waitForHealth(baseUrl, process);
		} catch (RuntimeException error) {
			try {
				process.kill();
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			throw error;
		}
		running = new Running(process, modelId, baseUrl + "/v1");
		return running.baseUrl();
	}

	public synchronized void stop() {
		Running current = running;
		running = null;
		if (current == null || !current.process().isAlive()) {
			return;
		}
		try {
			current.process().terminate(STOP_GRACE);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while stopping the local model server.",
					interrupted);
		}
	}

	public synchronized boolean isRunning() {
		return running != null && running.process().isAlive();
	}

	@Override
	public void close() {
		stop();
	}

	private void waitForHealth(String baseUrl, ProcessRunnerPort.RunningProcess process) {
		long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
		URI health = URI.create(baseUrl + "/health");
		while (System.nanoTime() < deadline) {
			if (!process.isAlive()) {
				throw new IllegalStateException(
						"The local model server stopped while loading: "
								+ Texts.lastLines(process.stderrTail(), 4));
			}
			try {
				HttpResponse<Void> response = healthClient.send(
						HttpRequest.newBuilder(health).timeout(HEALTH_TIMEOUT).GET().build(),
						HttpResponse.BodyHandlers.discarding());
				if (response.statusCode() >= 200 && response.statusCode() < 300) {
					return;
				}
			} catch (IOException notListeningYet) {
				// Not listening yet.
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(
						"Interrupted while waiting for the local model server.", interrupted);
			}
			try {
				Thread.sleep(HEALTH_POLL.toMillis());
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(
						"Interrupted while waiting for the local model server.", interrupted);
			}
		}
		throw new IllegalStateException(
				"The local model server did not become ready within 2 minutes.");
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		} catch (IOException error) {
			throw new IllegalStateException("No free port for the local model server.", error);
		}
	}
}
