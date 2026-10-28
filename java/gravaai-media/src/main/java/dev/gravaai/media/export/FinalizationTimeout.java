package dev.gravaai.media.export;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Finalization timeout + idle-progress watchdog for exports (port of
 * {@code src/lib/exporter/finalizationTimeout.ts}, plan §5.10).
 */
public final class FinalizationTimeout {

	/** {@code FinalizationTimeoutWorkload}. */
	public enum Workload {
		DEFAULT,
		AUDIO
	}

	/** Timeout policy; {@link #DEFAULT} carries today's numbers and tests may use a faster one. */
	public record Timeouts(long baseMs, long audioHeadroomPerOutputSecondMs, long maxAudioMs,
			long minIdleMs, long maxIdleMs, double idleFraction) {
		public static final Timeouts DEFAULT = new Timeouts(10 * 60_000L, 500L, 45 * 60_000L,
				90_000L, 5 * 60_000L, 0.25);
	}

	/** {@code FinalizationProgressState}. */
	public record ProgressState(double lastRenderProgress, double lastAudioProgress) {
		public static final ProgressState INITIAL = new ProgressState(-1, -1);
	}

	/** Result of {@code advanceFinalizationProgress}. */
	public record AdvancedProgress(ProgressState state, boolean progressed) {
		public double lastRenderProgress() {
			return state.lastRenderProgress();
		}

		public double lastAudioProgress() {
			return state.lastAudioProgress();
		}
	}

	/** {@code FinalizationProgressWatchdog}. */
	public interface Watchdog {
		void refreshProgress();
	}

	private FinalizationTimeout() {}

	/** {@code getExportFinalizationTimeoutMs}. */
	public static long finalizationTimeoutMs(Double effectiveDurationSec, Workload workload) {
		return finalizationTimeoutMs(Timeouts.DEFAULT, effectiveDurationSec, workload);
	}

	public static long finalizationTimeoutMs(Timeouts policy, Double effectiveDurationSec,
			Workload workload) {
		Workload resolvedWorkload = workload == null ? Workload.DEFAULT : workload;
		if (resolvedWorkload != Workload.AUDIO) {
			return policy.baseMs();
		}
		if (effectiveDurationSec == null || !Double.isFinite(effectiveDurationSec)
				|| effectiveDurationSec <= 0) {
			return policy.baseMs();
		}
		double adaptive = policy.baseMs()
				+ effectiveDurationSec * policy.audioHeadroomPerOutputSecondMs();
		return Math.min((long) adaptive, policy.maxAudioMs());
	}

	/** {@code getExportFinalizationIdleTimeoutMs}. */
	public static long finalizationIdleTimeoutMs(Double effectiveDurationSec, Workload workload) {
		return finalizationIdleTimeoutMs(Timeouts.DEFAULT, effectiveDurationSec, workload);
	}

	public static long finalizationIdleTimeoutMs(Timeouts policy, Double effectiveDurationSec,
			Workload workload) {
		long total = finalizationTimeoutMs(policy, effectiveDurationSec, workload);
		return Math.min(
				Math.max((long) Math.floor(total * policy.idleFraction()), policy.minIdleMs()),
				policy.maxIdleMs());
	}

	/** {@code advanceFinalizationProgress}. */
	public static AdvancedProgress advanceFinalizationProgress(double renderProgress,
			Double audioProgress, ProgressState state) {
		Double normalizedRenderProgress = Double.isFinite(renderProgress)
				? Math.max(0, Math.min(renderProgress, 100))
				: null;
		Double normalizedAudioProgress = audioProgress != null && Double.isFinite(audioProgress)
				? Math.max(0, Math.min(audioProgress, 1))
				: null;
		double nextRenderProgress = normalizedRenderProgress == null
				? state.lastRenderProgress()
				: Math.max(state.lastRenderProgress(), normalizedRenderProgress);
		double nextAudioProgress = normalizedAudioProgress == null
				? state.lastAudioProgress()
				: Math.max(state.lastAudioProgress(), normalizedAudioProgress);
		boolean progressed = nextRenderProgress > state.lastRenderProgress()
				|| nextAudioProgress > state.lastAudioProgress();
		return new AdvancedProgress(new ProgressState(nextRenderProgress, nextAudioProgress),
				progressed);
	}

