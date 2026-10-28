package dev.gravaai.ui.editor.preview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.ByteBuffer;

import dev.gravaai.media.compose.Background;
import dev.gravaai.media.compose.CursorSpec;
import dev.gravaai.media.compose.FrameInput;
import dev.gravaai.media.compose.FrameStyle;
import dev.gravaai.media.compose.LayoutSpec;
import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.compose.WebcamSpec;
import dev.gravaai.media.compose.ZoomSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link PreviewFrameQueue}: non-blocking copy hand-off between engine and GL (P2.11). */
class PreviewFrameQueueTest {

	private static RgbaFrame frame(int width, int height, int r, int g, int b) {
		ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
		for (int i = 0; i < width * height; i++) {
			pixels.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
		}
		pixels.position(0);
		pixels.limit(pixels.capacity());
		return new RgbaFrame(width, height, pixels);
	}

	private static FrameInput input(long index, double time, RgbaFrame source, RgbaFrame webcam) {
		return new FrameInput(index, time, source, webcam, java.util.List.of(),
				Background.black(), 0, LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT,
				CursorSpec.DEFAULT, WebcamSpec.DISABLED);
	}

	@DisplayName("submit copies the decoder frame before returning (engine buffers are released)")
	@Test
	void submitCopiesFrame() {
		PreviewFrameQueue queue = new PreviewFrameQueue();
		ByteBuffer pixels = ByteBuffer.allocateDirect(2 * 2 * 4);
		pixels.put(new byte[] {1, 2, 3, (byte) 255, 4, 5, 6, (byte) 255, 7, 8, 9, (byte) 255,
				10, 11, 12, (byte) 255});
		pixels.position(0);
		pixels.limit(pixels.capacity());
		RgbaFrame source = new RgbaFrame(2, 2, pixels);
		queue.submit(input(1, 0.5, source, null));
		// Simulate the decoder reusing its buffer after the callback returned.
		pixels.position(0);
		pixels.put(new byte[] {99, 99, 99, (byte) 255, 99, 99, 99, (byte) 255, 99, 99, 99,
				(byte) 255, 99, 99, 99, (byte) 255});
		rpixelsReset(pixels);

		FrameInput taken = queue.pollLatest();
		assertNotNull(taken);
		assertEquals(1, taken.frameIndex());
		assertEquals(0.5, taken.timeSeconds(), 1e-9);
		ByteBuffer copied = taken.sourceFrame().pixelsTopFirstRgba().duplicate();
		copied.position(0);
		byte[] actual = new byte[16];
		copied.get(actual);
		assertArrayEquals(new byte[] {1, 2, 3, (byte) 255, 4, 5, 6, (byte) 255, 7, 8, 9,
				(byte) 255, 10, 11, 12, (byte) 255}, actual);
		queue.finishRendering();
	}

	private static void rpixelsReset(ByteBuffer pixels) {
		pixels.position(0);
		pixels.limit(pixels.capacity());
	}

	@DisplayName("the latest submitted frame wins; older pending copies are coalesced away")
	@Test
	void coalescesToLatest() {
		PreviewFrameQueue queue = new PreviewFrameQueue();
		queue.submit(input(1, 0.0, frame(1, 1, 10, 0, 0), null));
		queue.submit(input(2, 0.1, frame(1, 1, 20, 0, 0), null));
		FrameInput taken = queue.pollLatest();
		assertEquals(2, taken.frameIndex());
		assertNull(queue.pollLatest());
		assertEquals(2, queue.submittedCount());
		queue.finishRendering();
		assertEquals(1, queue.drawnCount());
	}

	@DisplayName("webcam frames are copied too and the metadata is preserved")
	@Test
	void copiesWebcamAndMetadata() {
		PreviewFrameQueue queue = new PreviewFrameQueue();
		queue.submit(input(7, 1.25, frame(2, 2, 1, 2, 3), frame(1, 1, 9, 8, 7)));
		FrameInput taken = queue.pollLatest();
		assertNotNull(taken.webcamFrame());
		assertEquals(7, taken.frameIndex());
		assertSame(taken.cursor(), CursorSpec.DEFAULT);
		assertFalse(queue.hasPending());
		queue.finishRendering();
	}

	@DisplayName("a background-only frame (gap) is queued without frames")
	@Test
	void backgroundOnly() {
		PreviewFrameQueue queue = new PreviewFrameQueue();
		queue.submit(input(3, 2.0, null, null));
		FrameInput taken = queue.pollLatest();
		assertNull(taken.sourceFrame());
		assertNull(taken.webcamFrame());
		assertEquals(3, taken.frameIndex());
	}

	@DisplayName("after finishRendering the producer reuses the slot")
	@Test
	void slotReuse() {
		PreviewFrameQueue queue = new PreviewFrameQueue();
		queue.submit(input(1, 0, frame(2, 2, 1, 1, 1), null));
		queue.pollLatest();
		queue.finishRendering();
		queue.submit(input(2, 0, frame(2, 2, 2, 2, 2), null));
		FrameInput taken = queue.pollLatest();
		assertEquals(2, taken.frameIndex());
		queue.finishRendering();
	}
}
