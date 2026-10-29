package dev.gravaai.platform.process;

import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A started child process: raw pipes plus a kill-tree aware wait/cancel handle (plan §5.5).
 *
 * <p>In the default mode the runner owns stdout and stderr and drains them on virtual threads; the
 * raw accessors then throw and the captured text is available in {@link ProcessResult}. In
 * {@link ProcessRunner#rawStdout() raw} mode the caller owns the stream and must consume it.
 */
public final class RunningProcess implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.platform.process");
	private static final Duration KILL_GRACE = Duration.ofSeconds(2);
	private static final long JOIN_TIMEOUT_MILLIS = 5000;

	private final List<String> command;
	private final Process process;
	/** Optional wall-clock limit; {@code null} means wait indefinitely. */
	private final @Nullable Duration timeout;
	private final boolean rawStdout;
	private final boolean rawStderr;
	private final @Nullable StreamCollector stdoutCollector;
	private final @Nullable StreamCollector stderrCollector;
	private final long startedAtNanos;
	private final AtomicBoolean cancelRequested = new AtomicBoolean();
	private @Nullable ProcessResult result;

	RunningProcess(List<String> command, Process process, @Nullable Duration timeout, @Nullable Consumer<String> stdoutLine,
			@Nullable Consumer<String> stderrLine, boolean rawStdout, boolean rawStderr) {
		this.command = command;
		this.process = process;
		this.timeout = timeout;
		this.rawStdout = rawStdout;
		this.rawStderr = rawStderr;
		this.startedAtNanos = System.nanoTime();
		this.stdoutCollector = rawStdout ? null
				: new StreamCollector("stdout", process.getInputStream(), orNoOp(stdoutLine));
		this.stderrCollector = rawStderr ? null
				: new StreamCollector("stderr", process.getErrorStream(), orNoOp(stderrLine));
		if (stdoutCollector != null) {
			stdoutCollector.start();
		}
		if (stderrCollector != null) {
			stderrCollector.start();
		}
	}

	public Process process() {
		return process;
	}

	public long pid() {
		return process.pid();
	}

	/** The child's stdout. Only available in raw mode. */
	public InputStream stdout() {
		requireRaw(rawStdout, "stdout");
		return process.getInputStream();
	}

	/** The child's stderr. Only available in raw mode. */
	public InputStream stderr() {
		requireRaw(rawStderr, "stderr");
		return process.getErrorStream();
	}

	/** The child's stdin pipe. Always available; close it to signal EOF. */
	public OutputStream stdin() {
		return process.getOutputStream();
	}

	public boolean isAlive() {
		return process.isAlive();
	}

	/**
	 * Waits for the process to exit, enforcing the configured timeout, and returns the captured
	 * result. Repeated calls return the same result. Threads that are interrupted keep their
	 * interrupt flag.
	 */
	public synchronized ProcessResult waitForExit() throws InterruptedException {
		if (result != null) {
			return result;
		}
		boolean timedOut = false;
		if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				timedOut = true;
				LOG.log(System.Logger.Level.WARNING, "process timed out after " + timeout + ": " + command);
				killTree();
				process.waitFor();
			}
		} else {
			process.waitFor();
		}
		join(stdoutCollector);
		join(stderrCollector);
		long durationMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;
		result = new ProcessResult(process.exitValue(), timedOut, cancelRequested.get(),
				stdoutCollector == null ? "" : stdoutCollector.text(),
				stderrCollector == null ? "" : stderrCollector.text(), durationMillis);
		return result;
	}

	/** Kills the process together with all descendants. Safe to call from another thread. */
	public void cancel() {
		if (cancelRequested.compareAndSet(false, true)) {
			LOG.log(System.Logger.Level.DEBUG, "cancelling process tree: " + command);
			killTree();
		}
	}

	@Override
	public void close() {
		if (process.isAlive()) {
			cancel();
			try {
				waitForExit();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static void requireRaw(boolean raw, String stream) {
		if (!raw) {
			throw new IllegalStateException(stream + " is drained by the ProcessRunner; use on" + capitalize(stream)
					+ "Line(...) or raw" + capitalize(stream) + "() to access the pipe");
		}
	}

	private static Consumer<String> orNoOp(@Nullable Consumer<String> callback) {
		return callback == null ? line -> {} : callback;
	}

	private static String capitalize(String value) {
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	private static void join(@Nullable StreamCollector collector) throws InterruptedException {
		if (collector != null) {
			collector.join(JOIN_TIMEOUT_MILLIS);
		}
	}

	/**
	 * Destroys every descendant first and then the process, waits up to the grace period and force
	 * kills whatever is left. Descendants are collected before the parent dies so re-parenting to
	 * init cannot hide them.
	 */
	private void killTree() {
		List<ProcessHandle> descendants = process.descendants().toList();
		for (ProcessHandle descendant : descendants) {
			descendant.destroy();
		}
		process.destroy();
		long deadline = System.nanoTime() + KILL_GRACE.toNanos();
		boolean interrupted = false;
		while (System.nanoTime() < deadline && (process.isAlive()
				|| descendants.stream().anyMatch(ProcessHandle::isAlive))) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException error) {
				interrupted = true;
				break;
			}
		}
		for (ProcessHandle descendant : descendants) {
			if (descendant.isAlive()) {
				descendant.destroyForcibly();
			}
		}
		if (process.isAlive()) {
			process.destroyForcibly();
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	/** Drains one stream on a virtual thread, capturing the text and firing an optional line callback. */
	private static final class StreamCollector implements Runnable {

		private static final System.Logger LOG = System.getLogger("gravaai.platform.process");

		private final String name;
		private final InputStream input;
		private final Consumer<String> lineCallback;
		private final StringBuilder captured = new StringBuilder();
		private final StringBuilder currentLine = new StringBuilder();
		private @Nullable Thread thread;

		StreamCollector(String name, InputStream input, Consumer<String> lineCallback) {
			this.name = name;
			this.input = input;
			this.lineCallback = lineCallback;
		}

		void start() {
			thread = Thread.ofVirtual().name("process-" + name).start(this);
		}

		@Override
		public void run() {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
				char[] buffer = new char[8192];
				int read;
				while ((read = reader.read(buffer)) != -1) {
					captured.append(buffer, 0, read);
					for (int index = 0; index < read; index++) {
						char character = buffer[index];
						if (character == '\n') {
							emitLine();
						} else {
							currentLine.append(character);
						}
					}
				}
				if (currentLine.length() > 0) {
					emitLine();
				}
			} catch (IOException error) {
				// The pipe is closed when the process is killed; that is not an error.
				LOG.log(System.Logger.Level.TRACE, "process " + name + " reader stopped: " + error);
			}
		}

		private void emitLine() {
			String line = currentLine.toString();
			currentLine.setLength(0);
			if (line.endsWith("\r")) {
				line = line.substring(0, line.length() - 1);
			}
			try {
				lineCallback.accept(line);
			} catch (RuntimeException error) {
				// A callback must not take the drain thread down: an undrained pipe blocks the child.
				LOG.log(System.Logger.Level.WARNING, "process " + name + " line callback failed", error);
			}
		}

		void join(long timeoutMillis) throws InterruptedException {
			if (thread != null) {
				thread.join(timeoutMillis);
			}
		}

		String text() {
			return captured.toString();
		}
	}
}
