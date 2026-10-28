package dev.gravaai.ui.editor.preview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.playback.PlaybackFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Retained frame copies keep a paused preview alive across scene/renderer changes. */
class PreviewFrameSnapshotTest {

private static RgbaFrame frame(int width, int height, int r, int g, int b) {
ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
for (int index = 0; index < width * height; index++) {
pixels.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
}
pixels.position(0);
pixels.limit(pixels.capacity());
return new RgbaFrame(width, height, pixels);
}

@DisplayName("capture owns the decoder pixels after the engine reuses its buffers")
@Test
void captureCopiesDecoderBuffers() {
RgbaFrame source = frame(2, 2, 1, 2, 3);
RgbaFrame webcam = frame(1, 1, 9, 8, 7);
PlaybackFrame original = new PlaybackFrame(7, 1.25, 0.5, "clip-1", source, webcam, true);

PreviewFrameSnapshot snapshot = PreviewFrameSnapshot.capture(original);

// The decoder is free to overwrite its buffers as soon as onFrame returns.
ByteBuffer reused = source.pixelsTopFirstRgba();
reused.position(0);
for (int index = 0; index < reused.capacity(); index++) {
reused.put((byte) 99);
}
reused.position(0);
reused.limit(reused.capacity());

PlaybackFrame retained = snapshot.playbackFrame();
assertEquals(7, retained.frameIndex());
assertEquals(1.25, retained.timelineSeconds(), 1e-9);
assertEquals(0.5, retained.sourceSeconds(), 1e-9);
assertEquals("clip-1", retained.clipId());
assertNotNull(retained.sourceFrame());
assertNotNull(retained.webcamFrame());
assertEquals(2, retained.sourceFrame().width());
assertEquals(1, retained.webcamFrame().width());

ByteBuffer pixels = retained.sourceFrame().pixelsTopFirstRgba().duplicate();
pixels.position(0);
byte[] actual = new byte[16];
pixels.get(actual);
assertArrayEquals(new byte[] {1, 2, 3, (byte) 255, 1, 2, 3, (byte) 255,
1, 2, 3, (byte) 255, 1, 2, 3, (byte) 255}, actual);
}
}
