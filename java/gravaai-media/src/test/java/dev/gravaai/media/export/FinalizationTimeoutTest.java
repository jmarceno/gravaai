package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.export.FinalizationTimeout.AdvancedProgress;
import dev.gravaai.media.export.FinalizationTimeout.ProgressState;
import dev.gravaai.media.export.FinalizationTimeout.Timeouts;
import dev.gravaai.media.export.FinalizationTimeout.Watchdog;
import dev.gravaai.media.export.FinalizationTimeout.Workload;

/** Port of {@code src/lib/exporter/finalizationTimeout.test.ts}. */
class FinalizationTimeoutTest {

	@Test
	@DisplayName("keeps non-audio finalization on the existing 10 minute timeout")
	void defaultTimeout() {
		assertEquals(600_000, FinalizationTimeout.finalizationTimeoutMs(null, Workload.DEFAULT));
		assertEquals(600_000, FinalizationTimeout.finalizationTimeoutMs(7_200.0, Workload.DEFAULT));
	}

	@Test
	@DisplayName("gives audio finalization more headroom on longer exports")
	void audioTimeout() {
		assertEquals(1_200_000, FinalizationTimeout.finalizationTimeoutMs(1_200.0, Workload.AUDIO));
		assertEquals(1_950_000, FinalizationTimeout.finalizationTimeoutMs(2_700.0, Workload.AUDIO));
	}

	@Test
	@DisplayName("caps adaptive audio timeout growth")
	void capsAudioTimeout() {
		assertEquals(2_700_000, FinalizationTimeout.finalizationTimeoutMs(10_800.0, Workload.AUDIO));
	}

	@Test
	@DisplayName("falls back to the base timeout for invalid audio durations")
	void invalidAudioDurations() {
		assertEquals(600_000, FinalizationTimeout.finalizationTimeoutMs(0.0, Workload.AUDIO));
		assertEquals(600_000, FinalizationTimeout.finalizationTimeoutMs(Double.NaN, Workload.AUDIO));
	}

	@Test
	@DisplayName("derives a bounded idle watchdog window from the total timeout")
	void idleTimeout() {
		assertEquals(150_000, FinalizationTimeout.finalizationIdleTimeoutMs(null, Workload.DEFAULT));
		assertEquals(300_000, FinalizationTimeout.finalizationIdleTimeoutMs(1_200.0, Workload.AUDIO));
		assertEquals(300_000, FinalizationTimeout.finalizationIdleTimeoutMs(2_700.0, Workload.AUDIO));
		assertEquals(150_000, FinalizationTimeout.finalizationIdleTimeoutMs(0.0, Workload.AUDIO));
		assertEquals(150_000, FinalizationTimeout.finalizationIdleTimeoutMs(Double.NaN, Workload.AUDIO));
	}

	@Test
	@DisplayName("rejects when a progress-aware finalization stage stops reporting progress")
	void idleWatchdogRejects() throws Exception {
		Timeouts policy = new Timeouts(1_200, 0, 1_200, 60, 60, 0.25);
		CompletableFuture<String> pending = new CompletableFuture<>();
		CompletableFuture<String> guarded = FinalizationTimeout.withFinalizationTimeout(pending,
				"audio processing", null, Workload.DEFAULT, true, null, policy);
		ExecutionException error = org.junit.jupiter.api.Assertions.assertThrows(
				ExecutionException.class, () -> guarded.get(3, TimeUnit.SECONDS));
		assertTrue(error.getCause().getMessage().contains("without observable progress"),
				error.getCause().getMessage());
	}

	@Test
	@DisplayName("resets the idle watchdog when finalization progress continues")
	void idleWatchdogRefreshes() throws Exception {
		Timeouts policy = new Timeouts(5_000, 0, 5_000, 250, 250, 0.25);
		CompletableFuture<String> pending = new CompletableFuture<>();
		AtomicReference<Watchdog> watchdog = new AtomicReference<>();
		CompletableFuture<String> guarded = FinalizationTimeout.withFinalizationTimeout(pending,
				"audio processing", null, Workload.DEFAULT, true, watchdog::set, policy);
		Thread.sleep(120);
		assertFalse(guarded.isDone());
		assertTrue(watchdog.get() != null);
		watchdog.get().refreshProgress();
		Thread.sleep(120);
		watchdog.get().refreshProgress();
		Thread.sleep(120);
		assertFalse(guarded.isDone());
		ExecutionException error = org.junit.jupiter.api.Assertions.assertThrows(
				ExecutionException.class, () -> guarded.get(3, TimeUnit.SECONDS));
		assertTrue(error.getCause().getMessage().contains("without observable progress"));
		assertNull(watchdog.get());
	}

	@Test
	@DisplayName("enforces the total finalization timeout")
	void totalTimeoutRejects() throws Exception {
		Timeouts policy = new Timeouts(120, 0, 120, 5_000, 5_000, 1.0);
		CompletableFuture<String> pending = new CompletableFuture<>();
		CompletableFuture<String> guarded = FinalizationTimeout.withFinalizationTimeout(pending,
				"audio processing", null, Workload.DEFAULT, true, null, policy);
		ExecutionException error = org.junit.jupiter.api.Assertions.assertThrows(
				ExecutionException.class, () -> guarded.get(3, TimeUnit.SECONDS));
		assertTrue(error.getCause().getMessage().contains("minutes"),
				error.getCause().getMessage());
	}

	@Test
	@DisplayName("passes through successful finalization and clears the watchdog")
	void successfulFinalization() throws Exception {
		CompletableFuture<String> completed = CompletableFuture.completedFuture("saved");
		AtomicReference<Watchdog> watchdog = new AtomicReference<>();
		CompletableFuture<String> guarded = FinalizationTimeout.withFinalizationTimeout(completed,
				"saving", null, Workload.DEFAULT, true, watchdog::set);
		assertEquals("saved", guarded.get(2, TimeUnit.SECONDS));
		assertNull(watchdog.get());
	}

	@Test
	@DisplayName("only marks finalization as progressed when normalized progress increases")
	void advancesProgress() {
		AdvancedProgress initial = FinalizationTimeout.advanceFinalizationProgress(99, 0.5,
				ProgressState.INITIAL);
		assertTrue(initial.progressed());
		assertEquals(99, initial.lastRenderProgress());
		assertEquals(0.5, initial.lastAudioProgress());
		AdvancedProgress repeated = FinalizationTimeout.advanceFinalizationProgress(99, 0.5,
				initial.state());
		assertFalse(repeated.progressed());
		AdvancedProgress advanced = FinalizationTimeout.advanceFinalizationProgress(100, 0.5,
				repeated.state());
		assertTrue(advanced.progressed());
		assertEquals(100, advanced.lastRenderProgress());
		assertEquals(0.5, advanced.lastAudioProgress());
	}

	@Test
	@DisplayName("ignores non-finite render progress without poisoning later updates")
	void ignoresNonFiniteProgress() {
		AdvancedProgress invalid = FinalizationTimeout.advanceFinalizationProgress(Double.NaN, 0.25,
				ProgressState.INITIAL);
		assertTrue(invalid.progressed());
		assertEquals(-1, invalid.lastRenderProgress());
		assertEquals(0.25, invalid.lastAudioProgress());
		AdvancedProgress recovered = FinalizationTimeout.advanceFinalizationProgress(99, 0.25,
				invalid.state());
		assertTrue(recovered.progressed());
		assertEquals(99, recovered.lastRenderProgress());
		assertEquals(0.25, recovered.lastAudioProgress());
	}
}
