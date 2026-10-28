package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.media.compose.RgbaFrame;

/** Audio-master clock wiring in the engine, with a fake audio track (plan §5.9). */
@Timeout(15)
class PlaybackEngineAudioTest {

	private static ClipRegion clip() {
		return new ClipRegion("a", 0, 4_000, 0L, null, null, 1, null, null, null, null);
	}

	private static PlaybackMedia media() {
		return new PlaybackMedia("/media/screen.mp4", null, 0, List.of(clip()), 4_000, null);
	}

	private static final class FakeAudioTrack implements PlaybackAudioTrack {

		private final long framesPerPump;
		private final int latencyMicros;
		long samples;
		int pumps;
		int pauses;
		int seeks;
		int closes;
		double lastSeek;
		double volume = 1;
		double rate = 1;

		FakeAudioTrack(long framesPerPump, int latencyMicros) {
			this.framesPerPump = framesPerPump;
			this.latencyMicros = latencyMicros;
		}

		@Override
		public void seek(double timelineSeconds) {
			seeks++;
			lastSeek = timelineSeconds;
			samples = 0;
		}

		@Override
		public void pump() {
			pumps++;
			samples += framesPerPump;
		}

		@Override
		public long samplesWritten() {
			return samples;
		}

		@Override
		public int latencyMicros() {
			return latencyMicros;
		}

		@Override
		public void pause() {
			pauses++;
		}

		@Override
		public void setRate(double rate) {
			this.rate = rate;
		}

		@Override
		public void setVolume(double volume) {
			this.volume = volume;
		}

		@Override
		public void close() {
			closes++;
		}
	}

	private static final class FakeSource implements FrameSource {

		private final RgbaFrame frame = new RgbaFrame(2, 2, ByteBuffer.allocateDirect(16));

		@Override
		public RgbaFrame frameAt(double sourceSeconds) {
			return frame;
		}

		@Override
		public double durationSeconds() {
			return 10;
		}

		@Override
		public void close() {
		}
	}

	private static final class ManualScheduler implements RenderScheduler {

		private Runnable task;

		@Override
		public void start(Runnable tick) {
			task = tick;
		}

		@Override
		public void stop() {
			task = null;
		}

		void tick() {
			Runnable current = task;
			if (current != null) {
				current.run();
			}
		}
	}

	private final AudioMediaClock clock = new AudioMediaClock();
	private final ManualScheduler scheduler = new ManualScheduler();
	private final List<PlaybackFrame> frames = new ArrayList<>();
	private final PlaybackEngine engine =
			new PlaybackEngine(new PlaybackEngine.Dependencies(file -> new FakeSource(), clock, scheduler, true));

	PlaybackEngineAudioTest() {
		engine.onFrame(frames::add);
		engine.setMedia(media());
		clock.resetTo(0);
	}

	@Test
	@DisplayName("the audio track advances the audio-master clock and the playhead")
	void audioTrackAdvancesClock() {
		FakeAudioTrack track = new FakeAudioTrack(4_800, 0);
		engine.setAudioTrack(track);
		frames.clear();
		engine.play();
		scheduler.tick();

		assertEquals(1, track.pumps);
		assertEquals(0.1, clock.positionSeconds(), 1e-9);
		assertEquals(1, frames.size());
		assertEquals(0.1, frames.get(0).timelineSeconds(), 1e-9);
	}

	@Test
	@DisplayName("audio latency is subtracted from the master clock")
	void latencyIsSubtractedFromTheClock() {
		FakeAudioTrack track = new FakeAudioTrack(4_800, 20_000);
		engine.setAudioTrack(track);
		engine.play();
		scheduler.tick();

		assertEquals(0.08, clock.positionSeconds(), 1e-9);
	}

	@Test
	@DisplayName("pause pauses the audio track and freezes the clock")
	void pausePausesAudioTrack() {
		FakeAudioTrack track = new FakeAudioTrack(4_800, 0);
		engine.setAudioTrack(track);
		engine.play();
		scheduler.tick();
		engine.pause();

		assertEquals(1, track.pauses);
		assertEquals(0.1, clock.positionSeconds(), 1e-9);
	}

	@Test
	@DisplayName("seek flushes the audio track to the target position")
	void seekFlushesAudioTrack() {
		FakeAudioTrack track = new FakeAudioTrack(4_800, 0);
		engine.setAudioTrack(track);
		engine.seek(2.0);

		assertTrue(track.seeks >= 1);
		assertEquals(2.0, track.lastSeek, 1e-9);
		assertEquals(2.0, clock.positionSeconds(), 1e-9);
		assertEquals(2.0, frames.get(frames.size() - 1).timelineSeconds(), 1e-9);
	}

	@Test
	@DisplayName("volume and rate are forwarded to the audio track")
	void volumeAndRateForwarding() {
		FakeAudioTrack track = new FakeAudioTrack(4_800, 0);
		engine.setAudioTrack(track);
		engine.setVolume(0.3);
		engine.setRate(2);

		assertEquals(0.3, track.volume, 1e-9);
		assertEquals(2.0, track.rate, 1e-9);
	}

	@Test
	@DisplayName("close closes the attached audio track")
	void closeClosesAudioTrack() {
		FakeAudioTrack track = new FakeAudioTrack(4_800, 0);
		engine.setAudioTrack(track);
		engine.close();

		assertEquals(1, track.closes);
	}
}
