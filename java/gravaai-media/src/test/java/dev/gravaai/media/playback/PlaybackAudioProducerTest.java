package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.media.audio.PlaybackAudioSink;

/** Tests the real preview audio track with a fake PCM source and a fake sink (plan §5.9). */
@Timeout(15)
class PlaybackAudioProducerTest {

	private static ClipRegion clip(String id, long startMs, long endMs, Long sourceStartMs, double speed) {
		return new ClipRegion(id, startMs, endMs, sourceStartMs, null, null, speed, null, null, null, null);
	}

	private static final class FakeSink implements PlaybackAudioSink {

		int writes;
		int flushCount;
		int closeCount;
		int latency = 0;
		final List<Integer> framesPerWrite = new ArrayList<>();
		final List<Float> firstSamples = new ArrayList<>();

		@Override
		public void write(float[] interleavedStereo, int frameCount) {
			writes++;
			framesPerWrite.add(frameCount);
			firstSamples.add(interleavedStereo[0]);
		}

		@Override
		public int latencyMicros() {
			return latency;
		}

		@Override
		public void flush() {
			flushCount++;
		}

		@Override
		public void close() {
			closeCount++;
		}
	}

	private static final class FakeSource implements PcmSource {

		private final float value;
		private int remainingFrames;
		boolean closed;

		FakeSource(float value, int totalFrames) {
			this.value = value;
			this.remainingFrames = totalFrames;
		}

		@Override
		public int read(float[] interleavedStereo, int frameCount) {
			if (remainingFrames <= 0) {
				return -1;
			}
			int frames = Math.min(frameCount, remainingFrames);
			for (int index = 0; index < frames * 2; index++) {
				interleavedStereo[index] = value;
			}
			remainingFrames -= frames;
			return frames;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private record Open(String mediaPath, double start, double duration, List<String> filters) {
	}

	private static final class FakeFactory implements PlaybackAudioProducer.PcmSourceFactory {

		final List<Open> opens = new ArrayList<>();
		final List<FakeSource> sources = new ArrayList<>();
		private final int frames;
		private final float value;

		FakeFactory(int frames, float value) {
			this.frames = frames;
			this.value = value;
		}

		@Override
		public PcmSource open(
				String mediaPath, double sourceStartSeconds, double durationSeconds, List<String> filters) {
			opens.add(new Open(mediaPath, sourceStartSeconds, durationSeconds, filters));
			FakeSource source = new FakeSource(value, frames);
			sources.add(source);
			return source;
		}
	}

	private static final String MEDIA = "/media/screen.mp4";

	@Test
	@DisplayName("decodes the first segment and writes its PCM with the sink latency")
	void decodesFirstSegment() {
		FakeSink sink = new FakeSink();
		sink.latency = 777;
		FakeFactory factory = new FakeFactory(48_000, 0.5f);
		PlaybackAudioProducer producer = new PlaybackAudioProducer(
				MEDIA, List.of(clip("a", 0, 1_000, 0L, 1)), sink, factory);

		producer.seek(0);
		producer.pump();

		assertEquals(1, factory.opens.size());
		Open open = factory.opens.get(0);
		assertEquals(MEDIA, open.mediaPath());
		assertEquals(0.0, open.start(), 1e-9);
		assertEquals(1.0, open.duration(), 1e-9);
		assertEquals(List.of(), open.filters());
		assertEquals(1, sink.writes);
		assertEquals(1_920, sink.framesPerWrite.get(0));
		assertEquals(0.5f, sink.firstSamples.get(0));
		assertEquals(1_920, producer.samplesWritten());
		assertEquals(777, producer.latencyMicros());
	}

	@Test
	@DisplayName("applies the volume gain to the written samples")
	void appliesVolumeGain() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = new FakeFactory(48_000, 0.5f);
		PlaybackAudioProducer producer = new PlaybackAudioProducer(
				MEDIA, List.of(clip("a", 0, 1_000, 0L, 1)), sink, factory);
		producer.setVolume(0.5);
		producer.seek(0);
		producer.pump();

		assertEquals(0.25f, sink.firstSamples.get(0));
	}

	@Test
	@DisplayName("applies the clip-transition fade gain to the preview audio (P3.4)")
	void appliesClipTransitionFadeGain() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = new FakeFactory(48_000, 0.5f);
		ClipRegion fadeOut = new ClipRegion("a", 0, 1_000, null, null, null, 1, null, null, null,
				new dev.gravaai.core.project.ClipTransition(
						dev.gravaai.core.project.ClipTransitionKind.FADE_BLACK, 200));
		ClipRegion next = clip("b", 1_000, 2_000, null, 1);
		PlaybackAudioProducer producer = new PlaybackAudioProducer(
				MEDIA, List.of(fadeOut, next), sink, factory);

