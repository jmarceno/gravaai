package dev.gravaai.media.playback;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * The production render scheduler: one daemon platform thread ticking at the requested frame rate
 * (plan §5.9: target 60 Hz; vsync is not available offscreen).
 *
 * <p>Each started thread owns its stop flag, so {@link #stop()} never has to wait for a tick that
 * is still decoding: the engine is called from the FX thread on pause and seek, and the engine
 * drops a frame decoded for a transport position it already left. The short join only keeps the
 * common case tidy.
 */
public final class ThreadRenderScheduler implements RenderScheduler {

	private static final long STOP_JOIN_MS = 20;

	private final long frameNanos;
	private @Nullable Thread thread;
	private @Nullable AtomicBoolean running;

	public ThreadRenderScheduler(double framesPerSecond) {
		if (!Double.isFinite(framesPerSecond) || framesPerSecond <= 0) {
			throw new IllegalArgumentException("framesPerSecond must be positive: " + framesPerSecond);
		}
		this.frameNanos = Math.max(1, Math.round(1_000_000_000.0 / framesPerSecond));
	}

	@Override
	public synchronized void start(Runnable tick) {
		if (tick == null) {
			throw new IllegalArgumentException("tick must not be null");
		}
		stop();
		AtomicBoolean active = new AtomicBoolean(true);
		this.running = active;
		this.thread = Thread.ofPlatform().daemon().name("gravaai-playback-render").start(() -> {
			long next = System.nanoTime();
			while (active.get()) {
				try {
					tick.run();
				} catch (RuntimeException error) {
					System.getLogger("gravaai.media.playback")
							.log(System.Logger.Level.WARNING, "render tick failed: " + error);
				}
				next += frameNanos;
				long sleepNanos = next - System.nanoTime();
				if (sleepNanos > 0) {
					LockSupport.parkNanos(sleepNanos);
				} else {
					// Fell behind; reset the cadence instead of trying to catch up in a burst.
					next = System.nanoTime();
				}
			}
		});
	}

	@Override
	public synchronized void stop() {
		AtomicBoolean active = running;
		Thread current = thread;
		running = null;
		thread = null;
		if (active != null) {
			active.set(false);
		}
		if (current != null && current != Thread.currentThread()) {
			LockSupport.unpark(current);
			try {
				current.join(STOP_JOIN_MS);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Override
	public synchronized void close() {
		stop();
	}

	/** The configured frame period in nanoseconds (diagnostics/tests). */
	public long frameNanos() {
		return frameNanos;
	}
}
