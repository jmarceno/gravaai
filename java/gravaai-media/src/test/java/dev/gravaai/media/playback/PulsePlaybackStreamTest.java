package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.audio.PlaybackAudioSink;
import dev.gravaai.media.audio.PulseAudio;
import dev.gravaai.platform.process.ProcessRunner;

/** The libpulse-simple playback configuration and, when PulseAudio is live, a real open/write smoke. */
class PulsePlaybackStreamTest {

	@Test
	@DisplayName("uses float32 stereo 48 kHz with a 40 ms target buffer")
	void usesThePlannedFormatAndBuffer() {
		assertEquals(48_000, PlaybackAudioSink.SAMPLE_RATE);
		assertEquals(2, PlaybackAudioSink.CHANNELS);
		assertEquals(1_920, PlaybackAudioSink.DEFAULT_CHUNK_FRAMES);
		assertEquals(15_360, PlaybackAudioSink.TLENGTH_BYTES);
	}

	@Test
	@DisplayName("flushes from a different thread than the opener without WrongThreadException")
	void crossThreadFlushWorks() throws Exception {
		boolean available;
		try {
			available = ProcessRunner.run(List.of("pactl", "info"), Duration.ofSeconds(2)).succeeded();
		} catch (RuntimeException | java.io.IOException error) {
			available = false;
		}
		Assumptions.assumeTrue(available, "PulseAudio not running");
		PlaybackAudioSink sink;
		try {
			sink = PulseAudio.openPlayback();
		} catch (IllegalStateException error) {
			Assumptions.assumeTrue(false, "no PulseAudio playback device: " + error);
			return;
		}
		try (sink) {
			// The engine opens on attach, writes on the render thread and flushes on seek from FX.
			java.util.concurrent.atomic.AtomicReference<Throwable> failure =
					new java.util.concurrent.atomic.AtomicReference<>();
			Thread writer = Thread.ofPlatform().start(() -> {
				try {
					float[] silence = new float[PlaybackAudioSink.DEFAULT_CHUNK_FRAMES * 2];
					sink.write(silence, PlaybackAudioSink.DEFAULT_CHUNK_FRAMES);
					sink.latencyMicros();
					sink.flush();
				} catch (Throwable error) {
					failure.set(error);
				}
			});
			writer.join(5_000);
			sink.flush();
			assertEquals(null, failure.get());
		}
	}

	@Test
	@DisplayName("opens, writes silence, reports latency and flushes when PulseAudio is available")
	void opensWritesAndFlushes() throws Exception {
		boolean available;
		try {
			available = ProcessRunner.run(List.of("pactl", "info"), Duration.ofSeconds(2)).succeeded();
		} catch (RuntimeException | java.io.IOException error) {
			available = false;
		}
		Assumptions.assumeTrue(available, "PulseAudio not running");
		try (PlaybackAudioSink sink = PulseAudio.openPlayback()) {
			float[] silence = new float[PlaybackAudioSink.DEFAULT_CHUNK_FRAMES * 2];
			sink.write(silence, PlaybackAudioSink.DEFAULT_CHUNK_FRAMES);
			assertTrue(sink.latencyMicros() >= 0);
			sink.flush();
		} catch (IllegalStateException error) {
			Assumptions.assumeTrue(false, "no PulseAudio playback device: " + error);
		}
	}
}
