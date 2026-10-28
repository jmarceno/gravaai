package dev.gravaai.ui.launch;

import dev.gravaai.media.audio.PulseAudio;
import dev.gravaai.media.audio.PulseRecordStream;
import java.util.function.Consumer;
import javafx.application.Platform;

/**
 * Live microphone level for the HUD mic menu (plan §5.6.9). Opens the PulseAudio 48 kHz mono
 * float stream on a platform thread, runs the analyser from {@code useAudioLevelMeter.ts}
 * ({@link AudioLevelAnalyser}) and publishes levels to the FX thread at ~60 Hz.
 *
 * <p>One monitor per visible device row; {@link #close()} stops the reader and the stream.
 */
public final class MicLevelMonitor implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.hud.mic");

	private static final long PUBLISH_INTERVAL_NANOS = 16_000_000; // ~60 Hz

	private volatile boolean running = true;
	private volatile PulseRecordStream stream;

	private MicLevelMonitor() {
	}

	/** Starts monitoring {@code sourceId}; the listener runs on the FX application thread. */
	public static MicLevelMonitor start(String sourceId, Consumer<Double> listener) {
		MicLevelMonitor monitor = new MicLevelMonitor();
		Thread reader = Thread.ofPlatform().daemon(true).name("hud-mic-level").unstarted(
				() -> monitor.read(sourceId, listener));
		reader.start();
		return monitor;
	}

	private void read(String sourceId, Consumer<Double> listener) {
		try (PulseRecordStream open = PulseAudio.openRecord(sourceId)) {
			stream = open;
			AudioLevelAnalyser analyser = new AudioLevelAnalyser();
			float[] buffer = new float[AudioLevelAnalyser.FFT_SIZE * 2];
			long lastPublish = 0;
			while (running) {
				int samples = open.read(buffer);
				if (samples < 0) {
					break;
				}
				double level = analyser.analyse(buffer);
				long now = System.nanoTime();
				if (now - lastPublish >= PUBLISH_INTERVAL_NANOS) {
					lastPublish = now;
					Platform.runLater(() -> {
						if (running) {
							listener.accept(level);
						}
					});
				}
			}
		} catch (RuntimeException error) {
			if (running) {
				LOG.log(System.Logger.Level.DEBUG, "mic level monitor for " + sourceId + " stopped: " + error);
			}
		}
	}

	@Override
	public void close() {
		running = false;
		PulseRecordStream open = stream;
		if (open != null) {
			try {
				open.close();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.DEBUG, "closing the mic level stream failed: " + error);
			}
		}
	}
}
