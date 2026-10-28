package dev.gravaai.services.meeting.ports;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Child processes for meeting jobs: ffmpeg, whisper-cli, tar and llama-server. The Lead wires
 * this to {@code dev.gravaai.platform.process.ProcessRunner} (plan §5.5); tests use a fake.
 */
public interface ProcessRunnerPort {

	/**
	 * Runs a command to completion with both streams captured. A non-zero exit status is returned,
	 * not thrown; the caller decides whether it is an error.
	 *
	 * @param environment extra variables merged over the inherited environment
	 */
	ProcessResult run(List<String> command, Duration timeout, Map<String, String> environment)
			throws IOException, InterruptedException;

	/**
	 * Starts a process and delivers its stderr one line at a time (whisper/llama-server progress).
	 * stdout is drained and discarded.
	 */
	RunningProcess spawn(List<String> command, Map<String, String> environment,
			Consumer<String> stderrLines) throws IOException;

	/** Captured result of {@link #run(List, Duration, Map)}. */
	record ProcessResult(int exitCode, String stdout, String stderr) {
	}

	/** A child process started by {@link #spawn(List, Map, Consumer)}. */
	interface RunningProcess {

		boolean isAlive();

		/** Exit code once exited; empty while running. */
		OptionalInt exitCode();

		/** Waits up to {@code timeout} for exit; true when the process has exited. */
		boolean waitFor(Duration timeout) throws InterruptedException;

		/** SIGTERM, then SIGKILL after {@code grace}; waits for exit. */
		void terminate(Duration grace) throws InterruptedException;

		/** SIGKILL and wait. */
		void kill() throws InterruptedException;

		/** Everything the process wrote to stderr so far (tail kept by the runner). */
		String stderrTail();
	}
}
