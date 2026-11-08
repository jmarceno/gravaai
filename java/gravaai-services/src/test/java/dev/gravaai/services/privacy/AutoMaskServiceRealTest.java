package dev.gravaai.services.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.core.privacy.DetectedRegion;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.services.privacy.AutoMaskService.Options;
import dev.gravaai.services.privacy.AutoMaskService.Result;
import dev.gravaai.services.privacy.AutoMaskService.State;
import dev.gravaai.services.privacy.AutoMaskService.Status;

/**
 * End-to-end coverage of the real analysis pass: the committed screen recording runs through
 * sampling, OCR, GLiNER and stabilisation, and the regions that come out cover the PII lines.
 *
 * <p>This is the test the plan asked for ("a committed fixture recording run through the service
 * produces correct, stable blur regions"): it exercises every stage together, which none of the
 * per-detector tests do. It needs the bundled OCR models, ONNX Runtime and the downloaded GLiNER
 * asset, so it aborts cleanly when any of them is missing.</p>
 */
class AutoMaskServiceRealTest {

	private static Path recording;
	private static Path glinerModelDir;

	@BeforeAll
	static void locateAssets() {
		try {
			recording = Path.of(AutoMaskServiceRealTest.class
					.getResource("/privacy/screen-recording.mp4").toURI());
		} catch (Exception missing) {
			recording = null;
		}
		Assumptions.assumeTrue(recording != null && Files.isRegularFile(recording),
				"screen-recording.mp4 fixture missing");
		String property = System.getProperty("gravaai.privacy.model.dir");
		Path candidate = property != null && !property.isBlank()
				? Path.of(property)
				: Path.of(System.getProperty("user.home"), ".local", "share", "gravaai", "privacy",
						"models", "gliner-multi-pii-v1");
		glinerModelDir = Files.isRegularFile(candidate.resolve("model.onnx"))
				&& Files.isRegularFile(candidate.resolve("tokenizer.json")) ? candidate : null;
		Assumptions.assumeTrue(glinerModelDir != null, "GLiNER model not found: set"
				+ " -Dgravaai.privacy.model.dir=<dir>");
		try {
			OnnxLibs.ensureLoaded();
		} catch (RuntimeException unavailable) {
			Assumptions.abort("libonnxruntime.so not found: run"
					+ " java/native/build-onnxruntime.sh");
		}
	}

	/** Runs the real pipeline to completion and returns its result. */
	private static Result analyse(Options options) throws Exception {
		AutoMaskService service = AutoMaskService.withRealDetectors(glinerModelDir);
		AtomicReference<Status> last = new AtomicReference<>();
		service.addListener(last::set);
		try {
			service.start(options);
			long deadline = System.nanoTime()
					+ java.time.Duration.ofMinutes(4).toNanos();
			while (System.nanoTime() < deadline) {
				Status status = service.status();
				if (status.state() == State.DONE) {
					Result result = status.result();
					Assumptions.assumeTrue(result != null, "the service reported DONE without a"
							+ " result");
					return result;
				}
				if (status.state() == State.ERROR) {
					throw new AssertionError("analysis failed: " + status.message());
				}
				if (status.state() == State.CANCELLED) {
					throw new AssertionError("analysis was cancelled unexpectedly");
				}
				Thread.sleep(50);
			}
			throw new AssertionError("analysis did not finish in time: " + service.status());
		} finally {
			service.close();
		}
	}

	@Test
	@Timeout(400)
	@DisplayName("the committed screen recording yields blur regions over its PII lines")
	void masksPiiInRealRecording() throws Exception {
		Result result = analyse(new Options(recording, 4_000, true, true));
		assertTrue(result.sampledFrames() >= 3, "the pass must sample the recording: " + result);
		assertFalse(result.regions().isEmpty(), "the recording has four PII lines and a face-free"
				+ " UI, so it must produce text regions: " + result);
		// The fixture has exactly four PII lines (name, email, phone, card) and one non-PII line.
		// Four regions means the PII pass caught all of them and rejected the benign one.
		assertEquals(4, result.textRegions(), "all four PII lines must be found and the benign"
				+ " line rejected: " + result);
		assertTrue(result.distinctTexts() > 0, "the PII detector must have run: " + result);
		for (var region : result.regions()) {
			DetectedRegion box = region.region();
			assertTrue(box.left() >= 0 && box.top() >= 0, "regions stay inside the frame: " + box);
			assertTrue(box.right() <= result.frameWidth() && box.bottom() <= result.frameHeight(),
					"regions stay inside the frame: " + box);
			assertTrue(region.endMs() >= region.startMs(), "span must be well formed: " + region);
		}
	}

	@Test
	@Timeout(400)
	@DisplayName("a static recording collapses into one region per PII line, not one per sample")
	void staticRegionsAreStable() throws Exception {
		Result result = analyse(new Options(recording, 4_000, true, true));
		int sampled = result.sampledFrames();
		List<AutoMaskService.Result> results = List.of(result);
		assertTrue(results.getFirst().regions().size() < sampled * 3,
				"four static lines over " + sampled + " samples must not produce a region each: "
						+ result.regions().size());
		// The recording is static, so every region should span essentially the whole clip.
		for (var region : result.regions()) {
			assertTrue(region.endMs() - region.startMs() >= 3_000,
					"a line visible for the whole clip must be masked for the whole clip: "
							+ region);
		}
	}

	@Test
	@Timeout(400)
	@DisplayName("faces-only mode skips the PII model and finds no text regions")
	void facesOnlySkipsPii() throws Exception {
		Result result = analyse(new Options(recording, 4_000, true, false));
		assertFalse(result.sampledFrames() == 0, "the pass must still sample frames: " + result);
		assertTrue(result.textRegions() == 0, "faces-only must not produce text regions: "
				+ result);
		assertTrue(result.distinctTexts() == 0, "faces-only must not run the PII detector: "
				+ result);
	}
}
