package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.audio.PlaybackAudioSink;
import dev.gravaai.media.ffmpeg.MediaProbe;

/**
 * The meeting audio player end to end (meeting audio player card): two real ffmpeg CLI decoders
 * (standing in for the mic and system sidecars) mix into one float pump through the production
 * {@link MeetingAudioProducer}, and the {@link MeetingPlayback} transport carries it to ENDED. The
 * sink is a fake, so the test never touches PulseAudio.
 */
@Timeout(120)
class MeetingPlaybackRealFixtureTest {

	/** Locates the native ffmpeg build from the module or repo root (dev layout). */
	private static Path bundledFfmpeg() {
		for (Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath(); start != null;
				start = start.getParent()) {
			for (String relative : List.of(
					"native/build/ffmpeg/bin/ffmpeg",
					"native/build/linux-x64/ffmpeg/bin/ffmpeg",
					"java/native/build/ffmpeg/bin/ffmpeg",
					"java/native/build/linux-x64/ffmpeg/bin/ffmpeg")) {
				Path candidate = start.resolve(relative);
				if (Files.isExecutable(candidate)) {
					return candidate;
				}
			}
		}
		return Path.of("missing-ffmpeg");
	}

	private static Path audioFixture() {
		for (Path candidate : List.of(
				Path.of("parity", "fixtures", "media", "aac-48k-stereo.m4a"),
				Path.of("..", "parity", "fixtures", "media", "aac-48k-stereo.m4a"),
				Path.of("..", "..", "parity", "fixtures", "media", "aac-48k-stereo.m4a"))) {
			if (Files.isRegularFile(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		return Path.of("missing-fixture");
	}

	private static final class CountingSink implements PlaybackAudioSink {

		int writes;
		long totalFrames;
		float firstSample = Float.NaN;
		float peak;

		@Override
		public void write(float[] interleavedStereo, int frameCount) {
			if (writes == 0) {
				firstSample = interleavedStereo[0];
			}
			for (int index = 0; index < frameCount * 2; index++) {
				peak = Math.max(peak, Math.abs(interleavedStereo[index]));
			}
			writes++;
			totalFrames += frameCount;
		}

		@Override
		public int latencyMicros() {
			return 0;
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 60_000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("condition not reached in time");
			}
			Thread.sleep(10);
		}
	}

	@Test
	@DisplayName("two real decoders mix into one pump and the transport ends cleanly")
	void twoRealDecodersMixAndEnd() throws InterruptedException {
		Path ffmpeg = bundledFfmpeg();
		Path fixture = audioFixture();
		assumeTrue(Files.isExecutable(ffmpeg), "bundled ffmpeg not built: " + ffmpeg);
		assumeTrue(Files.isRegularFile(fixture), "aac-48k-stereo.m4a fixture not found");

		CountingSink sink = new CountingSink();
		List<Double> opens = new ArrayList<>();
		MeetingAudioProducer producer = new MeetingAudioProducer(
				List.of(fixture.toString(), fixture.toString()),
				2.0,
				sink,
				(path, start, duration, filters) -> {
					opens.add(start);
					return FfmpegCliPcmSource.open(ffmpeg, path, start, duration, filters);
				},
				PlaybackAudioProducer.DEFAULT_CHUNK_FRAMES);

		producer.seek(0);
		producer.pump();
		assertEquals(2, opens.size(), "both sidecar decoders open at the same position");
		assertTrue(sink.writes > 0, "the mix produces output");
		assertFalse(Float.isNaN(sink.firstSample), "the summed chunk carries samples");

		// A seek restarts both decoders at the same later position.
		producer.seek(1);
		producer.pump();
		assertEquals(1.0, opens.get(opens.size() - 2), 1e-9);
		assertEquals(1.0, opens.get(opens.size() - 1), 1e-9);

		// Draining: both decoders reach EOF inside the 2 s window; the pump thread ends the media.
		long deadline = System.currentTimeMillis() + 60_000;
		while (!producer.ended() && System.currentTimeMillis() <= deadline) {
			producer.pump();
		}
		assertTrue(producer.ended(), "the mix drains to the end");
		producer.close();
	}

	@Test
	@DisplayName("the transport plays a real mix to ENDED with listeners on the way")
	void transportPlaysRealMixToEnded() throws InterruptedException {
		Path ffmpeg = bundledFfmpeg();
		Path fixture = audioFixture();
		assumeTrue(Files.isExecutable(ffmpeg), "bundled ffmpeg not built: " + ffmpeg);
		assumeTrue(Files.isRegularFile(fixture), "aac-48k-stereo.m4a fixture not found");

		double duration = Math.max(1.0, Math.min(3.0,
				MediaProbe.probe(fixture).durationSeconds()));
		List<PlaybackStatus> statuses = new CopyOnWriteArrayList<>();
		List<PlaybackTime> times = new CopyOnWriteArrayList<>();
		CountingSink sink = new CountingSink();

		MeetingPlayback playback = new MeetingPlayback(media -> new MeetingAudioProducer(
				media.audioPaths(), media.durationSeconds(), sink,
				(path, start, span, filters) -> FfmpegCliPcmSource.open(
						ffmpeg, path, start, span, filters),
				PlaybackAudioProducer.DEFAULT_CHUNK_FRAMES));
		playback.onStatus(statuses::add);
		playback.onTime(times::add);
		playback.setMedia(new MeetingPlayback.Media(
				List.of(fixture.toString(), fixture.toString()), duration));
		playback.play();

		await(() -> !statuses.isEmpty()
				&& statuses.get(statuses.size() - 1).state() == PlaybackState.ENDED);
		assertFalse(playback.isPlaying());
		PlaybackStatus last = statuses.get(statuses.size() - 1);
		assertEquals(duration, last.timelineSeconds(), 0.1, "the playhead lands on the end");
		assertTrue(sink.totalFrames > 0, "audio was actually decoded and written");
		assertTrue(times.get(times.size() - 1).timelineSeconds() > 0, "time updates arrived");
		playback.close();
	}

	@Test
	@DisplayName("a rate change rebuilds the real decoders' atempo chains")
	void rateChangeRebuildsRealChains() {
		Path ffmpeg = bundledFfmpeg();
		Path fixture = audioFixture();
		assumeTrue(Files.isExecutable(ffmpeg), "bundled ffmpeg not built: " + ffmpeg);
		assumeTrue(Files.isRegularFile(fixture), "aac-48k-stereo.m4a fixture not found");

		CountingSink sink = new CountingSink();
		List<List<String>> filtersPerOpen = new ArrayList<>();
		MeetingAudioProducer producer = new MeetingAudioProducer(
				List.of(fixture.toString()), 2.0, sink,
				(path, start, duration, filters) -> {
					filtersPerOpen.add(filters);
					return FfmpegCliPcmSource.open(ffmpeg, path, start, duration, filters);
				},
				PlaybackAudioProducer.DEFAULT_CHUNK_FRAMES);

		producer.setRate(2);
		producer.seek(0);
		producer.pump();
		assertEquals(List.of("atempo=2"), filtersPerOpen.get(0));
		assertTrue(sink.writes > 0, "the accelerated mix still produces output");
		producer.close();
	}
}
