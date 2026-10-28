package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for the audio-master clock and the monotonic fallback (plan §5.9). */
class PlaybackClockTest {

	@Test
	@DisplayName("the audio clock is samples written minus pulse latency")
	void audioClockSubtractsLatency() {
		AudioMediaClock clock = new AudioMediaClock();
		clock.resetTo(0);
		clock.onSamplesWritten(48_000, 20_000);
		assertEquals(0.98, clock.positionSeconds(), 1e-9);
		assertEquals(2.0, AudioMediaClock.positionSeconds(2.0, 0, 0), 1e-9);
	}

	@Test
	@DisplayName("flushing the audio stream rebases the clock")
	void audioClockRebasesOnFlush() {
		AudioMediaClock clock = new AudioMediaClock();
		clock.onSamplesWritten(48_000, 0);
		assertEquals(1.0, clock.positionSeconds(), 1e-9);
		clock.resetTo(2.0);
		assertEquals(2.0, clock.positionSeconds(), 1e-9);
		clock.onSamplesWritten(24_000, 0);
		assertEquals(2.5, clock.positionSeconds(), 1e-9);
	}

	@Test
	@DisplayName("the monotonic clock advances while playing and freezes on pause")
	void monotonicClockAdvancesAndFreezes() {
		AtomicLong now = new AtomicLong();
		MonotonicMediaClock clock = new MonotonicMediaClock(now::get);
		clock.resetTo(0);
		clock.setRunning(true);
		now.set(1_000_000_000L);
		assertEquals(1.0, clock.positionSeconds(), 1e-9);
		clock.setRunning(false);
		now.set(5_000_000_000L);
		assertEquals(1.0, clock.positionSeconds(), 1e-9);
	}

	@Test
	@DisplayName("seeking the monotonic clock keeps it running from the new position")
	void monotonicClockSeekKeepsRunning() {
		AtomicLong now = new AtomicLong();
		MonotonicMediaClock clock = new MonotonicMediaClock(now::get);
		clock.resetTo(0);
		clock.setRunning(true);
		now.set(2_000_000_000L);
		clock.resetTo(3.0);
		now.set(2_500_000_000L);
		assertEquals(3.5, clock.positionSeconds(), 1e-9);
	}
}
