package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.media.compose.RgbaFrame;

/** Engine tests with a fake frame provider, fake clock and manual scheduler (plan §5.9). */
class PlaybackEngineTest {

	private static ClipRegion clip(String id, long startMs, long endMs, Long sourceStartMs, double speed) {
		return new ClipRegion(id, startMs, endMs, sourceStartMs, null, null, speed, null, null, null, null);
	}

	private static PlaybackMedia media(List<ClipRegion> clips) {
		return new PlaybackMedia("/media/screen.mp4", null, 0, clips, 4_000, null);
	}

	private static final class FakeClock implements PlaybackClock {

		private double position;
		private boolean running;

		@Override
		public double positionSeconds() {
			return position;
		}

		@Override
		public void resetTo(double seconds) {
			position = seconds;
		}

		@Override
		public void setRunning(boolean running) {
			this.running = running;
		}

		void advance(double seconds) {
			if (running) {
				position += seconds;
			}
		}
	}

	private static final class ManualScheduler implements RenderScheduler {

		private Runnable task;
		int starts;
		int stops;

		@Override
		public void start(Runnable tick) {
			task = tick;
			starts++;
		}

		@Override
		public void stop() {
			task = null;
			stops++;
		}

		void tick() {
			Runnable current = task;
			if (current != null) {
				current.run();
			}
		}

		boolean running() {
			return task != null;
		}
	}

	private static final class FakeSource implements FrameSource {

		private final List<Double> requests = new ArrayList<>();
		private final double durationSeconds;
		boolean closed;
		private final RgbaFrame frame;

		FakeSource(double durationSeconds, int width, int height) {
			this.durationSeconds = durationSeconds;
			this.frame = new RgbaFrame(width, height, ByteBuffer.allocateDirect(width * height * 4));
		}

		@Override
		public RgbaFrame frameAt(double sourceSeconds) {
			requests.add(sourceSeconds);
			return frame;
		}

		@Override
		public double durationSeconds() {
			return durationSeconds;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private static final class FakeProvider implements FrameSourceProvider {

		final Map<String, FakeSource> sources = new LinkedHashMap<>();
		final Map<String, Double> durations = new LinkedHashMap<>();
		int opens;

		@Override
		public FrameSource open(String mediaPath) {
			opens++;
			FakeSource source = new FakeSource(durations.getOrDefault(mediaPath, 10.0), 4, 4);
			sources.put(mediaPath, source);
			return source;
		}
	}

	private final FakeProvider provider = new FakeProvider();
	private final FakeClock clock = new FakeClock();
	private final ManualScheduler scheduler = new ManualScheduler();
	private final List<PlaybackFrame> frames = new ArrayList<>();
	private final List<PlaybackStatus> statuses = new ArrayList<>();
	private final List<PlaybackTime> times = new ArrayList<>();

	private PlaybackEngine engine() {
		PlaybackEngine engine = new PlaybackEngine(
				new PlaybackEngine.Dependencies(provider, clock, scheduler, false));
		engine.onFrame(frames::add);
		engine.onStatus(statuses::add);
		engine.onTime(times::add);
		return engine;
	}

	@Test
	@DisplayName("renders the first frame and reports the duration on setMedia")
	void rendersFirstFrameOnSetMedia() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(clip("a", 0, 4_000, 0L, 1))));

