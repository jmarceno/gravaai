package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.media.capture.WebcamFormat;
import dev.gravaai.media.export.VideoEncoderProbe;
import dev.gravaai.media.export.VideoEncoderSpec;
import dev.gravaai.services.recording.ports.PlatformRecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.SpawnedProcess;

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

	/** A process whose stdin is the given stream; it lives until destroyed. */
	private static final class PipeProcess implements SpawnedProcess {

		private final OutputStream stdin;
		private volatile boolean alive = true;

		PipeProcess(OutputStream stdin) {
			this.stdin = stdin;
		}

		@Override
		public OutputStream stdin() {
			return stdin;
		}

		@Override
		public InputStream stdout() {
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream stderr() {
			return InputStream.nullInputStream();
		}

		@Override
		public long pid() {
			return 1;
		}

		@Override
		public boolean isAlive() {
			return alive;
		}

		@Override
		public int waitFor() {
			return 0;
		}

		@Override
		public void destroy() {
			alive = false;
		}

		@Override
		public void destroyForcibly() {
			alive = false;
		}
	}

	@Test
	@DisplayName("a hardware webcam session that breaks on its first frames restarts on libx264 with the frame count kept")
	void hardwareFailureReplaysTheLostFrames(@TempDir Path temp) throws Exception {
		WebcamFormat format = new WebcamFormat(2, 2, 30, "yuvj422p");
		int frameBytes = 2 * 2 * 4;
		ByteArrayOutputStream software = new ByteArrayOutputStream();
		ArrayDeque<OutputStream> stdins = new ArrayDeque<>(List.of(new OutputStream() {
			private int written;

			@Override
			public void write(int value) throws IOException {
				write(new byte[] {(byte) value}, 0, 1);
			}

			@Override
			public void write(byte[] bytes, int offset, int length) throws IOException {
				// The session takes the first frame, then the driver rejects it.
				if (written + length > frameBytes) {
					throw new IOException("Broken pipe");
				}
				written += length;
			}
		}, software));
		List<List<String>> commands = new ArrayList<>();
		RecordingProcesses processes = (command, options) -> {
			commands.add(List.copyOf(command));
			return new PipeProcess(stdins.removeFirst());
		};
		Path output = temp.resolve("recording-1-webcam.mkv");
		WebcamRecordingService.WebcamVideoEncoder encoder = WebcamRecordingService
				.ffmpegCliEncoder(processes, new PlatformRecordingEnvironment(),
						VideoEncoderProbe.of(List.of(VAAPI, VideoEncoderSpec.SOFTWARE)))
				.create(output, format);

		ByteBuffer first = ByteBuffer.allocate(frameBytes);
		java.util.Arrays.fill(first.array(), (byte) 1);
		ByteBuffer second = ByteBuffer.allocate(frameBytes);
		java.util.Arrays.fill(second.array(), (byte) 2);
		encoder.writeFrame(first);
		encoder.writeFrame(second);

		assertEquals(2, commands.size(), commands.toString());
		assertTrue(commands.get(0).contains("h264_vaapi"), commands.toString());
		assertTrue(commands.get(1).contains("libx264"), commands.toString());
		byte[] received = software.toByteArray();
		// The lost first frame is replayed as a copy of the current one, then the current one.
		assertEquals(2 * frameBytes, received.length);
		for (byte value : received) {
			assertEquals(2, value);
		}
		assertEquals(0, second.position(), "the caller's frame buffer must not move");
	}
}
