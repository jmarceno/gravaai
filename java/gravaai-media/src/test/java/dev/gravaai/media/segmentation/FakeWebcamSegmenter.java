package dev.gravaai.media.segmentation;

import java.nio.ByteBuffer;

import dev.gravaai.media.compose.RgbaFrame;

/** Deterministic test segmenter: a constant mask at the frame's dimensions, counting calls. */
public final class FakeWebcamSegmenter implements WebcamSegmenter {

	private final int maskValue;
	private int calls;

	public FakeWebcamSegmenter(int maskValue) {
		this.maskValue = maskValue;
	}

	@Override
	public RgbaFrame maskFor(RgbaFrame frame) {
		calls++;
		ByteBuffer buffer = ByteBuffer.allocateDirect(frame.width() * frame.height() * 4);
		for (int pixel = 0; pixel < frame.width() * frame.height(); pixel++) {
			buffer.put((byte) maskValue).put((byte) maskValue).put((byte) maskValue)
					.put((byte) maskValue);
		}
		buffer.position(0);
		return new RgbaFrame(frame.width(), frame.height(), buffer);
	}

	/** How many frames reached the segmenter (the cache must collapse repeats). */
	public int calls() {
		return calls;
	}
}
