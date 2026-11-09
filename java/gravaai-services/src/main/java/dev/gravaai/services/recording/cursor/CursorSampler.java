package dev.gravaai.services.recording.cursor;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.services.recording.CursorTelemetry;

/**
 * The 30 Hz cursor sampler from {@code electron/ipc/cursor/telemetry.ts}.
 *
 * <p>It reschedules itself recursively instead of using a fixed-rate timer: under CPU load a
 * fixed-rate timer bunches or skips callbacks, creating large gaps in the telemetry. Each tick
 * measures how far it drifted from the expected instant so the next delay compensates.
 */
final class CursorSampler {

	/** One sample tick; the caller decides whether anything should actually be sampled. */
	@FunctionalInterface
	interface Tick {
		void sample();
	}

	private final ScheduledExecutorService sampler = Threads.daemonScheduler("gravaai-cursor-sampler");
	private final Tick tick;

	private @Nullable ScheduledFuture<?> sampleTimer;
	private long nextExpectedMs;
	private boolean running;

	CursorSampler(Tick tick) {
		this.tick = tick;
	}

	synchronized void start() {
		stop();
		running = true;
		nextExpectedMs = System.currentTimeMillis() + CursorTelemetry.CURSOR_SAMPLE_INTERVAL_MS;
		sampleTimer = sampler.schedule(this::sampleTick, CursorTelemetry.CURSOR_SAMPLE_INTERVAL_MS,
				TimeUnit.MILLISECONDS);
	}

	synchronized void stop() {
		running = false;
		if (sampleTimer != null) {
			sampleTimer.cancel(false);
			sampleTimer = null;
		}
	}

	/** Stops the sampler for good (app shutdown). */
	void close() {
		stop();
		sampler.shutdownNow();
	}

	private void sampleTick() {
		tick.sample();
		long now = System.currentTimeMillis();
		long drift = now - nextExpectedMs;
		nextExpectedMs += CursorTelemetry.CURSOR_SAMPLE_INTERVAL_MS;
		// If we fell behind by more than one full interval, reset the baseline so we do not try
		// to "catch up" with a burst of rapid samples.
		if (drift > CursorTelemetry.CURSOR_SAMPLE_INTERVAL_MS) {
			nextExpectedMs = now + CursorTelemetry.CURSOR_SAMPLE_INTERVAL_MS;
		}
		long delay = Math.max(1, nextExpectedMs - now);
		synchronized (this) {
			if (!running) {
				return;
			}
			sampleTimer = sampler.schedule(this::sampleTick, delay, TimeUnit.MILLISECONDS);
		}
	}
}
