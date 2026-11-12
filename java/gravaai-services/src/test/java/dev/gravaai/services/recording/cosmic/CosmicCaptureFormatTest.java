package dev.gravaai.services.recording.cosmic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.cosmic.CosmicRecording.CaptureFormat;
import dev.gravaai.services.recording.ports.PlatformRecordingEnvironment;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * The capture helper's stderr contract: one {@code FORMAT} line decides the encoder's input, and
 * the {@code CURSOR} lines that follow carry the compositor's pointer reading in recorded-frame
 * coordinates. A window recording has no other correct pointer source, so a dropped or misparsed
 * line shows up as cursor effects offset from the pointer.
 */
class CosmicCaptureFormatTest {

	private static final class FakeProcess implements SpawnedProcess {

		private final InputStream stderr;

		FakeProcess(InputStream stderr) {
			this.stderr = stderr;
		}

		@Override
		public OutputStream stdin() {
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream stdout() {
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream stderr() {
			return stderr;
		}

		@Override
		public long pid() {
			return 1;
		}

		@Override
		public boolean isAlive() {
			return false;
		}

		@Override
		public int waitFor() {
			return 0;
		}

		@Override
		public void destroy() {
		}

		@Override
		public void destroyForcibly() {
		}
	}

	private static CosmicVideoPipeline pipeline() {
		return new CosmicVideoPipeline(
				(command, options) -> {
					throw new UnsupportedOperationException("no processes in this test");
				},
				new PlatformRecordingEnvironment(),
				new CosmicProcessIo((command, options) -> {
					throw new UnsupportedOperationException("no processes in this test");
				}));
	}

	private static FakeProcess helper(String stderrText) {
		return new FakeProcess(new ByteArrayInputStream(stderrText.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("the FORMAT line becomes the encoder's input geometry")
	void readsTheFormatLine() throws IOException {
		CaptureFormat format = pipeline().readCaptureFormat(
				helper("SOURCE window\nFORMAT 800 636 rgba 0\n"));

		assertEquals(new CaptureFormat(800, 636, "rgba", 0), format);
	}

	@Test
	@DisplayName("a helper that exits before FORMAT fails with its own error text")
	void reportsEarlyExit() {
		IOException error = assertThrows(IOException.class,
				() -> pipeline().readCaptureFormat(helper("no such output\n")));
		assertEquals("no such output", error.getMessage());
	}

	@Test
	@DisplayName("CURSOR lines are forwarded as normalized points, other lines are not")
	void forwardsCursorLines() throws Exception {
		List<double[]> points = new CopyOnWriteArrayList<>();
		CountDownLatch received = new CountDownLatch(2);
		pipeline().readCaptureFormat(
				helper("FORMAT 800 636 rgba 0\n"
						+ "helper: warming up\n"
						+ "CURSOR 0.580729 0.025000\n"
						+ "CURSOR 1.000000 0.000000\n"),
				(cx, cy) -> {
					points.add(new double[] {cx, cy});
					received.countDown();
				});

		assertTrue(received.await(5, TimeUnit.SECONDS), "the pointer lines must reach the listener");
		assertEquals(2, points.size());
		assertArrayEquals(new double[] {0.580729, 0.025}, points.get(0), 1e-9);
		assertArrayEquals(new double[] {1.0, 0.0}, points.get(1), 1e-9);
	}

	@Test
	@DisplayName("a CURSOR line that races the FORMAT line is forwarded, not treated as error text")
	void forwardsCursorLinesThatPrecedeTheFormat() throws Exception {
		List<double[]> points = new CopyOnWriteArrayList<>();
		CountDownLatch received = new CountDownLatch(1);
		pipeline().readCaptureFormat(
				helper("CURSOR 0.821875 0.772\nFORMAT 1920 1000 rgba 0\n"),
				(cx, cy) -> {
					points.add(new double[] {cx, cy});
					received.countDown();
				});

		assertTrue(received.await(5, TimeUnit.SECONDS), "the early pointer line must reach the listener");
		assertArrayEquals(new double[] {0.821875, 0.772}, points.getFirst(), 1e-9);
	}

	@Test
	@DisplayName("the overload without a listener still resolves the format")
	void ignoresPointerLinesWithoutAListener() throws IOException {
		CaptureFormat format = pipeline().readCaptureFormat(
				helper("FORMAT 2 2 bgra 0\nCURSOR 0.5 0.5\n"));

		assertEquals(new CaptureFormat(2, 2, "bgra", 0), format);
	}
}
