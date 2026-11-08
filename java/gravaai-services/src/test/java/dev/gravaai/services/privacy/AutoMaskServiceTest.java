package dev.gravaai.services.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.core.privacy.DetectedRegion;
import dev.gravaai.core.privacy.RegionTracker;
import dev.gravaai.services.privacy.AutoMaskService.Result;
import dev.gravaai.services.privacy.AutoMaskService.State;

/**
 * The auto-mask state machine, exercised with a fake {@link AutoMaskService.Detector} so the
 * transitions are tested without ONNX, a video, or the PII model.
 *
 * <p>The real pipeline has its own coverage ({@code AutoMaskServiceRealTest}); what matters here
 * is that a run publishes progress, ends in exactly one terminal state, and that cancel and a
 * failing detector surface as CANCELLED and ERROR rather than leaving the bar spinning.</p>
 */
class AutoMaskServiceTest {

	private static Path recording() {
		try {
			Path path = Path.of(AutoMaskServiceTest.class
					.getResource("/privacy/screen-recording.mp4").toURI());
			Assumptions.assumeTrue(Files.isRegularFile(path), "fixture missing");
			return path;
		} catch (Exception missing) {
			Assumptions.abort("screen-recording.mp4 fixture missing");
			throw new IllegalStateException(missing);
		}
	}

	private static RegionTracker.TimedRegion faceRegion() {
		return new RegionTracker.TimedRegion(
				new DetectedRegion(100, 100, 200, 200, DetectedRegion.Source.FACE), 0, 1_000);
	}

	/** Waits for the service to reach a terminal state. */
	private static AutoMaskService.Status awaitTerminal(AutoMaskService service) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (System.nanoTime() < deadline) {
			AutoMaskService.Status status = service.status();
			if (!status.running() && status.state() != State.IDLE) {
				return status;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("never reached a terminal state: " + service.status());
	}

	@Test
	@Timeout(60)
	@DisplayName("a run publishes progress and ends DONE with its regions")
	void publishesProgressThenDone() throws Exception {
		AutoMaskService service = new AutoMaskService((options, progress, signal) -> {
			progress.accept(10);
			progress.accept(80);
			return new Result(List.of(faceRegion()), 1, 0, 1, 0, 1920, 1080);
		}, Threads.daemon("automask-test"));
		try {
			service.start(new AutoMaskService.Options(recording(), 1_000, true, false));
			AutoMaskService.Status terminal = awaitTerminal(service);
			assertEquals(State.DONE, terminal.state());
			assertEquals(1, terminal.regions().size());
			assertEquals("Masked 1 face.", terminal.message());
			assertTrue(terminal.result() != null, "DONE must carry the result");
		} finally {
			service.close();
		}
	}

	@Test
	@Timeout(60)
	@DisplayName("cancel surfaces as CANCELLED, not ERROR")
	void cancelIsNotAnError() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		AutoMaskService service = new AutoMaskService((options, progress, signal) -> {
			started.countDown();
			// Blocks until the flag is set, exactly like the sampling loop between frames.
			for (int i = 0; i < 200; i++) {
				signal.throwIfCancelled();
				try {
					Thread.sleep(10);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			return new Result(List.of(), 0, 0, 0, 0, 0, 0);
		}, Threads.daemon("automask-test"));
		try {
			service.start(new AutoMaskService.Options(recording(), 1_000, true, false));
			assertTrue(started.await(5, TimeUnit.SECONDS), "the detector must start");
			service.cancel();
			assertEquals(State.CANCELLED, awaitTerminal(service).state());
		} finally {
			service.close();
		}
	}

	@Test
	@Timeout(60)
	@DisplayName("a failing detector surfaces as ERROR with its message")
	void failureSurfacesAsError() throws Exception {
		AutoMaskService service = new AutoMaskService((options, progress, signal) -> {
			throw new IllegalStateException("model exploded");
		}, Threads.daemon("automask-test"));
		try {
			service.start(new AutoMaskService.Options(recording(), 1_000, true, false));
			AutoMaskService.Status terminal = awaitTerminal(service);
			assertEquals(State.ERROR, terminal.state());
			assertEquals("model exploded", terminal.message());
			assertFalse(terminal.running());
		} finally {
			service.close();
		}
	}

	/** A region of the given kind, so the message test builds the same thing a run produces. */
	private static RegionTracker.TimedRegion regionOf(DetectedRegion.Source source, int y) {
		return new RegionTracker.TimedRegion(
				new DetectedRegion(100, y, 200, y + 100, source), 0, 1_000);
	}

	@Test
	@Timeout(60)
	@DisplayName("successful runs report how many regions of each kind were produced")
	void doneMessageDescribesTheRun() {
		assertEquals("Masked 2 faces and 1 text region.", AutoMaskService.doneMessage(
				new Result(List.of(regionOf(DetectedRegion.Source.FACE, 0),
						regionOf(DetectedRegion.Source.FACE, 200),
						regionOf(DetectedRegion.Source.TEXT, 400)), 2, 1, 3, 0, 1920, 1080)));
		assertEquals("Masked 3 text regions.", AutoMaskService.doneMessage(
				new Result(List.of(regionOf(DetectedRegion.Source.TEXT, 0),
						regionOf(DetectedRegion.Source.TEXT, 200),
						regionOf(DetectedRegion.Source.TEXT, 400)), 0, 3, 3, 0, 1920, 1080)));
		assertEquals("No faces or personal data found.", AutoMaskService.doneMessage(
				new Result(List.of(), 0, 0, 0, 0, 0, 0)));
	}

	@Test
	@Timeout(60)
	@DisplayName("options reject a missing recording or no enabled detector")
	void optionsAreValidated() {
		Path path = recording();
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new AutoMaskService.Options(Path.of("/nope/missing.mp4"), 1_000, true,
						true));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new AutoMaskService.Options(path, 0, true, true));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new AutoMaskService.Options(path, 1_000, false, false));
	}

	@Test
	@Timeout(60)
	@DisplayName("status snapshots carry the latest state to every listener")
	void listenersSeeEveryStatus() throws Exception {
		AtomicReference<AutoMaskService.Status> seen = new AtomicReference<>();
		AutoMaskService service = new AutoMaskService((options, progress, signal) -> {
			progress.accept(50);
			return new Result(List.of(faceRegion()), 1, 0, 1, 0, 1920, 1080);
		}, Threads.daemon("automask-test"));
		service.addListener(seen::set);
		try {
			service.start(new AutoMaskService.Options(recording(), 1_000, false, true));
			assertEquals(State.DONE, awaitTerminal(service).state());
			assertEquals(State.DONE, seen.get().state(),
					"the listener must have seen the final status");
		} finally {
			service.close();
		}
	}
}
