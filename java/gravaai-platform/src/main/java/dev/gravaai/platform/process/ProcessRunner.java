package dev.gravaai.platform.process;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs child processes (plan §5.5).
 *
 * <p>Two shapes are supported:
 * <ul>
 *   <li>one-shot calls — {@link #run(List, Duration)} or the fluent {@link #run()}: stdout and
 *       stderr are drained on their own threads into {@link ProcessResult}, with optional
 *       line callbacks;</li>
 *   <li>long-running children — {@link #start()} returns a {@link RunningProcess} whose raw pipes
 *       can be connected to another process ({@code gravaai-wayland-capture} stdout → ffmpeg stdin,
 *       plan §5.6 / decision rule R7). A child's stdin is always piped and writable.</li>
 * </ul>
 *
 * <p>Neither pipe is ever left undrained in the default mode: both streams get a virtual-thread
 * reader even when no callback is registered (stdout must be consumed with {@link #rawStdout()} to
 * opt out). Timed-out and cancelled processes are killed together with their descendants
 * ({@link ProcessHandle#descendants()}).
 */
public final class ProcessRunner {

	private final List<String> command;
	private @Nullable Consumer<String> stdoutLine;
	private @Nullable Consumer<String> stderrLine;
	private boolean rawStdout;
	private boolean rawStderr;
	private @Nullable Duration timeout;
	private @Nullable Path workingDirectory;
	private final Map<String, String> environment = new LinkedHashMap<>();

	private ProcessRunner(List<String> command) {
		if (command == null || command.isEmpty()) {
			throw new IllegalArgumentException("command must not be empty");
		}
		for (String part : command) {
			if (part == null || part.isEmpty()) {
				throw new IllegalArgumentException("command must not contain null or empty arguments: " + command);
			}
		}
		this.command = List.copyOf(command);
	}

	public static ProcessRunner of(String... command) {
		return new ProcessRunner(Arrays.asList(command));
	}

	public static ProcessRunner of(List<String> command) {
		return new ProcessRunner(command);
	}

	/** One-shot helper: run {@code command} to completion and collect its output. */
	public static ProcessResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
		return new ProcessRunner(command).timeout(timeout).run();
	}

	/** One-shot helper with no timeout. */
	public static ProcessResult run(String... command) throws IOException, InterruptedException {
		return new ProcessRunner(Arrays.asList(command)).run();
	}

	/** Consume stdout as lines on the drain thread instead of exposing the raw pipe. */
	public ProcessRunner onStdoutLine(Consumer<String> consumer) {
		this.stdoutLine = consumer;
		return this;
	}

	/** Consume stderr as lines on the drain thread instead of exposing the raw pipe. */
	public ProcessRunner onStderrLine(Consumer<String> consumer) {
		this.stderrLine = consumer;
		return this;
	}

	/**
	 * Leave stdout untouched by the runner so the caller can read it (or copy it into another
	 * process). In this mode the caller <b>must</b> consume the stream or the child will block once
	 * the OS pipe is full.
	 */
	public ProcessRunner rawStdout() {
		this.rawStdout = true;
		return this;
	}

	/** Leave stderr untouched by the runner; the caller must consume it. */
	public ProcessRunner rawStderr() {
		this.rawStderr = true;
		return this;
	}

	public ProcessRunner timeout(Duration timeout) {
		this.timeout = timeout;
		return this;
	}

	public ProcessRunner workingDirectory(Path workingDirectory) {
		this.workingDirectory = workingDirectory;
		return this;
	}

	/** Adds or overrides one environment variable; the rest of the parent environment is inherited. */
	public ProcessRunner environment(String name, String value) {
		this.environment.put(name, value);
		return this;
	}

	/** Starts the process, waits for it to finish and returns its result. */
	public ProcessResult run() throws IOException, InterruptedException {
		return start().waitForExit();
	}

	/** Starts the process and hands back its pipes and control handle. */
	public RunningProcess start() throws IOException {
		ProcessBuilder builder = new ProcessBuilder(command);
		if (workingDirectory != null) {
			builder.directory(workingDirectory.toFile());
		}
		environment.forEach(builder.environment()::put);
		Process process = builder.start();
		return new RunningProcess(command, process, timeout, stdoutLine, stderrLine, rawStdout, rawStderr);
	}

	@Override
	public String toString() {
		return "ProcessRunner" + command;
	}
}
