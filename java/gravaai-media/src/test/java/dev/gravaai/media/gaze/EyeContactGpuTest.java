package dev.gravaai.media.gaze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.compose.MediaFrameReader;
import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;

/**
 * The eye-contact pipeline end to end on a real webcam recording and this machine's GPU: analysis,
 * correction, the glasses-frame guarantee and the per-frame cost.
 *
 * <p>Opt-in: {@code -Dgravaai.gaze.gpu=true -Dgravaai.gaze.webcam=<recording>}, optionally
 * {@code -Dgravaai.gaze.out=<dir>} to keep the corrected frames (raw RGBA) for a visual check.</p>
 */
class EyeContactGpuTest {

	@Test
	@Timeout(900)
	void correctsRealRecordingOnTheGpu() throws Exception {
		Assumptions.assumeTrue(Boolean.getBoolean("gravaai.gaze.gpu"),
				"set -Dgravaai.gaze.gpu=true to run the eye-contact GPU test");
		Path webcam = Path.of(System.getProperty("gravaai.gaze.webcam", ""));
		Assumptions.assumeTrue(Files.isRegularFile(webcam), "no webcam recording at " + webcam);
		OnnxLibs.ensureLoaded();
		OnnxWebGpuPlugin.Selection selection = OnnxWebGpuPlugin.probe();
		assertTrue(selection.provider().hardware(), "this machine must run the models on the GPU: "
				+ selection.detail().orElse(""));

		GazeTrack track = GazeAnalyzer.analyze(webcam, selection, fraction -> {}, () -> false);
		assertTrue(track.size() > 100, "the analysis must cover the recording");
		int valid = 0;
		double weightSum = 0;
		for (double t = 0; t < track.durationSeconds(); t += 0.5) {
			if (track.sampleAt(t).isPresent()) {
				valid++;
				weightSum += track.weightAt(t);
			}
		}
		System.out.printf("track: %d frames, %d/%d half-second samples with a face, mean weight %.2f%n",
				track.size(), valid, (int) (track.durationSeconds() * 2), weightSum / Math.max(1, valid));
		assertTrue(valid > track.durationSeconds(), "most of the recording must have a face");

		Path out = Path.of(System.getProperty("gravaai.gaze.out", ""));
		boolean keep = !out.toString().isEmpty();
		if (keep) {
			Files.createDirectories(out);
		}
		try (MediaFrameReader reader = new MediaFrameReader(webcam, 0, false);
				EyeContactCorrector corrector = new EyeContactCorrector(selection);
				GlassesFrameSegmenter glasses = new GlassesFrameSegmenter(selection)) {
			System.out.println("providers: " + corrector.providerSummary());
			long nanos = 0;
			int corrected = 0;
			for (double t : new double[] {30, 75, 100, 130, 160}) {
				RgbaFrame decoded = reader.decode(t);
				Optional<GazeTrack.Sample> sample = track.sampleAt(reader.lastPtsSeconds());
				if (decoded == null || sample.isEmpty()) {
					continue;
				}
				RgbaFrame original = copy(decoded);
				RgbaFrame frame = copy(decoded);
				for (int warm = 0; warm < 3; warm++) {
					corrector.correct(copy(decoded), sample.get(), 15);
				}
				long started = System.nanoTime();
				int changed = corrector.correct(frame, sample.get(), 15);
				nanos += System.nanoTime() - started;
				corrected++;
				System.out.printf("t=%.0f weight %.2f changed %d px%n", t, sample.get().weight(), changed);
				assertUntouchedUnderFrame(original, frame, sample.get(), glasses);
				if (keep) {
					write(out.resolve(String.format("t%03d_src.rgba", (int) t)), original);
					write(out.resolve(String.format("t%03d_fix.rgba", (int) t)), frame);
				}
			}
			assertTrue(corrected > 0, "some frames must be corrected");
			System.out.printf("correction: %.1f ms per frame%n", nanos / 1e6 / corrected);
		}
	}

	/** Every pixel the dilated glasses alpha fully covers is byte-identical to the source. */
	private static void assertUntouchedUnderFrame(RgbaFrame original, RgbaFrame corrected,
			GazeTrack.Sample sample, GlassesFrameSegmenter glasses) {
		float[] roi = sample.roi();
		FaceSquare square = new FaceSquare(roi[0], roi[1], roi[2]);
		float[] logits = glasses.logits(original, square);
		int framePixels = 0;
		for (int eye = 0; eye < 2; eye++) {
			float[] xs = new float[6];
			float[] ys = new float[6];
			for (int k = 0; k < 6; k++) {
				xs[k] = sample.x(eye, k);
				ys[k] = sample.y(eye, k);
			}
			EyeContactMath.Patch patch = EyeContactMath.Patch.of(xs, ys);
			float[] alpha = EyeContactMath.glassesAlpha(patch, logits, square.x(), square.y(),
					square.side());
			for (int y = Math.max(0, patch.top()); y < Math.min(original.height(), patch.bottom()); y++) {
				for (int x = Math.max(0, patch.left()); x < Math.min(original.width(), patch.right()); x++) {
					if (alpha[(y - patch.top()) * patch.width() + x - patch.left()] < 1) {
						continue;
					}
					framePixels++;
					int o = (y * original.width() + x) * 4;
					for (int c = 0; c < 4; c++) {
						assertEquals(original.pixelsTopFirstRgba().get(o + c),
								corrected.pixelsTopFirstRgba().get(o + c),
								"glasses-frame pixel " + x + "," + y + " changed");
					}
				}
			}
		}
		System.out.println("  glasses-frame pixels held: " + framePixels);
	}

	private static RgbaFrame copy(RgbaFrame frame) {
		int bytes = frame.width() * frame.height() * 4;
		ByteBuffer buffer = ByteBuffer.allocateDirect(bytes);
		buffer.put(0, frame.pixelsTopFirstRgba(), 0, bytes);
		return new RgbaFrame(frame.width(), frame.height(), buffer);
	}

	private static void write(Path file, RgbaFrame frame) throws Exception {
		byte[] bytes = new byte[frame.width() * frame.height() * 4];
		frame.pixelsTopFirstRgba().get(0, bytes);
		Files.write(file, bytes);
	}
}
