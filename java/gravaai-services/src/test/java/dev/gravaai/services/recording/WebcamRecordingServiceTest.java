package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.capture.WebcamFormat;

/** Tests the webcam file naming/encoder-command helpers; capture itself is a device/manual gate. */
class WebcamRecordingServiceTest {

	@Test
	@DisplayName("names the webcam file next to the recording")
	void namesTheWebcamFileNextToTheRecording() {
		assertEquals(
				"recording-1720588800000-webcam.mkv",
				WebcamRecordingService.webcamFileName(1_720_588_800_000L));
	}

	@Test
	@DisplayName("encodes raw RGBA frames with software libx264 into an MKV")
	void buildsTheEncoderCommand() {
		WebcamFormat format = new WebcamFormat(1280, 720, 30, "yuvj422p");
		List<String> command = WebcamRecordingService.encoderCommand(
				Path.of("/native/ffmpeg"), Path.of("/tmp/recording-1-webcam.mkv"), format);
		assertEquals("/native/ffmpeg", command.get(0));
		assertTrue(command.contains("-f"));
		assertTrue(command.contains("rawvideo"));
		assertTrue(command.contains("rgba"));
		assertTrue(command.contains("1280x720"));
		assertTrue(command.contains("libx264"));
		assertTrue(!command.contains("h264_vaapi"));
		assertTrue(!command.contains("-hwaccel"));
		assertEquals("/tmp/recording-1-webcam.mkv", command.get(command.size() - 1));
	}
}