		// Fade window 0.9 s .. 1.1 s around the cut; seek to 0.95 s, the midpoint.
		producer.seek(0.95);
		producer.pump();

		// Gain 0.5 at the seek position, so 0.5 source amplitude becomes 0.25.
		assertEquals(0.25f, sink.firstSamples.get(0), 1e-6);

		// Outside every fade window the audio is untouched.
		FakeSink cleanSink = new FakeSink();
		PlaybackAudioProducer clean = new PlaybackAudioProducer(
				MEDIA, List.of(fadeOut, next), cleanSink, new FakeFactory(48_000, 0.5f));
		clean.seek(0.2);
		clean.pump();
		assertEquals(0.5f, cleanSink.firstSamples.get(0), 1e-6);
	}

	@Test
	@DisplayName("a mid-segment seek maps the timeline offset into the source")
	void midSegmentSeekMapsSourceOffset() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = new FakeFactory(48_000, 0.5f);
		PlaybackAudioProducer producer = new PlaybackAudioProducer(
				MEDIA, List.of(clip("a", 0, 1_000, 0L, 1)), sink, factory);
		producer.seek(0.5);
		producer.pump();

		assertEquals(0.5, factory.opens.get(0).start(), 1e-9);
		assertEquals(0.5, factory.opens.get(0).duration(), 1e-9);
	}

	@Test
	@DisplayName("writes silence over a gap and then opens the next segment")
	void writesSilenceOverGapThenOpensNextSegment() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = new FakeFactory(0, 0.5f);
		PlaybackAudioProducer producer = new PlaybackAudioProducer(
				MEDIA,
				List.of(clip("a", 0, 1_000, 0L, 1), clip("b", 2_000, 3_000, 0L, 1)),
				sink,
				factory);
		producer.seek(0);
		for (int index = 0; index < 40 && factory.opens.size() < 2; index++) {
			producer.pump();
		}

		assertEquals(2, factory.opens.size());
		assertEquals(0.0, factory.opens.get(1).start(), 1e-9);
		assertTrue(sink.writes > 1);
		assertTrue(sink.firstSamples.contains(0.0f));
	}

	@Test
	@DisplayName("a sped-up clip plans its source span and atempo chain")
	void speedRegionUsesAtempoChain() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = new FakeFactory(48_000, 0.5f);
		PlaybackAudioProducer producer = new PlaybackAudioProducer(
				MEDIA, List.of(clip("a", 0, 1_000, 2_000L, 2)), sink, factory);
		producer.seek(0);
		producer.pump();

		Open open = factory.opens.get(0);
		assertEquals(2.0, open.start(), 1e-9);
		assertEquals(2.0, open.duration(), 1e-9);
		assertEquals(List.of("atempo=2"), open.filters());
	}

	@Test
	@DisplayName("the preview rate adds another atempo chain")
	void rateAddsAnotherAtempoChain() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = new FakeFactory(48_000, 0.5f);
		PlaybackAudioProducer producer = new PlaybackAudioProducer(
				MEDIA, List.of(clip("a", 0, 1_000, 0L, 1)), sink, factory);
		producer.setRate(2);
		producer.seek(0);
		producer.pump();

		assertEquals(List.of("atempo=2"), factory.opens.get(0).filters());
	}

	@Test
	@DisplayName("pause flushes the output and releases the segment source")
	void pauseFlushesAndReleasesSource() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = new FakeFactory(48_000, 0.5f);
		PlaybackAudioProducer producer = new PlaybackAudioProducer(
				MEDIA, List.of(clip("a", 0, 1_000, 0L, 1)), sink, factory);
		producer.seek(0);
		producer.pump();
		int flushesBefore = sink.flushCount;
		producer.pause();

		assertTrue(sink.flushCount > flushesBefore, "pause flushes the output");
		assertTrue(factory.sources.get(0).closed);
		int writes = sink.writes;
		producer.pump();
		assertEquals(writes, sink.writes, "a paused producer writes nothing");
		producer.resume();
		producer.pump();
		assertTrue(sink.writes > writes, "resume lets the pump write again");
	}

	@Test
	@DisplayName("close releases the segment source and the sink")
	void closeReleasesSourceAndSink() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = new FakeFactory(48_000, 0.5f);
		PlaybackAudioProducer producer = new PlaybackAudioProducer(
				MEDIA, List.of(clip("a", 0, 1_000, 0L, 1)), sink, factory);
		producer.seek(0);
		producer.pump();
		producer.close();

		assertTrue(factory.sources.get(0).closed);
		assertEquals(1, sink.closeCount);
		assertEquals(1_920, producer.samplesWritten());
	}
}
