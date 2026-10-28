package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FrameInput / RgbaFrame value contracts (plan §5.8). */
class FrameInputTest {

	@Test
	@DisplayName("a background-only frame has no media and no cursor samples")
	void backgroundOnlyFrame() {
		FrameInput input = FrameInput.background(7, 0.5, new Background.Colour(0xFF102030));
		assertEquals(7, input.frameIndex());
		assertNull(input.sourceFrame());
		assertNull(input.webcamFrame());
		assertTrue(input.cursorSamples().isEmpty());
		assertEquals(new Background.Colour(0xFF102030), input.background());
		assertEquals(0.0, input.backgroundBlur());
		assertSame(LayoutSpec.FULL_FRAME, input.layout());
		assertSame(ZoomSpec.NONE, input.zoom());
		assertSame(FrameStyle.DEFAULT, input.style());
	}

	@Test
	@DisplayName("cursor samples are copied defensively")
	void cursorSamplesAreCopied() {
		List<CursorSample> samples = new ArrayList<>();
		samples.add(new CursorSample(0.0, 0.5, 0.5));
		FrameInput input = new FrameInput(0, 0.0, null, null, samples,
				Background.black(), 0, LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT, WebcamSpec.DISABLED);
		samples.add(new CursorSample(1.0, 0.1, 0.1));
		assertEquals(1, input.cursorSamples().size());
	}

	@Test
	@DisplayName("invalid frame index, time, blur and background are rejected")
	void invalidValuesRejected() {
		assertThrows(IllegalArgumentException.class, () -> new FrameInput(-1, 0.0, null, null,
				List.of(), Background.black(), 0, null, null, null, null, null));
		assertThrows(IllegalArgumentException.class, () -> new FrameInput(0, Double.NaN, null,
				null, List.of(), Background.black(), 0, null, null, null, null, null));
		assertThrows(IllegalArgumentException.class, () -> new FrameInput(0, 0.0, null, null,
				List.of(), Background.black(), -1, null, null, null, null, null));
		assertThrows(IllegalArgumentException.class, () -> new FrameInput(0, 0.0, null, null,
				List.of(), null, 0, null, null, null, null, null));
	}

	@Test
	@DisplayName("null layout, zoom and style mean full frame without extras")
	void nullDefaults() {
		FrameInput input = new FrameInput(0, 0.0, null, null, List.of(), Background.black(), 0,
				null, null, null, null, null);
		assertSame(LayoutSpec.FULL_FRAME, input.layout());
		assertSame(ZoomSpec.NONE, input.zoom());
		assertSame(FrameStyle.DEFAULT, input.style());
	}

	@Test
	@DisplayName("an RgbaFrame needs a direct buffer of one full frame")
	void frameValidation() {
		ByteBuffer direct = ByteBuffer.allocateDirect(4 * 3 * 4);
		RgbaFrame frame = new RgbaFrame(4, 3, direct);
		assertEquals(16, frame.strideBytes());

		assertThrows(IllegalArgumentException.class,
				() -> new RgbaFrame(0, 1, ByteBuffer.allocateDirect(4)));
		assertThrows(IllegalArgumentException.class,
				() -> new RgbaFrame(2, 2, ByteBuffer.allocateDirect(4)));
		assertThrows(IllegalArgumentException.class,
				() -> new RgbaFrame(2, 2, ByteBuffer.allocate(2 * 2 * 4)));
	}
}