		assertEquals(1, frames.size());
		PlaybackFrame frame = frames.get(0);
		assertEquals(0, frame.frameIndex());
		assertEquals(0.0, frame.timelineSeconds());
		assertEquals(0.0, frame.sourceSeconds(), 1e-9);
		assertEquals("a", frame.clipId());
		assertNotNull(frame.sourceFrame());
		assertEquals(PlaybackState.PAUSED, engine.status().state());
		assertEquals(4.0, engine.status().durationSeconds());
	}

	@Test
	@DisplayName("updateClips applies an editor cut without reopening the decoders")
	void updateClipsAppliesACutWithoutReopening() {
		// The preview kept the clips it was opened with: a cut range still played.
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(clip("a", 0, 4_000, 0L, 1))));
		int opens = provider.opens;
		engine.seek(3.5);
		frames.clear();

		// Cut source 1-2 s: two clips, 3 s of timeline.
		engine.updateClips(List.of(clip("a", 0, 1_000, 0L, 1), clip("b", 1_000, 3_000, 2_000L, 1)));

		assertEquals(opens, provider.opens, "the decoders are reused");
		assertEquals(3.0, engine.status().durationSeconds(), 1e-9);
		assertEquals(1, frames.size(), "the frame at the clamped playhead is shown again");
		assertTrue(frames.get(0).timelineSeconds() <= 3.0);
		frames.clear();
		engine.seek(1.5);
		assertEquals(2.5, frames.get(0).sourceSeconds(), 1e-9, "timeline 1.5 s maps past the cut");
		assertEquals("b", frames.get(0).clipId());

		frames.clear();
		engine.updateClips(List.of(clip("a", 0, 1_000, 0L, 1), clip("b", 1_000, 3_000, 2_000L, 1)));
		assertTrue(frames.isEmpty(), "unchanged clips are a no-op");
	}

	@Test
	@DisplayName("play advances the timeline and maps clips to source times")
	void playAdvancesTimelineAndMapsSourceTimes() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(
				clip("a", 0, 1_000, 1_000L, 1),
				clip("b", 2_000, 3_000, 6_000L, 1))));
		frames.clear();
		engine.play();
		clock.advance(0.5);
		scheduler.tick();

		assertEquals(1, frames.size());
		assertEquals(0.5, frames.get(0).timelineSeconds(), 1e-9);
		assertEquals(1.5, frames.get(0).sourceSeconds(), 1e-9);
		assertEquals("a", frames.get(0).clipId());
	}

	@Test
	@DisplayName("skips an empty timeline gap during playback")
	void skipsGapsDuringPlayback() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(
				clip("a", 0, 1_000, 0L, 1),
				clip("b", 2_000, 3_000, 6_000L, 1))));
		frames.clear();
		engine.play();
		clock.advance(1.5);
		scheduler.tick();

		assertEquals(1, frames.size());
		assertEquals(2.0, frames.get(0).timelineSeconds(), 1e-9);
		assertEquals(6.0, frames.get(0).sourceSeconds(), 1e-9);
		assertEquals("b", frames.get(0).clipId());
	}

	@Test
	@DisplayName("holds the final clip at the timeline end and reports ENDED")
	void holdsFinalEndpointAndEnds() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(clip("a", 0, 1_000, 0L, 1))));
		frames.clear();
		engine.play();
		clock.advance(1.0);
		scheduler.tick();

		assertEquals(1, frames.size());
		assertEquals(1.0, frames.get(0).timelineSeconds(), 1e-9);
		assertTrue(frames.get(0).sourceSeconds() < 1.0);
		assertTrue(frames.get(0).sourceSeconds() > 0.99);
		assertEquals("a", frames.get(0).clipId());
		assertEquals(PlaybackState.ENDED, engine.status().state());
		assertFalse(scheduler.running());
	}

	@Test
	@DisplayName("loops back to the start when loop is enabled")
	void loopsBackToStart() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(clip("a", 0, 1_000, 0L, 1))));
		engine.setLoop(true);
		frames.clear();
		engine.play();
		clock.advance(1.0);
		scheduler.tick();

		assertEquals(1, frames.size());
		assertEquals(0.0, frames.get(0).timelineSeconds(), 1e-9);
		assertEquals(PlaybackState.PLAYING, engine.status().state());
		assertTrue(engine.isLooping());
	}

	@Test
	@DisplayName("rate scales how fast the timeline advances")
	void rateScalesTimelineAdvance() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(clip("a", 0, 4_000, 0L, 1))));
		frames.clear();
		engine.setRate(2);
		engine.play();
		clock.advance(0.5);
		scheduler.tick();

		assertEquals(1, frames.size());
		assertEquals(1.0, frames.get(0).timelineSeconds(), 1e-9);
		assertEquals(2.0, engine.rate());
	}

	@Test
	@DisplayName("a paused seek renders exactly one frame at the target")
	void pausedSeekRendersOneFrame() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(clip("a", 0, 4_000, 0L, 1))));
		frames.clear();
		engine.seek(2.5);

		assertEquals(1, frames.size());
		assertEquals(2.5, frames.get(0).timelineSeconds(), 1e-9);
		assertEquals(2.5, frames.get(0).sourceSeconds(), 1e-9);
		assertEquals(PlaybackState.PAUSED, engine.status().state());
		assertEquals(2.5, engine.status().timelineSeconds(), 1e-9);
	}

	@Test
	@DisplayName("scrubbing during playback coalesces to the latest position")
	void scrubbingCoalescesLatest() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(clip("a", 0, 10_000, 0L, 1))));
		frames.clear();
		engine.play();
		clock.advance(0.1);
		engine.scrub(1.0);
		engine.scrub(3.0);
		engine.scrub(5.0);
		scheduler.tick();

		assertEquals(1, frames.size());
		assertEquals(5.0, frames.get(0).timelineSeconds(), 1e-9);
	}

	@Test
	@DisplayName("a paused drag shows each decoded frame while newer scrubs keep arriving")
	void pausedDragDeliversFramesMidDrag() {
		java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();
		PlaybackEngine[] holder = new PlaybackEngine[1];
		double[] nextPointer = {2.0, 3.0};
		int[] decodes = {0};
		RgbaFrame frame = new RgbaFrame(4, 4, ByteBuffer.allocateDirect(64));
		FrameSourceProvider dragging = path -> new FrameSource() {
			@Override
			public RgbaFrame frameAt(double sourceSeconds) {
				// The pointer moves again while every frame decodes (the real drag cadence).
				int index = decodes[0]++;
				if (index > 0 && index - 1 < nextPointer.length) {
					holder[0].scrub(nextPointer[index - 1]);
				}
				return frame;
			}

			@Override
			public double durationSeconds() {
				return 10;
			}

			@Override
			public void close() {
			}
		};
		PlaybackEngine engine = new PlaybackEngine(new PlaybackEngine.Dependencies(dragging, clock,
				scheduler, false, queued::add, false));
		holder[0] = engine;
		engine.onFrame(frames::add);
		engine.setMedia(media(List.of(clip("a", 0, 10_000, 0L, 1))));
		frames.clear();

		engine.scrub(1.0);
		while (!queued.isEmpty()) {
			queued.poll().run();
		}

		assertEquals(List.of(1.0, 2.0, 3.0),
				frames.stream().map(PlaybackFrame::timelineSeconds).toList());
	}

	@Test
	@DisplayName("a seek drops the frame of a scrub that was still decoding")
	void seekDropsInFlightScrubFrame() {
		java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();
		PlaybackEngine engine = new PlaybackEngine(new PlaybackEngine.Dependencies(provider, clock,
				scheduler, false, queued::add, false));
		engine.onFrame(frames::add);
		engine.setMedia(media(List.of(clip("a", 0, 10_000, 0L, 1))));
		frames.clear();

		engine.scrub(1.0);
		engine.seek(4.0);
		while (!queued.isEmpty()) {
			queued.poll().run();
		}

		assertEquals(List.of(4.0), frames.stream().map(PlaybackFrame::timelineSeconds).toList());
	}

	@Test
	@DisplayName("the webcam source follows its offset and visible ranges")
	void webcamFollowsOffsetAndRanges() {
		FakeProvider webcamProvider = provider;
		webcamProvider.durations.put("/media/cam.webm", 10.0);
		PlaybackMedia media = new PlaybackMedia(
				"/media/screen.mp4",
				"/media/cam.webm",
				500,
				List.of(clip("a", 0, 3_000, 0L, 1)),
				4_000,
				List.of(new WebcamSync.WebcamRange(1_000, 2_000)));
		PlaybackEngine engine = engine();
		engine.setMedia(media);

		FakeSource webcam = webcamProvider.sources.get("/media/cam.webm");
		assertNotNull(webcam);
		assertFalse(frames.get(frames.size() - 1).webcamVisible());
		assertNull(frames.get(frames.size() - 1).webcamFrame());

		engine.seek(2.0);
		PlaybackFrame frame = frames.get(frames.size() - 1);
		assertTrue(frame.webcamVisible());
		assertNotNull(frame.webcamFrame());
		assertEquals(1.5, webcam.requests.get(webcam.requests.size() - 1), 1e-9);
	}

	@Test
	@DisplayName("setMedia closes the previous sources")
	void setMediaClosesPreviousSources() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(clip("a", 0, 1_000, 0L, 1))));
		FakeSource first = provider.sources.get("/media/screen.mp4");
		engine.setMedia(media(List.of(clip("b", 0, 1_000, 0L, 1))));

		assertTrue(first.closed);
		assertEquals(2, provider.opens);
	}

	@Test
	@DisplayName("close releases the sources, the scheduler and the listeners")
	void closeReleasesEverything() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(clip("a", 0, 1_000, 0L, 1))));
		FakeSource source = provider.sources.get("/media/screen.mp4");
		engine.close();

		assertTrue(source.closed);
		assertFalse(scheduler.running());
		assertEquals(PlaybackState.IDLE, engine.status().state());
		engine.play();
		assertFalse(engine.isPlaying());
	}

	@Test
	@DisplayName("status and time listeners see play, pause and seeks")
	void statusAndTimeListenersSeeTransportChanges() {
		PlaybackEngine engine = engine();
		engine.setMedia(media(List.of(clip("a", 0, 4_000, 0L, 1))));
		statuses.clear();
		times.clear();
		engine.play();
		clock.advance(0.25);
		scheduler.tick();
		engine.pause();
		engine.seek(1.0);

		assertTrue(statuses.stream().anyMatch(status -> status.state() == PlaybackState.PLAYING));
		assertTrue(statuses.stream().anyMatch(status -> status.state() == PlaybackState.PAUSED));
		assertTrue(times.stream().anyMatch(time -> time.timelineSeconds() == 0.25));
		assertTrue(times.stream().anyMatch(time -> time.timelineSeconds() == 1.0));
	}

	@Test
	@DisplayName("volume is clamped to 0..1")
	void volumeIsClamped() {
		PlaybackEngine engine = engine();
		assertEquals(1.0, engine.volume());
		engine.setVolume(0.4);
		assertEquals(0.4, engine.volume(), 1e-9);
		engine.setVolume(5);
		assertEquals(1.0, engine.volume());
		engine.setVolume(-1);
		assertEquals(0.0, engine.volume());
	}
}
