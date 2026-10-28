package dev.gravaai.services.recording;

import java.util.List;
import java.util.function.Consumer;

import dev.gravaai.media.audio.PulseAudio;
import dev.gravaai.media.audio.PulseRecordStream;
import dev.gravaai.media.audio.PulseSource;

/**
 * The HUD microphone level meter, ported from {@code src/hooks/useAudioLevelMeter.ts} (plan
 * §5.6.9). A libpulse-simple record stream (media layer) is opened only while the meter is
 * visible; the analyser-equivalent maths lives in {@link AudioLevelMeter}.
 */
public final class MicLevelMeterService implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	/** 20 ms of 48 kHz mono audio per read, the PulseAudio fragment size. */
	private static final int SAMPLES_PER_READ = 960;

	/** Opens the PulseAudio record stream; injectable so tests do not need libpulse. */
	@FunctionalInterface
	public interface RecordStreamOpener {
		PulseRecordStream open(String sourceName);
	}

	/** Lists PulseAudio sources; injectable for the same reason. */
	@FunctionalInterface
	public interface SourceLister {
		List<PulseSource> list();
	}

	private final RecordStreamOpener opener;
	private final SourceLister lister;
	private final double smoothingFactor;
	private final Consumer<Double> onLevel;

	/**
	 * One meter session: the native stream, its once-only close guard and the pump thread that
	 * exclusively reads it. The stream is freed on the reader thread (or by {@link #close()} only
	 * after the reader exited) — libpulse aborts if a pa_stream is freed while another thread is
	 * inside pa_stream_get_state/pa_simple_read.
	 */
	private static final class Session {
		final PulseRecordStream stream;
		final java.util.concurrent.atomic.AtomicBoolean streamClosed =
				new java.util.concurrent.atomic.AtomicBoolean();
		volatile boolean stopping;
		volatile Thread pump;

		Session(PulseRecordStream stream) {
			this.stream = stream;
		}

		void closeStream() {
			if (streamClosed.compareAndSet(false, true)) {
				try {
					stream.close();
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.DEBUG, "microphone level stream close failed: " + error);
				}
			}
		}
	}

	private volatile Session session;

	public MicLevelMeterService(Consumer<Double> onLevel) {
		this(onLevel, 0.8);
	}

	public MicLevelMeterService(Consumer<Double> onLevel, double smoothingFactor) {
		this(onLevel, smoothingFactor, PulseAudio::openRecord, PulseAudio::listSources);
	}

	MicLevelMeterService(
			Consumer<Double> onLevel,
			double smoothingFactor,
			RecordStreamOpener opener,
			SourceLister lister) {
		this.onLevel = onLevel;
		this.smoothingFactor = smoothingFactor;
		this.opener = opener;
		this.lister = lister;
	}

	/** The microphones the HUD can show; the monitor source is listed only for system audio. */
	public List<PulseSource> listSources() {
		return lister.list();
	}

	/** Opens the stream for one device and starts publishing levels until {@link #close()}. */
	public synchronized void open(String deviceName) {
		close();
		PulseRecordStream created;
		try {
			created = opener.open(deviceName);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not open the microphone level stream: " + error);
			onLevel.accept(0.0);
			return;
		}
		Session current = new Session(created);
		session = current;
		AudioLevelMeter meter = new AudioLevelMeter(smoothingFactor);
		Thread pump = Thread.ofPlatform().daemon().name("gravaai-mic-level").start(() -> {
			float[] buffer = new float[SAMPLES_PER_READ];
			try {
				while (!current.stopping) {
					int read = current.stream.read(buffer);
					if (read < 0) {
						break;
					}
					double level = meter.update(buffer);
					if (!current.stopping) {
						onLevel.accept(level);
					}
				}
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "Microphone level stream ended: " + error);
			} finally {
				// The reader owns the native stream: free it only after the blocking read returned.
				current.closeStream();
				if (!current.stopping) {
					onLevel.accept(0.0);
				}
			}
		});
		current.pump = pump;
	}

	/**
	 * Stops the meter and publishes level 0. The native stream is only freed here when the reader
	 * thread already exited; otherwise the reader frees it as soon as its blocking read returns
	 * (never free a pa_stream another thread is inside).
	 */
	@Override
	public synchronized void close() {
		Session current = session;
		session = null;
		if (current != null) {
			current.stopping = true;
		}
		Thread currentPump = current == null ? null : current.pump;
		if (currentPump != null && currentPump != Thread.currentThread()) {
			try {
				currentPump.join(500);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		}
		if (current != null && (currentPump == null || !currentPump.isAlive())) {
			current.closeStream();
		}
		onLevel.accept(0.0);
	}
}
