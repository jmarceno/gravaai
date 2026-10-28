package dev.gravaai.platform.process;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class ProcessRunnerTest {

	@TempDir
	Path tempDir;

	private static ProcessRunner shell(String script) {
		return ProcessRunner.of("/bin/sh", "-c", script);
	}

	@Test
	@DisplayName("captures stdout and stderr separately")
	void capturesStdoutAndStderrSeparately() throws Exception {
		ProcessResult result = shell("echo hello; echo oops 1>&2").run();

		assertEquals(0, result.exitCode());
		assertTrue(result.succeeded());
		assertEquals("hello\n", result.stdout());
		assertEquals("oops\n", result.stderr());
		assertFalse(result.timedOut());
		assertFalse(result.cancelled());
	}

	@Test
	@DisplayName("reports the exit code of a failing command")
	void reportsNonZeroExitCode() throws Exception {
		ProcessResult result = shell("exit 3").run();

		assertEquals(3, result.exitCode());
		assertFalse(result.succeeded());
		assertFalse(result.timedOut());
	}

	@Test
	@DisplayName("one-shot run helper collects the output")
	void oneShotRunHelper() throws Exception {
		ProcessResult result = ProcessRunner.run(List.of("/bin/sh", "-c", "printf hi"), Duration.ofSeconds(10));

		assertTrue(result.succeeded());
		assertEquals("hi", result.stdout());
	}

	@Test
	@DisplayName("delivers stdout lines to the callback")
	void invokesStdoutLineCallback() throws Exception {
		List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

		ProcessResult result = shell("echo one; echo two; echo three").onStdoutLine(lines::add).run();

		assertTrue(result.succeeded());
		assertEquals(List.of("one", "two", "three"), lines);
	}

	@Test
	@DisplayName("delivers a trailing partial stdout line")
	void deliversTrailingPartialLine() throws Exception {
		AtomicReference<String> line = new AtomicReference<>();

		ProcessResult result = shell("printf no-newline").onStdoutLine(line::set).run();

		assertTrue(result.succeeded());
		assertEquals("no-newline", line.get());
	}

	@Test
	@DisplayName("delivers stderr lines to the callback")
	void invokesStderrLineCallback() throws Exception {
		List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

		ProcessResult result = shell("echo first 1>&2; echo second 1>&2").onStderrLine(lines::add).run();

		assertTrue(result.succeeded());
		assertEquals(List.of("first", "second"), lines);
	}

	@Test
	@DisplayName("drains large stdout and stderr concurrently without deadlocking")
	void drainsLargeStdoutAndStderrConcurrently() throws Exception {
		ProcessResult result = shell("head -c 2000000 /dev/zero; head -c 2000000 /dev/zero 1>&2")
				.timeout(Duration.ofSeconds(30))
				.run();

		assertTrue(result.succeeded(), "process should not time out: " + result);
		assertTrue(result.stdout().length() >= 2_000_000, "stdout was truncated: " + result.stdout().length());
		assertTrue(result.stderr().length() >= 2_000_000, "stderr was truncated: " + result.stderr().length());
	}

	@Test
	@DisplayName("times out and kills a hanging process")
	void timesOutAndKillsProcess() throws Exception {
		long startedAt = System.nanoTime();

		ProcessResult result = shell("sleep 30").timeout(Duration.ofMillis(250)).run();

		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
		assertTrue(result.timedOut());
		assertFalse(result.cancelled());
		assertTrue(elapsedMillis < 10_000, "timeout took " + elapsedMillis + " ms");
	}

	@Test
	@DisplayName("cancel kills the process and its descendants")
	void cancelsProcessAndItsDescendants() throws Exception {
		AtomicReference<String> childPidLine = new AtomicReference<>();
		CountDownLatch childPidReady = new CountDownLatch(1);
		RunningProcess running = shell("sleep 30 & echo $!; wait")
				.onStdoutLine(line -> {
					childPidLine.set(line);
					childPidReady.countDown();
				})
				.start();

		assertTrue(childPidReady.await(5, TimeUnit.SECONDS), "child pid was not reported");
		long childPid = Long.parseLong(childPidLine.get().trim());

		running.cancel();
		ProcessResult result = running.waitForExit();

		assertTrue(result.cancelled());
		assertTrue(waitForExitOf(childPid, Duration.ofSeconds(5)), "descendant " + childPid + " is still alive");
	}

	@Test
	@DisplayName("runs the child in the requested working directory")
	void runsInWorkingDirectory() throws Exception {
		Path directory = Files.createDirectories(tempDir.resolve("work"));

		ProcessResult result = shell("pwd").workingDirectory(directory).run();

		assertTrue(result.succeeded());
		assertEquals(directory.toRealPath().toString(), result.stdout().trim());
	}

	@Test
	@DisplayName("applies environment overrides")
	void appliesEnvironmentOverrides() throws Exception {
		ProcessResult result = shell("printf '%s' \"$GRAVAAI_TEST_VAR\"")
				.environment("GRAVAAI_TEST_VAR", "from-test")
				.run();

		assertTrue(result.succeeded());
		assertEquals("from-test", result.stdout());
	}

	@Test
	@DisplayName("exposes raw pipes in raw mode")
	void exposesRawPipesInRawMode() throws Exception {
		try (RunningProcess running = shell("read line; printf 'got:%s' \"$line\"").rawStdout().start()) {
			try (var stdin = running.stdin();
					var stdout = running.stdout();
					var reader = new BufferedReader(new InputStreamReader(stdout, UTF_8))) {
				stdin.write("hi\n".getBytes(UTF_8));
				stdin.flush();
				assertEquals("got:hi", reader.readLine());
			}

			ProcessResult result = running.waitForExit();
			assertTrue(result.succeeded());
		}
	}

	@Test
	@DisplayName("raw accessors are rejected while the runner drains the stream")
	void rawAccessorsRejectedWhenDrained() throws Exception {
		try (RunningProcess running = shell("sleep 5").start()) {
			assertThrows(IllegalStateException.class, running::stdout);
			assertThrows(IllegalStateException.class, running::stderr);
			running.cancel();
			running.waitForExit();
		}
	}

	@Test
	@DisplayName("rejects an empty command")
	void rejectsEmptyCommand() {
		assertThrows(IllegalArgumentException.class, ProcessRunner::of);
		assertThrows(IllegalArgumentException.class, () -> ProcessRunner.of(List.of()));
	}

	private static boolean waitForExitOf(long pid, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
				Thread.sleep(20);
			} else {
				return true;
			}
		}
		return !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}
}
