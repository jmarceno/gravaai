package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The golden comparison harness (plan §6.2). The real goldens arrive from task-13; these tests
 * pin the harness contract so the compositor suites can rely on it.
 */
class GoldenHarnessTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("identical frames pass with zero difference")
	void identicalFramesPass() throws Exception {
		RgbaFrame frame = solid(64, 32, 10, 20, 30);
		GoldenComparison.Result result = GoldenComparison.compare(frame, copy(frame),
				tempDir.resolve("diff.png"));
		assertTrue(result.passed(), result.summary());
		assertEquals(0.0, result.meanAbsDiff(), 1e-9);
		assertEquals(0.0, result.changedPixelRatio(), 1e-9);
		assertNull(result.diffPng());
	}

	@Test
	@DisplayName("a small amount of noise stays inside the plan thresholds")
	void smallNoisePasses() throws Exception {
		RgbaFrame reference = solid(64, 32, 10, 20, 30);
		RgbaFrame actual = copy(reference);
		// One pixel off by 2 everywhere -> below the max-diff tolerance; mean stays tiny.
		setPixel(actual, 0, 0, 12, 22, 32);
		GoldenComparison.Result result = GoldenComparison.compare(reference, actual,
				tempDir.resolve("diff.png"));
		assertTrue(result.passed(), result.summary());
		assertTrue(result.meanAbsDiff() <= 2.0);
	}

	@Test
	@DisplayName("a visibly different frame fails and writes a diff PNG")
	void differentFrameFailsAndWritesDiff() throws Exception {
		RgbaFrame reference = solid(64, 32, 10, 20, 30);
		RgbaFrame actual = solid(64, 32, 200, 20, 30);
		Path diff = tempDir.resolve("diff.png");
		GoldenComparison.Result result = GoldenComparison.compare(reference, actual, diff);
		assertFalse(result.passed(), result.summary());
		assertNotNull(result.diffPng());
		assertTrue(Files.isRegularFile(diff), "diff PNG was not written");
		RgbaFrame written = GoldenImages.read(diff);
		assertEquals(64, written.width());
		assertEquals(32, written.height());
		// Changed pixels are red in the diff image.
		assertEquals(255, Byte.toUnsignedInt(written.pixelsTopFirstRgba().get(0)));
		assertEquals(0, Byte.toUnsignedInt(written.pixelsTopFirstRgba().get(1)));
	}

	@Test
	@DisplayName("a size mismatch is a programming error, not a comparison result")
	void sizeMismatchRejected() {
		RgbaFrame reference = solid(64, 32, 0, 0, 0);
		RgbaFrame actual = solid(32, 32, 0, 0, 0);
		assertThrows(IllegalArgumentException.class,
				() -> GoldenComparison.compare(reference, actual, null));
	}

	@Test
	@DisplayName("reads a manifest array, resolves paths and validates lengths")
	void readsManifestArray() throws Exception {
		Path manifest = tempDir.resolve("golden").resolve("manifest.json");
		Files.createDirectories(manifest.getParent());
		Files.writeString(manifest, String.join("\n",
				"[",
				"  {",
				"    \"projectPath\": \"projects/fixture.gravaai\",",
				"    \"outputWidth\": 1920,",
				"    \"outputHeight\": 1080,",
				"    \"times\": [0.0, 1.5],",
				"    \"referenceImages\": [\"fixture/0.png\", \"fixture/1500.png\"]",
				"  }",
				"]"));
		List<GoldenManifest.Case> cases = GoldenManifest.load(manifest);
		assertEquals(1, cases.size());
		GoldenManifest.Case golden = cases.get(0);
		assertEquals(tempDir.resolve("golden/projects/fixture.gravaai"), golden.projectPath());
		assertEquals(1920, golden.outputWidth());
		assertEquals(1080, golden.outputHeight());
		assertEquals(List.of(0.0, 1.5), List.of(golden.times()[0], golden.times()[1]));
		assertEquals(tempDir.resolve("golden/fixture/1500.png"),
				golden.referenceImages().get(1));
	}

	@Test
	@DisplayName("accepts an object with a cases array and a single case object")
	void readsOtherManifestShapes() {
		Path directory = tempDir;
		String caseJson = "{\"projectPath\": \"p.gravaai\", \"outputWidth\": 640,"
				+ " \"outputHeight\": 360, \"times\": [0], \"referenceImages\": [\"r.png\"]}";
		assertEquals(1, GoldenManifest.parse("{\"cases\": [" + caseJson + "]}", directory).size());
		assertEquals(1, GoldenManifest.parse(caseJson, directory).size());
	}

	@Test
	@DisplayName("rejects a manifest with mismatched times and reference counts")
	void rejectsLengthMismatch() {
		String caseJson = "{\"projectPath\": \"p.gravaai\", \"outputWidth\": 640,"
				+ " \"outputHeight\": 360, \"times\": [0, 1],"
				+ " \"referenceImages\": [\"r.png\"]}";
		assertThrows(IllegalArgumentException.class,
				() -> GoldenManifest.parse(caseJson, tempDir));
	}

	@Test
	@DisplayName("loading the default manifest is empty until task-13 publishes it")
	void defaultManifestIsOptional() {
		// The repository may already have the manifest once platform lands the fixtures; either
		// way the call must not throw and, when present, must parse.
		GoldenManifest.loadDefault().ifPresent(cases -> assertNotNull(cases));
	}

	private static RgbaFrame solid(int width, int height, int red, int green, int blue) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int i = 0; i < width * height; i++) {
			buffer.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static RgbaFrame copy(RgbaFrame frame) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(frame.width() * frame.height() * 4);
		ByteBuffer source = frame.pixelsTopFirstRgba().duplicate();
		source.position(0);
		buffer.put(source);
		buffer.position(0);
		return new RgbaFrame(frame.width(), frame.height(), buffer);
	}

	private static void setPixel(RgbaFrame frame, int x, int y, int red, int green, int blue) {
		int offset = (y * frame.width() + x) * 4;
		frame.pixelsTopFirstRgba().put(offset, (byte) red);
		frame.pixelsTopFirstRgba().put(offset + 1, (byte) green);
		frame.pixelsTopFirstRgba().put(offset + 2, (byte) blue);
	}
}
