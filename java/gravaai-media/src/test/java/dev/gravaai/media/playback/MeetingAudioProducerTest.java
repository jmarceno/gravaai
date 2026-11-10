package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.audio.PlaybackAudioSink;

/** Tests the meeting mix track with fake PCM sources and a fake sink (meeting audio player). */
@Timeout(15)
class MeetingAudioProducerTest {

	private static final double DURATION = 4;

	private static final class FakeSink implements PlaybackAudioSink {

		int writes;
		int flushCount;
		int closeCount;
		final List<Integer> framesPerWrite = new ArrayList<>();
		final List<float[]> snapshots = new ArrayList<>();

		@Override
		public void write(float[] interleavedStereo, int frameCount) {
			writes++;
			framesPerWrite.add(frameCount);
			snapshots.add(java.util.Arrays.copyOf(interleavedStereo, frameCount * 2));
		}

		@Override
		public int latencyMicros() {
			return 0;
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

	private static final class FakeFactory implements MeetingAudioProducer.SourceFactory {

		final List<Open> opens = new ArrayList<>();
		final List<FakeSource> sources = new ArrayList<>();
		/** Frames each open produces, per audio path; the value follows the path index. */
		private final List<Integer> framesPerPath;
		private final List<Float> valuesPerPath;

		FakeFactory(List<Integer> framesPerPath, List<Float> valuesPerPath) {
			this.framesPerPath = framesPerPath;
			this.valuesPerPath = valuesPerPath;
		}

		static FakeFactory uniform(int frames, float... values) {
			List<Integer> framesPerPath = new ArrayList<>();
			List<Float> valuesPerPath = new ArrayList<>();
			for (float value : values) {
				framesPerPath.add(frames);
				valuesPerPath.add(value);
			}
			return new FakeFactory(framesPerPath, valuesPerPath);
		}

		@Override
		public PcmSource open(String mediaPath, double sourceStartSeconds, double durationSeconds,
				List<String> filters) {
			int index = opens.size();
			opens.add(new Open(mediaPath, sourceStartSeconds, durationSeconds, filters));
			FakeSource source = new FakeSource(
					valuesPerPath.get(Math.min(index, valuesPerPath.size() - 1)),
					framesPerPath.get(Math.min(index, framesPerPath.size() - 1)));
			sources.add(source);
			return source;
		}
	}

	private static MeetingAudioProducer producer(
			PlaybackAudioSink sink, MeetingAudioProducer.SourceFactory factory,
			String... paths) {
		return new MeetingAudioProducer(
				List.of(paths), DURATION, sink, factory, PlaybackAudioProducer.DEFAULT_CHUNK_FRAMES);
	}

	@Test
	@DisplayName("two sources decode into one summed stereo chunk")
	void twoSourcesSumIntoStereo() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = FakeFactory.uniform(48_000, 0.25f, 0.5f);
		MeetingAudioProducer producer = producer(sink, factory, "/m/meeting-mic.opus",
				"/m/meeting-system.opus");

		producer.seek(0);
		producer.pump();

		assertEquals(2, factory.opens.size());
		assertEquals("/m/meeting-mic.opus", factory.opens.get(0).mediaPath());
		assertEquals("/m/meeting-system.opus", factory.opens.get(1).mediaPath());
		assertEquals(1, sink.writes);
		assertEquals(0.75f, sink.snapshots.get(0)[0], 1e-6, "mic + system sum into the chunk");
		assertEquals(0.75f, sink.snapshots.get(0)[1], 1e-6);
	}

	@Test
	@DisplayName("a seek restarts every source at the same position")
	void seekRestartsEverySourceAtTheSamePosition() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = FakeFactory.uniform(48_000, 0.5f, 0.5f);
		MeetingAudioProducer producer = producer(sink, factory, "/m/a.opus", "/m/b.opus");

		producer.seek(0);
		producer.pump();
		producer.seek(1.5);
		producer.pump();

		assertTrue(factory.sources.get(0).closed && factory.sources.get(1).closed,
				"the pre-seek decoders are released");
		assertEquals(4, factory.opens.size());
		for (Open open : factory.opens.subList(2, 4)) {
			assertEquals(1.5, open.start(), 1e-9);
			assertEquals(DURATION - 1.5, open.duration(), 1e-9);
		}
	}

