package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.gl.PboFormat;

/**
 * The FX hand-off policy of {@link JavaFxPreviewSink}. The PixelBuffer/ImageView path needs a
 * JavaFX toolkit and is exercised by the editor preview panel tests (P2.5); here the
 * frame-drop/back-pressure contract is tested without starting JavaFX.
 */
class JavaFxPreviewSinkTest {

	@Test
	@DisplayName("drops frames until the preview is attached instead of throwing")
	void dropsFramesBeforeAttach() {
		JavaFxPreviewSink sink = new JavaFxPreviewSink(16, 8);
		ByteBuffer pixels = ByteBuffer.allocateDirect(16 * 8 * 4);
		for (int i = 0; i < 3; i++) {
			sink.frame(pixels, 16, 8, i);
		}
		assertEquals(3, sink.framesReceived());
		assertEquals(3, sink.framesDropped());
	}

	@Test
	@DisplayName("a filled upload buffer is rewound for Prism's texture upload")
	void uploadBufferIsRewound() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(16 * 8 * 4);
		ByteBuffer source = ByteBuffer.allocateDirect(16 * 8 * 4);
		buffer.position(0);
		buffer.put(source);
		assertEquals(0, buffer.remaining(), "the bulk put leaves nothing readable");
		JavaFxPreviewSink.rewindForUpload(buffer);
		assertEquals(0, buffer.position());
		assertEquals(buffer.capacity(), buffer.limit());
		assertEquals(buffer.capacity(), buffer.remaining());
	}

	@Test
	@DisplayName("rejects invalid sizes and a null ImageView")
	void rejectsInvalidInput() {
		assertThrows(IllegalArgumentException.class, () -> new JavaFxPreviewSink(0, 8));
		assertThrows(IllegalArgumentException.class, () -> new JavaFxPreviewSink(8, -1));
		JavaFxPreviewSink sink = new JavaFxPreviewSink(8, 8);
		assertThrows(IllegalArgumentException.class, () -> sink.attach(null));
	}
}
