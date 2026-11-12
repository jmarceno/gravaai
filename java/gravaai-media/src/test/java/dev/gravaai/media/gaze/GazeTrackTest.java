package dev.gravaai.media.gaze;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Frame lookup, gating and the on-disk format of the eye-contact analysis. */
class GazeTrackTest {

	private static final int N = 90;
	private static final double FPS = 30;

	/** An open-eyed face looking at the screen, with blinks/look-aways injected by index. */
	private static GazeTrack track(java.util.function.IntPredicate blink,
			java.util.function.IntPredicate away, java.util.function.IntPredicate missing) {
		double[] pts = new double[N];
		boolean[] valid = new boolean[N];
		float[] eyes = new float[N * GazeTrack.EYE_FLOATS];
		float[] iris = new float[N * 4];
		float[] roi = new float[N * 3];
		float[] yaw = new float[N];
		float[] presence = new float[N];
		for (int i = 0; i < N; i++) {
			pts[i] = i / FPS;
			valid[i] = !missing.test(i);
			float lid = blink.test(i) ? 1 : 8;
			for (int e = 0; e < 2; e++) {
				float cx = e == 0 ? 400 : 300;
				float[] xs = {cx - 20, cx - 7, cx + 7, cx + 20, cx + 7, cx - 7};
				float[] ys = {200, 200 - lid / 2, 200 - lid / 2, 200, 200 + lid / 2, 200 + lid / 2};
				for (int k = 0; k < 6; k++) {
					eyes[i * GazeTrack.EYE_FLOATS + e * 12 + k * 2] = xs[k];
					eyes[i * GazeTrack.EYE_FLOATS + e * 12 + k * 2 + 1] = ys[k];
				}
				iris[i * 4 + e * 2] = cx + (away.test(i) ? 15 : 0);
				iris[i * 4 + e * 2 + 1] = 200;
			}
			roi[i * 3] = 200;
			roi[i * 3 + 1] = 100;
			roi[i * 3 + 2] = 300;
			presence[i] = 12;
		}
		return new GazeTrack(640, 480, pts, valid, eyes, iris, roi, yaw, presence);
	}

	@Test
	@DisplayName("a steady face gets full weight and is found by media time")
	void steady() {
		GazeTrack track = track(i -> false, i -> false, i -> false);
		assertEquals(N, track.size());
		assertEquals(1, track.weightAt(1.0), 1e-6);
		GazeTrack.Sample sample = track.sampleAt(1.0 + 0.01).orElseThrow();
		assertEquals(30, sample.index());
		assertEquals(380, sample.x(0, 0), 1e-3);
	}

	@Test
	@DisplayName("a blink fades the correction out and back in over the ramp, not at once")
	void blinkRamps() {
		GazeTrack track = track(i -> i >= 40 && i < 44, i -> false, i -> false);
		assertEquals(0, track.weightAt(42 / FPS), 1e-6);
		double before = track.weightAt(38 / FPS);
		assertTrue(before > 0 && before < 1, "ramping down before the blink: " + before);
		assertEquals(1, track.weightAt(10 / FPS), 1e-6);
		assertEquals(1, track.weightAt(70 / FPS), 1e-6);
	}

	@Test
	@DisplayName("looking away from the usual reading position switches the correction off")
	void lookAway() {
		GazeTrack track = track(i -> false, i -> i >= 50 && i < 70, i -> false);
		assertEquals(0, track.weightAt(60 / FPS), 1e-6);
		assertEquals(1, track.weightAt(20 / FPS), 1e-6);
	}

	@Test
	@DisplayName("frames without a face have no sample and no weight")
	void missingFace() {
		GazeTrack track = track(i -> false, i -> false, i -> i >= 20 && i < 25);
		assertTrue(track.sampleAt(22 / FPS).isEmpty());
		assertEquals(0, track.weightAt(22 / FPS), 1e-6);
		assertTrue(track.sampleAt(-5).isEmpty());
		assertTrue(track.sampleAt(100).isEmpty());
	}

	@Test
	@DisplayName("a sample scales onto a frame decoded at another size")
	void scaled() {
		GazeTrack.Sample sample = track(i -> false, i -> false, i -> false).sampleAt(1)
				.orElseThrow().scaled(0.5, 0.5);
		assertEquals(190, sample.x(0, 0), 1e-3);
		assertEquals(150, sample.roi()[2], 1e-3);
	}

	@Test
	@DisplayName("round-trips through its file")
	void roundTrip(@TempDir Path dir) throws Exception {
		GazeTrack track = track(i -> i == 10, i -> i > 80, i -> i == 3);
		Path file = dir.resolve("a.gzt");
		track.write(file);
		assertTrue(Files.isRegularFile(file));
		GazeTrack read = GazeTrack.read(file);
		assertEquals(track.size(), read.size());
		for (int i = 0; i < N; i++) {
			double t = i / FPS;
			assertEquals(track.weightAt(t), read.weightAt(t), 1e-6, "weight at " + i);
			assertEquals(track.sampleAt(t).isPresent(), read.sampleAt(t).isPresent());
			if (track.sampleAt(t).isPresent()) {
				assertArrayEquals(track.sampleAt(t).get().eyes(), read.sampleAt(t).get().eyes(),
						1e-3f);
			}
		}
	}
}
