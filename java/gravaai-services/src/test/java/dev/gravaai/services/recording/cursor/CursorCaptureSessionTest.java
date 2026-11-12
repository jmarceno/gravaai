package dev.gravaai.services.recording.cursor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.CursorScreen;
import dev.gravaai.services.recording.WindowSources;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;
import dev.gravaai.services.sources.CaptureSelection;
import dev.gravaai.services.sources.SelectedSource;

/**
 * The capture helper reports its first pointer position as soon as the capture stream is up —
 * before the recording-state message arms the session. A still pointer never sends a second one,
 * so a dropped first line leaves a whole window take anchored at the frame centre.
 */
class CursorCaptureSessionTest {

	private static final CursorScreen.Display PRIMARY =
			new CursorScreen.Display(1, 0, 0, 1920, 1080, 1);

	private static final SelectedSource WINDOW = CaptureSelection.asWindowSource(
			new WindowSources.CaptureWindow("5f1c", "code", "main.rs"));

	private final CursorCaptureSession session = new CursorCaptureSession(
			(command, options) -> {
				throw new IOException("no helper child in this test");
			},
			// The session never spawns its helper here (the processes seam throws first), so the
			// environment only needs paths it can name.
			new dev.gravaai.services.recording.ports.RecordingEnvironment() {
				@Override
				public java.nio.file.Path captureHelper() {
					return java.nio.file.Path.of("/helpers/gravaai-wayland-capture");
				}

				@Override
				public java.nio.file.Path cursorHelper() {
					return java.nio.file.Path.of("/helpers/gravaai-wayland-cursor");
				}

				@Override
				public java.nio.file.Path ffmpeg() {
					return java.nio.file.Path.of("/helpers/ffmpeg");
				}

				@Override
				public java.nio.file.Path rnnoiseModel() {
					return null;
				}

				@Override
				public java.nio.file.Path command(String name) throws IOException {
					throw new IOException("no system commands in this test");
				}
			},
			new CursorScreen() {
				@Override
				public Point cursorScreenPoint() {
					return new Point(0, 0);
				}

				@Override
				public List<Display> allDisplays() {
					return List.of(PRIMARY);
				}

				@Override
				public Display primaryDisplay() {
					return PRIMARY;
				}

				@Override
				public @Nullable Display displayNearestPoint(double x, double y) {
					return PRIMARY;
				}
			},
			() -> WINDOW,
			() -> "off");

	@AfterEach
	void tearDown() throws Exception {
		session.close();
	}

	@Test
	@DisplayName("a first reading that races the recording start is adopted, not dropped")
	void adoptsTheReadingThatRacedTheStart() {
		session.onCaptureCursorPoint(0.3, 0.6);
		assertArrayEquals(new double[] {0.5, 0.5}, session.getNormalizedCursorPoint(), 1e-9);

		session.start();

		assertArrayEquals(new double[] {0.3, 0.6}, session.getNormalizedCursorPoint(), 1e-9);
	}

	@Test
	@DisplayName("a reading that arrives once armed is used directly")
	void usesLiveReadingsDirectly() {
		session.start();
		session.onCaptureCursorPoint(0.25, 0.75);

		assertArrayEquals(new double[] {0.25, 0.75}, session.getNormalizedCursorPoint(), 1e-9);
	}

	@Test
	@DisplayName("the next take does not inherit the previous one's pointer")
	void forgetsTheReadingOnStop() {
		session.start();
		session.onCaptureCursorPoint(0.25, 0.75);
		session.stop();
		session.start();

		assertArrayEquals(new double[] {0.5, 0.5}, session.getNormalizedCursorPoint(), 1e-9);
	}
}
