package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * How the recording pipeline talks to its child processes: line drains, one-shot commands, exit
 * waits and captured output.
 *
 * <p>It also owns the two executors the topology runs on: one virtual thread per process wait
 * (plan §4.1) and one daemon platform thread per drained pipe, because pipe reads sit in long
 * native calls and must stay off the shared common pool.
 */
final class CosmicProcessIo implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	private final RecordingProcesses processes;
	/** One virtual thread per process wait (plan §4.1: virtual threads for process waiting). */
	private final ExecutorService processWaits = Executors.newVirtualThreadPerTaskExecutor();
	private final ExecutorService pipeDrains = Executors.newThreadPerTaskExecutor(
			Thread.ofPlatform().daemon().name("gravaai-recorder-drain-", 0).factory());

	CosmicProcessIo(RecordingProcesses processes) {
		this.processes = processes;
	}

	/** The daemon platform executor pipe reads run on; shared with the ffmpeg mux runs. */
	ExecutorService pipeDrains() {
		return pipeDrains;
	}

	/** Completes once the child exits; an interrupted wait completes it anyway. */
	CompletableFuture<Void> onExit(SpawnedProcess process) {
		return CompletableFuture.runAsync(() -> {
			try {
				process.waitFor();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		}, processWaits);
	}

	/** Reads a pipe to the end, logging each line under {@code prefix}. */
	void drainInto(@Nullable InputStream stream, String prefix) {
		if (stream == null) {
			return;
		}
		Thread.ofPlatform().daemon().name("gravaai-drain").start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					LOG.log(System.Logger.Level.WARNING, prefix + " " + line.trim());
				}
			} catch (IOException error) {
				// Process exited.
			}
		});
	}

	/** Runs a command, returns stdout, throws when it exits non-zero (like {@code execFile}). */
	byte[] runAndCapture(List<String> command) throws IOException {
		SpawnedProcess process = processes.spawn(command, new SpawnOptions(false, true, true));
		InputStream stdoutPipe = process.stdout();
		InputStream stderrPipe = process.stderr();
		CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> {
			if (stdoutPipe == null) {
				return new byte[0];
			}
			try (InputStream in = stdoutPipe) {
				return in.readAllBytes();
			} catch (IOException error) {
				return new byte[0];
			}
		}, pipeDrains);
		CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> {
			if (stderrPipe == null) {
				return new byte[0];
			}
			try (InputStream in = stderrPipe) {
				return in.readAllBytes();
			} catch (IOException error) {
				return new byte[0];
			}
		}, pipeDrains);
		int code;
		try {
			code = process.waitFor();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", error);
		}
		byte[] output = stdout.join();
		if (code != 0) {
			String errorText = new String(stderr.join(), StandardCharsets.UTF_8).trim();
			throw new IOException(errorText.isEmpty() ? "Command exited with code " + code + "." : errorText);
		}
		return output;
	}

	/** Sends one line to the child's stdin, logging instead of failing. */
	static void writeCommand(SpawnedProcess process, String command) {
		try {
			OutputStream stdin = process.stdin();
			if (stdin != null) {
				stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
				stdin.flush();
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING,
					"Could not send " + command + " to the capture helper: " + error);
		}
	}

	static void closeStdin(SpawnedProcess process) {
		try {
			OutputStream stdin = process.stdin();
			if (stdin != null) {
				stdin.close();
			}
		} catch (IOException error) {
			// Already closed.
		}
	}

	static void closeQuietly(OutputStream output) {
		try {
			output.close();
		} catch (IOException error) {
			// Already closed.
		}
	}

	@Override
	public void close() {
		processWaits.shutdownNow();
		pipeDrains.shutdownNow();
	}
}