	/** {@code withFinalizationTimeout}. */
	public static <T> CompletableFuture<T> withFinalizationTimeout(CompletableFuture<T> promise,
			String stage, Double effectiveDurationSec, Workload workload, boolean progressAware,
			Consumer<Watchdog> onWatchdogChanged) {
		return withFinalizationTimeout(promise, stage, effectiveDurationSec, workload, progressAware,
				onWatchdogChanged, Timeouts.DEFAULT);
	}

	public static <T> CompletableFuture<T> withFinalizationTimeout(CompletableFuture<T> promise,
			String stage, Double effectiveDurationSec, Workload workload, boolean progressAware,
			Consumer<Watchdog> onWatchdogChanged, Timeouts policy) {
		Objects.requireNonNull(promise, "promise");
		Objects.requireNonNull(stage, "stage");
		long timeoutMs = finalizationTimeoutMs(policy, effectiveDurationSec, workload);
		boolean idleWatchdog = progressAware;
		long idleTimeoutMs = idleWatchdog
				? finalizationIdleTimeoutMs(policy, effectiveDurationSec, workload)
				: -1;
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "export-finalization-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		CompletableFuture<T> result = new CompletableFuture<>();
		AtomicBoolean settled = new AtomicBoolean();
		AtomicReference<ScheduledFuture<?>> totalTimer = new AtomicReference<>();
		AtomicReference<ScheduledFuture<?>> idleTimer = new AtomicReference<>();
		Watchdog watchdog = idleWatchdog
				? () -> {
					ScheduledFuture<?> previous = idleTimer.getAndSet(scheduler.schedule(
							() -> fail(result, settled, totalTimer, idleTimer, scheduler, onWatchdogChanged,
									"Export timed out during " + stage + " after "
											+ (long) Math.ceil(idleTimeoutMs / 1000.0)
											+ " seconds without observable progress"),
							idleTimeoutMs, TimeUnit.MILLISECONDS));
					if (previous != null) {
						previous.cancel(false);
					}
				}
				: null;
		try {
			if (watchdog != null) {
				if (onWatchdogChanged != null) {
					onWatchdogChanged.accept(watchdog);
				}
				watchdog.refreshProgress();
			}
			totalTimer.set(scheduler.schedule(
					() -> fail(result, settled, totalTimer, idleTimer, scheduler, onWatchdogChanged,
							"Export timed out during " + stage + " after "
									+ (long) Math.ceil(timeoutMs / 60_000.0) + " minutes"),
					timeoutMs, TimeUnit.MILLISECONDS));
			promise.whenComplete((value, error) -> {
				if (!settled.compareAndSet(false, true)) {
					return;
				}
				cancel(totalTimer);
				cancel(idleTimer);
				if (onWatchdogChanged != null) {
					onWatchdogChanged.accept(null);
				}
				scheduler.shutdownNow();
				if (error != null) {
					result.completeExceptionally(error);
				} else {
					result.complete(value);
				}
			});
		} catch (RuntimeException error) {
			scheduler.shutdownNow();
			throw error;
		}
		return result;
	}

	private static void fail(CompletableFuture<?> result, AtomicBoolean settled,
			AtomicReference<ScheduledFuture<?>> totalTimer,
			AtomicReference<ScheduledFuture<?>> idleTimer, ScheduledExecutorService scheduler,
			Consumer<Watchdog> onWatchdogChanged, String message) {
		if (!settled.compareAndSet(false, true)) {
			return;
		}
		cancel(totalTimer);
		cancel(idleTimer);
		if (onWatchdogChanged != null) {
			onWatchdogChanged.accept(null);
		}
		scheduler.shutdownNow();
		result.completeExceptionally(new IllegalStateException(message));
	}

	private static void cancel(AtomicReference<ScheduledFuture<?>> timer) {
		ScheduledFuture<?> future = timer.get();
		if (future != null) {
			future.cancel(false);
		}
	}

	/** Convenience for callers that block: unwraps {@link ExecutionException} like {@code join()}. */
	public static <T> T await(CompletableFuture<T> future) {
		try {
			return future.get();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for export finalization", error);
		} catch (ExecutionException error) {
			if (error.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(error.getCause());
		}
	}
}