	@Test
	@DisplayName("a rate change rebuilds every source's atempo chain")
	void rateChangeRebuildsBothChains() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = FakeFactory.uniform(48_000, 0.5f, 0.5f);
		MeetingAudioProducer producer = producer(sink, factory, "/m/a.opus", "/m/b.opus");

		producer.setRate(2);
		producer.seek(1);
		producer.pump();

		for (Open open : factory.opens) {
			assertEquals(List.of("atempo=2"), open.filters());
			// The source span scales with the rate so the timeline still covers the remaining time.
			assertEquals((DURATION - 1) * 2, open.duration(), 1e-9);
		}
	}

	@Test
	@DisplayName("a source that ends early stays silent while the others finish")
	void drainedSourceStaysSilent() {
		FakeSink sink = new FakeSink();
		// The mic sidecar is a few frames long; the system track keeps playing.
		FakeFactory factory = new FakeFactory(new ArrayList<>(List.of(6, 48_000)),
				new ArrayList<>(List.of(0.5f, 0.25f)));
		MeetingAudioProducer producer = producer(sink, factory, "/m/a.opus", "/m/b.opus");

		producer.seek(0);
		producer.pump();

		float[] written = sink.snapshots.get(0);
		assertEquals(1_920, sink.framesPerWrite.get(0));
		assertEquals(0.5f + 0.25f, written[0], 1e-6, "both sources sound at the start");
		assertEquals(0.25f, written[100 * 2], 1e-6, "only the system track sounds after frame 6");
		assertFalse(producer.ended(), "the mix continues past the short sidecar");

		// A stale tail from the drained mic buffer must not leak into later chunks.
		producer.pump();
		float[] next = sink.snapshots.get(1);
		assertEquals(0.25f, next[0], 1e-6);
	}

	@Test
	@DisplayName("playback ends when every source has drained")
	void endsWhenAllSourcesDrain() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = FakeFactory.uniform(100, 0.5f, 0.5f);
		MeetingAudioProducer producer = producer(sink, factory, "/m/a.opus", "/m/b.opus");

		producer.seek(0);
		producer.pump();
		assertFalse(producer.ended());
		assertEquals(1, sink.writes, "the final partial chunk still goes out");

		producer.pump();
		assertTrue(producer.ended());
		assertEquals(1, sink.writes, "no silence is written after the end");
	}

	@Test
	@DisplayName("the position follows the samples written, clamped to the duration")
	void positionFollowsSamplesWritten() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = FakeFactory.uniform(48_000, 0.5f);
		MeetingAudioProducer producer = producer(sink, factory, "/m/a.opus");

		assertEquals(0, producer.positionSeconds(), 1e-9);
		producer.seek(0);
		producer.pump();
		assertEquals(1_920 / 48_000.0, producer.positionSeconds(), 1e-9);

		producer.seek(10);
		assertEquals(DURATION, producer.positionSeconds(), 1e-9, "seeks clamp to the duration");
	}

	@Test
	@DisplayName("pause flushes and releases the sources; resume plays again")
	void pauseFlushesAndResumePlays() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = FakeFactory.uniform(48_000, 0.5f);
		MeetingAudioProducer producer = producer(sink, factory, "/m/a.opus");

		producer.seek(0);
		producer.pump();
		int writes = sink.writes;
		producer.pause();
		assertTrue(factory.sources.get(0).closed);
		producer.pump();
		assertEquals(writes, sink.writes, "a paused producer writes nothing");

		producer.resume();
		producer.pump();
		assertTrue(sink.writes > writes, "resume lets the pump write again");
	}

	@Test
	@DisplayName("close releases the sources and the sink exactly once")
	void closeReleasesSourcesAndSink() {
		FakeSink sink = new FakeSink();
		FakeFactory factory = FakeFactory.uniform(48_000, 0.5f);
		MeetingAudioProducer producer = producer(sink, factory, "/m/a.opus", "/m/b.opus");

		producer.seek(0);
		producer.pump();
		producer.close();

		assertTrue(factory.sources.stream().allMatch(source -> source.closed));
		assertEquals(1, sink.closeCount);
		producer.close();
		assertEquals(1, sink.closeCount, "close is idempotent");
	}
}
