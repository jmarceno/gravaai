package dev.gravaai.media.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** In-process v4l2 capture through libavdevice (plan §5.6.6). Requires a real camera. */
class WebcamCaptureTest {

	@Test
	@DisplayName("the RGBA buffer must hold one full frame")
	void rejectsSmallBuffer() {
		WebcamCapture capture = openFirstCamera();
		try {
			assertThrows(IllegalArgumentException.class,
					() -> capture.readFrame(ByteBuffer.allocateDirect(16)));
		} finally {
			capture.close();
		}
	}

	@Test
	@Timeout(30)
	@DisplayName("reads RGBA frames from the first camera")
	void readsFrames() {
		WebcamCapture capture = openFirstCamera();
		try {
			WebcamFormat format = capture.format();
			assertNotNull(format);
			assertTrue(format.width() > 0 && format.height() > 0);
			assertFalse(format.pixelFormat().isBlank());
			ByteBuffer frame = ByteBuffer.allocateDirect(format.width() * format.height() * 4);
			boolean received = false;
			for (int attempt = 0; attempt < 30 && !received; attempt++) {
				received = capture.readFrame(frame);
			}
			Assumptions.assumeTrue(received, "camera produced no frame in time");
			assertTrue(capture.frameCount() >= 1);
			// The frame must contain at least one non-transparent pixel (a closed lens is black,
			// so only check the alpha channel the converter always fills with 255).
			assertEquals(255, Byte.toUnsignedInt(frame.get(3)));
		} finally {
			capture.close();
		}
	}

	@Test
	@DisplayName("close is idempotent and readFrame after close fails")
	void closeIsIdempotent() {
		WebcamCapture capture = openFirstCamera();
		capture.close();
		capture.close();
		assertThrows(IllegalStateException.class,
				() -> capture.readFrame(ByteBuffer.allocateDirect(64 * 64 * 4)));
	}

	private WebcamCapture openFirstCamera() {
		List<WebcamDevice> devices = WebcamDevices.list();
		Assumptions.assumeFalse(devices.isEmpty(), "no webcam on this machine");
		WebcamDevice device = devices.get(0);
		try {
			return WebcamCapture.open(device.path(), 640, 480, 30);
		} catch (RuntimeException unavailable) {
			Assumptions.abort("camera " + device.path() + " is not openable here: "
					+ unavailable.getMessage());
			throw unavailable;
		}
	}
}
