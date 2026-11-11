package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.capture.WebcamFormat;
import dev.gravaai.media.export.VideoEncoderSpec;

/** Tests the webcam file naming/encoder-command helpers; capture itself is a device/manual gate. */
class WebcamRecordingServiceTest {

	private static final VideoEncoderSpec VAAPI =
			new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);

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
		assertEquals("-preset", command.get(command.indexOf("-preset")));
		assertEquals("ultrafast", command.get(command.indexOf("-preset") + 1));
		assertEquals("18", command.get(command.indexOf("-crf") + 1));
		assertEquals("/tmp/recording-1-webcam.mkv", command.get(command.size() - 1));
	}

	@Test
	@DisplayName("a hardware webcam encoder uploads the RGB frames and drops the x264 options")
	void buildsTheHardwareEncoderCommand() {
		WebcamFormat format = new WebcamFormat(1280, 720, 30, "yuvj422p");
		List<String> command = WebcamRecordingService.encoderCommand(
				Path.of("/native/ffmpeg"), Path.of("/tmp/recording-1-webcam.mkv"), format, VAAPI);
		assertTrue(command.contains("h264_vaapi"), command.toString());
		assertEquals("format=nv12,hwupload", command.get(command.indexOf("-vf") + 1), command.toString());
		assertTrue(command.contains("-init_hw_device"), command.toString());
		assertFalse(command.contains("-preset"), "VA-API has no x264 presets: " + command);
		assertFalse(command.contains("-crf"), "VA-API has no CRF: " + command);
		assertEquals("CQP", command.get(command.indexOf("-rc_mode") + 1), command.toString());
		assertFalse(command.contains("-hwaccel"), "no hardware decode path: " + command);
	}
}
