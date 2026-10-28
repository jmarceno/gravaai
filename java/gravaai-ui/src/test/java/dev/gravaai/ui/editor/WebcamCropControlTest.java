package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.WebcamCropMath.Handle;
import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Behaviour of the webcam crop control (P3.4 gap 4): move/resize gestures, mirror flipping,
 * keyboard nudge and commit-on-release.
 *
 * <p>Needs a JavaFX toolkit, so it only runs when {@code DISPLAY} is set like the other UI tests.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class WebcamCropControlTest {

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			Platform.runLater(() -> { });
		}
	}

	/** Runs {@code work} on the FX thread and waits for it. */
	private static void onFx(ThrowingRunnable work) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				work.run();
			} catch (Throwable error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(60, TimeUnit.SECONDS), "FX work timed out");
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static WebcamCropControl control(CropRegion crop, List<CropRegion> commits) {
		WebcamCropControl control = new WebcamCropControl();
		control.resize(224, 224);
		control.setCropRegion(crop);
		control.setOnCropChange(commits::add);
		control.layout();
		return control;
	}

	@Test
	@DisplayName("a move gesture commits a translated, clamped crop")
	void moveGesture() throws Exception {
		onFx(() -> {
			List<CropRegion> commits = new ArrayList<>();
			WebcamCropControl control = control(new CropRegion(0, 0, 0.5, 0.5), commits);
			// 22.4 px of a 224 px control = 10% of the source.
			control.dragForTest(Handle.MOVE, 0, 0, 22.4, 22.4);
			assertEquals(1, commits.size());
			CropRegion next = commits.get(0);
			assertEquals(0.1, next.x(), 1e-9);
			assertEquals(0.1, next.y(), 1e-9);
			assertEquals(0.5, next.width(), 1e-9);
			assertEquals(0.5, next.height(), 1e-9);
			// The control keeps showing the committed crop.
			assertEquals(next, control.cropRegion());
		});
	}

	@Test
	@DisplayName("a move is clamped to the source bounds")
	void moveIsClamped() throws Exception {
		onFx(() -> {
			List<CropRegion> commits = new ArrayList<>();
			WebcamCropControl control = control(new CropRegion(0, 0, 0.5, 0.5), commits);
			control.dragForTest(Handle.MOVE, 0, 0, -50, -50);
			assertEquals(0, commits.get(0).x(), 1e-9);
			assertEquals(0, commits.get(0).y(), 1e-9);
			control.dragForTest(Handle.MOVE, 0, 0, 500, 500);
			CropRegion last = commits.get(commits.size() - 1);
			assertEquals(0.5, last.x(), 1e-9);
			assertEquals(0.5, last.y(), 1e-9);
		});
	}

	@Test
	@DisplayName("corner handles resize the matching edges")
	void cornerResize() throws Exception {
		onFx(() -> {
			List<CropRegion> commits = new ArrayList<>();
			WebcamCropControl control = control(new CropRegion(0.2, 0.2, 0.4, 0.4), commits);
			control.dragForTest(Handle.SE, 0, 0, 22.4, 22.4);
			CropRegion next = commits.get(0);
			assertEquals(0.2, next.x(), 1e-9);
			assertEquals(0.2, next.y(), 1e-9);
			assertEquals(0.5, next.width(), 1e-9);
			assertEquals(0.5, next.height(), 1e-9);

			commits.clear();
			control.dragForTest(Handle.NW, 0, 0, -22.4, -22.4);
			CropRegion shrink = commits.get(0);
			assertEquals(0.1, shrink.x(), 1e-9);
			assertEquals(0.1, shrink.y(), 1e-9);
			assertEquals(0.6, shrink.width(), 1e-9);
			assertEquals(0.6, shrink.height(), 1e-9);
		});
	}

	@Test
	@DisplayName("a mirrored webcam edits the flipped rectangle and stores source space")
	void mirroredEditIsFlippedBack() throws Exception {
		onFx(() -> {
			List<CropRegion> commits = new ArrayList<>();
			WebcamCropControl control = control(new CropRegion(0.2, 0, 0.4, 1), commits);
			control.setMirrored(true);
			// Visual crop is x = 1 - 0.2 - 0.4 = 0.4; +10% moves it right, stored x moves left.
			assertEquals(0.4, control.visualCrop().x(), 1e-9);
			control.dragForTest(Handle.MOVE, 0, 0, 22.4, 0);
			CropRegion next = commits.get(0);
			assertEquals(0.1, next.x(), 1e-9);
			assertEquals(0.4, next.width(), 1e-9);
		});
	}

	@Test
	@DisplayName("arrow keys nudge the crop by 1% (5% with shift)")
	void keyboardNudge() throws Exception {
		onFx(() -> {
			List<CropRegion> commits = new ArrayList<>();
			WebcamCropControl control = control(new CropRegion(0.4, 0.4, 0.2, 0.2), commits);
			control.handleKey(key(KeyCode.RIGHT, false));
			assertEquals(0.41, commits.get(0).x(), 1e-9);
			control.handleKey(key(KeyCode.DOWN, true));
			assertEquals(0.45, commits.get(1).y(), 1e-9);
			control.handleKey(key(KeyCode.LEFT, false));
			assertEquals(0.40, commits.get(2).x(), 1e-9);
			// Non-arrow keys are ignored.
			control.handleKey(key(KeyCode.ENTER, false));
			assertEquals(3, commits.size());
		});
	}

	@Test
	@DisplayName("a press without movement commits the unchanged crop once")
	void clickCommitsUnchangedCrop() throws Exception {
		onFx(() -> {
			List<CropRegion> commits = new ArrayList<>();
			WebcamCropControl control = control(new CropRegion(0, 0, 1, 1), commits);
			control.dragForTest(Handle.MOVE, 100, 100, 100, 100);
			assertEquals(1, commits.size());
			assertEquals(CropRegion.DEFAULT, commits.get(0));
		});
	}

	private static KeyEvent key(KeyCode code, boolean shift) {
		return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false);
	}
}
