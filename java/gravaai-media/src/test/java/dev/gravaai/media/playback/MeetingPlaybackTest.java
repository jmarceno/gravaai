package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests the meeting transport with a fake audio track (meeting audio player). */
@Timeout(15)
class MeetingPlaybackTest {

	private static final double DURATION = 1;
	private static final long CHUNK_SAMPLES = 1_920;

	private static final class FakeTrack implements MeetingAudioTrack {

		final List<Double> seeks = new CopyOnWriteArrayList<>();
		final List<Double> rates = new CopyOnWriteArrayList<>();
		final List<Double> volumes = new CopyOnWriteArrayList<>();
		final CopyOnWriteArrayList<Boolean> pauses = new CopyOnWriteArrayList<>();
		final CopyOnWriteArrayList<Boolean> resumes = new CopyOnWriteArrayList<>();
		volatile boolean closed;
		/** Samples produced so far; pump grows it until {@code totalSamples} is reached. */
		volatile long samples;
		private final long totalSamples = (long) (DURATION * PlaybackAudioProducer.SAMPLE_RATE);

		@Override
		public void pump() {
			long next = Math.min(totalSamples, samples + CHUNK_SAMPLES);
			samples = next;
		}

		@Override
		public void seek(double positionSeconds) {
			seeks.add(positionSeconds);
			samples = 0;
		}

		@Override
		public long samplesWritten() {
			return samples;
		}

		@Override
		public int latencyMicros() {
			return 0;
		}

		@Override
		public boolean ended() {
			return samples >= totalSamples;
		}

		@Override
		public void pause() {
			pauses.add(true);
		}

		@Override
		public void resume() {
			resumes.add(true);
		}

		@Override
		public void setRate(double rate) {
			rates.add(rate);
		}

		@Override
		public void setVolume(double volume) {
			volumes.add(volume);
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private static MeetingPlayback.Media media(String... paths) {
		return new MeetingPlayback.Media(List.of(paths), DURATION);
	}

	/** Polls until the condition holds, so the pump thread's publications are awaited, not raced. */
	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("condition not reached in time");
			}
			Thread.sleep(10);
		}
	}

	@Test
	@DisplayName("setMedia resets the playhead and starts paused")
	void setMediaStartsPaused() {
		FakeTrack track = new FakeTrack();
		MeetingPlayback playback = new MeetingPlayback(mediaValue -> track);
		List<PlaybackStatus> statuses = new CopyOnWriteArrayList<>();
		playback.onStatus(statuses::add);

		playback.setMedia(media("/m/a.opus"));
		playback.play();
		playback.pause();

		assertFalse(playback.isPlaying());
		assertFalse(statuses.isEmpty());
		assertEquals(PlaybackState.PAUSED, statuses.get(statuses.size() - 1).state());
		assertEquals(1, track.volumes.size(), "volume is applied to the fresh track");
		assertEquals(1, track.rates.size(), "rate is applied to the fresh track");
		playback.close();
		assertTrue(track.closed);
	}

	@Test
	@DisplayName("playing to the end publishes ENDED and stops")
	void playsToEndAndPublishesEnded() throws InterruptedException {
		MeetingPlayback playback = new MeetingPlayback(mediaValue -> new FakeTrack());
		List<PlaybackStatus> statuses = new CopyOnWriteArrayList<>();
		playback.onStatus(statuses::add);

		playback.setMedia(media("/m/a.opus"));
		playback.play();
		await(() -> !statuses.isEmpty()
				&& statuses.get(statuses.size() - 1).state() == PlaybackState.ENDED);

		assertFalse(playback.isPlaying());
		PlaybackStatus last = statuses.get(statuses.size() - 1);
		assertEquals(PlaybackState.ENDED, last.state());
		assertEquals(DURATION, last.timelineSeconds(), 1e-9);
		playback.close();
	}

	@Test
	@DisplayName("a seek while paused jumps and publishes the new position")
	void seekWhilePausedPublishesPosition() {
		FakeTrack track = new FakeTrack();
		MeetingPlayback playback = new MeetingPlayback(mediaValue -> track);
		List<PlaybackTime> times = new CopyOnWriteArrayList<>();
		playback.onTime(times::add);

		playback.setMedia(media("/m/a.opus"));
		playback.seek(0.5);

		assertEquals(0.5, track.seeks.get(0), 1e-9);
		PlaybackTime last = times.get(times.size() - 1);
		assertEquals(0.5, last.timelineSeconds(), 1e-9);
		assertFalse(playback.isPlaying());
		playback.close();
	}

	@Test
	@DisplayName("seeks clamp to the media duration")
	void seekClampsToDuration() {
		FakeTrack track = new FakeTrack();
		MeetingPlayback playback = new MeetingPlayback(mediaValue -> track);

		playback.setMedia(media("/m/a.opus"));
		playback.seek(50);

		assertEquals(DURATION, track.seeks.get(0), 1e-9);
		playback.close();
	}

	@Test
	@DisplayName("a rate change forwards to the track and publishes the status")
	void rateChangeForwardsToTrack() {
		FakeTrack track = new FakeTrack();
		MeetingPlayback playback = new MeetingPlayback(mediaValue -> track);
		List<PlaybackStatus> statuses = new CopyOnWriteArrayList<>();
		playback.onStatus(statuses::add);

		playback.setMedia(media("/m/a.opus"));
		playback.setRate(1.5);

		assertEquals(1.5, track.rates.get(track.rates.size() - 1), 1e-9);
		assertEquals(1.5, playback.rate(), 1e-9);
		assertEquals(1.5, statuses.get(statuses.size() - 1).rate(), 1e-9);
		playback.close();
	}

	@Test
	@DisplayName("replacing the media closes the old track")
	void setMediaClosesTheOldTrack() {
		FakeTrack first = new FakeTrack();
		FakeTrack second = new FakeTrack();
		List<FakeTrack> opened = new CopyOnWriteArrayList<>(List.of(first, second));
		MeetingPlayback playback = new MeetingPlayback(mediaValue -> opened.remove(0));

		playback.setMedia(media("/m/a.opus"));
		playback.setMedia(media("/m/b.opus"));

		assertTrue(first.closed);
		assertFalse(second.closed);
		playback.close();
		assertTrue(second.closed);
	}

	@Test
	@DisplayName("close stops the pump and closes the track; calls are inert afterwards")
	void closeIsIdempotentAndStopsEverything() throws InterruptedException {
		FakeTrack track = new FakeTrack();
		MeetingPlayback playback = new MeetingPlayback(mediaValue -> track);

		playback.setMedia(media("/m/a.opus"));
		playback.play();
		await(() -> track.samples > 0);
		playback.close();
		await(() -> !playback.isPlaying());
		long samples = track.samples;

		playback.play();
		playback.seek(0.5);
		Thread.sleep(50);
		assertEquals(samples, track.samples, "a closed transport pumps no further");
		assertTrue(track.closed);
	}
}
