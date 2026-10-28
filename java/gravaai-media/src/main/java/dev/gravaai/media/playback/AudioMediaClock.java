package dev.gravaai.media.playback;

/**
 * The audio-master playback clock (plan §5.9): {@code clock = samples written - pa_simple latency}.
 *
 * <p>The engine feeds the number of samples handed to libpulse-simple and the latency returned by
 * {@code pa_simple_get_latency}; on seek/pause the pulse stream is flushed and {@link #resetTo}
 * rebases the clock. Pure math so it is testable without PulseAudio.
 */
public final class AudioMediaClock implements PlaybackClock {

	public static final int SAMPLE_RATE = 48_000;

	/**
	 * With no audio written for this long while running (no audio stream, PulseAudio gone, decoder
	 * failure), the clock advances with wall time so the video never freezes on an audio fault.
	 */
	static final long STALL_FALLBACK_NANOS = 400_000_000L;

	private final java.util.function.LongSupplier nanoTime;
	private double baseSeconds;
	private long samplesWritten;
	private int latencyMicros;
	private boolean running;
	/** Wall time of the last feed, or of the reset/start when nothing was fed since. */
	private long lastProgressNanos;

	public AudioMediaClock() {
		this(System::nanoTime);
	}

	AudioMediaClock(java.util.function.LongSupplier nanoTime) {
		this.nanoTime = nanoTime;
		this.lastProgressNanos = nanoTime.getAsLong();
	}

	/** Absolute sample/latency counters from the audio output; call after every write. */
	public synchronized void onSamplesWritten(long samples, int latencyMicros) {
		long next = Math.max(0, samples);
		if (next != samplesWritten) {
			lastProgressNanos = nanoTime.getAsLong();
		}
		this.samplesWritten = next;
		this.latencyMicros = Math.max(0, latencyMicros);
	}

	@Override
	public synchronized void resetTo(double seconds) {
		this.baseSeconds = Double.isFinite(seconds) ? seconds : 0;
		this.samplesWritten = 0;
		this.latencyMicros = 0;
		this.lastProgressNanos = nanoTime.getAsLong();
	}

	@Override
	public synchronized void setRunning(boolean running) {
		if (running && !this.running) {
			lastProgressNanos = nanoTime.getAsLong();
		}
		if (!running && this.running) {
			// Freeze any wall-time fallback into the base so a paused clock keeps its position.
			baseSeconds += stallSeconds();
		}
		this.running = running;
	}

	@Override
	public synchronized double positionSeconds() {
		return positionSeconds(baseSeconds, samplesWritten, latencyMicros) + stallSeconds();
	}

	private double stallSeconds() {
		if (!running) {
			return 0;
		}
		long stalled = nanoTime.getAsLong() - lastProgressNanos - STALL_FALLBACK_NANOS;
		return stalled > 0 ? stalled / 1e9 : 0;
	}

	/** Clock math alone: {@code base + samples/rate - latency}. */
	public static double positionSeconds(double baseSeconds, long samplesWritten, int latencyMicros) {
		return baseSeconds + samplesWritten / (double) SAMPLE_RATE - latencyMicros / 1_000_000.0;
	}
}
