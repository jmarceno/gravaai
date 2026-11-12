package dev.gravaai.services.recording.cosmic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import dev.gravaai.media.export.VideoEncoderProbe;
import dev.gravaai.media.export.VideoEncoderSpec;
import dev.gravaai.services.recording.RecordingQuality;
import dev.gravaai.services.recording.cosmic.CosmicRecording.CaptureFormat;
import dev.gravaai.services.recording.ports.PlatformRecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * The screen-recording encoder selection (card "GPU hardware encoding for export and recording").
 * No GPU device is opened and no ffmpeg process is started: the fake processes answer with pipes
 * the pipeline only starts to drain.
 */
class CosmicEncoderSelectionTest {

	@TempDir
	Path temp;

	private static final VideoEncoderSpec VAAPI =
			new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);
	private static final VideoEncoderProbe HARDWARE_PROBE =
			VideoEncoderProbe.of(List.of(VAAPI, VideoEncoderSpec.SOFTWARE));

	private static final class FakeProcess implements SpawnedProcess {

		private final CountDownLatch exited = new CountDownLatch(1);
		private final InputStream stdout;
		private final OutputStream stdin;

		FakeProcess(boolean diesImmediately) {
			this(InputStream.nullInputStream(), OutputStream.nullOutputStream());
			if (diesImmediately) {
				exited.countDown();
			}
		}

		FakeProcess(InputStream stdout, OutputStream stdin) {
			this.stdout = stdout;
			this.stdin = stdin;
		}

		@Override
		public OutputStream stdin() {
			return stdin;
		}

		@Override
		public InputStream stdout() {
			return stdout;
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
			return exited.getCount() > 0;
		}

		@Override
		public int waitFor() {
			return 0;
		}

		@Override
		public void destroy() {
			exited.countDown();
		}

		@Override
		public void destroyForcibly() {
			exited.countDown();
		}
	}

	/** Records the spawned commands and lets the test decide which ones die at once. */
	private static final class Commands implements RecordingProcesses {

		final List<List<String>> commands = new ArrayList<>();
		final AtomicInteger encoderStarts = new AtomicInteger();
		/** Stdins handed to successive encoder starts; a null-output stdin once it runs out. */
		final java.util.ArrayDeque<OutputStream> encoderStdins = new java.util.ArrayDeque<>();
		final boolean hardwareEncoderDiesImmediately;

		Commands(boolean hardwareEncoderDiesImmediately) {
			this.hardwareEncoderDiesImmediately = hardwareEncoderDiesImmediately;
		}

		@Nullable SpawnOptions lastOptions;

		@Override
		public SpawnedProcess spawn(List<String> command, SpawnOptions options) {
			lastOptions = options;
			commands.add(List.copyOf(command));
			if (command.getFirst().endsWith("ffmpeg")) {
				int start = encoderStarts.incrementAndGet();
				// The first encoder start is the hardware attempt when it is meant to die.
				boolean dies = hardwareEncoderDiesImmediately && start == 1
						&& command.contains("h264_vaapi");
				OutputStream stdin = encoderStdins.pollFirst();
				return stdin == null ? new FakeProcess(dies)
						: new FakeProcess(InputStream.nullInputStream(), stdin);
			}
			return new FakeProcess(false);
		}
	}

	private CosmicVideoPipeline pipeline(RecordingProcesses processes, VideoEncoderProbe probe) {
		return new CosmicVideoPipeline(processes, new PlatformRecordingEnvironment(),
				new CosmicProcessIo(processes), probe);
	}

	@Test
	@DisplayName("the software command keeps the wallclock/vfr contract and the x264 preset")
	void softwareCommandShape() {
		List<String> args = pipeline(new Commands(false), VideoEncoderProbe.of(List.of(VideoEncoderSpec.SOFTWARE)))
				.encoderCommand(new CaptureFormat(1920, 1080, "bgra", 0), "format=yuv420p",
						Path.of("/tmp/out.mkv"), 60, RecordingQuality.LOSSLESS, VideoEncoderSpec.SOFTWARE);
		assertTrue(args.contains("libx264"), args.toString());
		assertTrue(args.contains("-use_wallclock_as_timestamps"), args.toString());
		assertEquals("vfr", args.get(args.indexOf("-fps_mode") + 1), args.toString());
		assertEquals("1", args.get(args.indexOf("-crf") + 1), args.toString());
		assertEquals("120", args.get(args.indexOf("-g") + 1), args.toString());
		assertEquals("2000", args.get(args.indexOf("-cluster_time_limit") + 1), args.toString());
	}

	@Test
	@DisplayName("the hardware command uploads the frames and keeps the recording contract")
	void hardwareCommandShape() {
		VideoEncoderProbe probe = HARDWARE_PROBE;
		CosmicVideoPipeline pipeline = pipeline(new Commands(false), probe);
		String filters = "format=yuv420p,format=nv12,hwupload";
		List<String> args = pipeline.encoderCommand(new CaptureFormat(1920, 1080, "bgra", 0), filters,
				Path.of("/tmp/out.mkv"), 60, RecordingQuality.HIGH, VAAPI);
		assertTrue(args.contains("h264_vaapi"), args.toString());
		assertEquals(filters, args.get(args.indexOf("-vf") + 1), args.toString());
		assertTrue(args.contains("-use_wallclock_as_timestamps"), args.toString());
		assertEquals("vfr", args.get(args.indexOf("-fps_mode") + 1), args.toString());
		assertEquals("2000", args.get(args.indexOf("-cluster_time_limit") + 1), args.toString());
		assertFalse(args.contains("-preset"), args.toString());
		assertFalse(args.contains("-crf"), args.toString());
		assertEquals("CQP", args.get(args.indexOf("-rc_mode") + 1), args.toString());
		assertEquals("18", args.get(args.indexOf("-qp") + 1), args.toString());
		assertEquals("8", args.get(args.indexOf("-quality") + 1), args.toString());
	}

	@Test
	@DisplayName("the hardware encoder is preferred when the probe offers one")
	void hardwareIsPreferred() {
		Commands processes = new Commands(false);
		CosmicVideoPipeline pipeline = pipeline(processes, HARDWARE_PROBE);
		assertEquals("h264_vaapi", pipeline.selectedEncoder().codec());
	}

	/** Five frames of a 4x2 BGRA capture, each frame filled with its own index. */
	private static final CaptureFormat TINY = new CaptureFormat(4, 2, "bgra", 0);
	private static final int TINY_FRAME = 4 * 2 * 4;
	private static final int TINY_FRAMES = 5;

	private static byte[] tinyFrames() {
		byte[] stream = new byte[TINY_FRAME * TINY_FRAMES];
		for (int index = 0; index < stream.length; index++) {
			stream[index] = (byte) (index / TINY_FRAME);
		}
		return stream;
	}

	/** Hands the helper's stream out in small reads, like a pipe that is drained as it fills. */
	private static InputStream trickle(byte[] bytes, int chunk) {
		return new InputStream() {
			private int position;

			@Override
			public int read() {
				return position < bytes.length ? bytes[position++] & 0xFF : -1;
			}

			@Override
			public int read(byte[] target, int offset, int length) {
				if (position >= bytes.length) {
					return -1;
				}
				int count = Math.min(Math.min(length, chunk), bytes.length - position);
				System.arraycopy(bytes, position, target, offset, count);
				position += count;
				return count;
			}
		};
	}

	/** An encoder stdin that dies after {@code limit} bytes, like a session rejected mid-frame. */
	private static OutputStream diesAfter(int limit) {
		return new OutputStream() {
			private int written;

			@Override
			public void write(int value) throws IOException {
				write(new byte[] {(byte) value}, 0, 1);
			}

			@Override
			public void write(byte[] bytes, int offset, int length) throws IOException {
				if (written + length > limit) {
					throw new IOException("Broken pipe");
				}
				written += length;
			}
		};
	}

	@Test
	@DisplayName("the encoder starts without waiting, so the copy never stalls the helper")
	void startDoesNotWait() throws Exception {
		Commands processes = new Commands(false);
		CosmicVideoPipeline pipeline = pipeline(processes, HARDWARE_PROBE);
		CosmicRecording.VideoCapture video = pipeline.startVideo(new FakeProcess(false), TINY,
				temp.resolve("out.mkv"), 60, RecordingQuality.HIGH);
		assertEquals("h264_vaapi", video.encoderSpec().codec());
		assertEquals(1, processes.encoderStarts.get(), processes.commands.toString());
		assertEquals(TINY_FRAME, video.frameBytes());
	}

	@Test
	@DisplayName("a hardware session that fails on its first frames moves to libx264 on a frame boundary")
	void earlyHardwareFailureFallsBackAligned() throws Exception {
		Commands processes = new Commands(false);
		ByteArrayOutputStream software = new ByteArrayOutputStream();
		// The hardware encoder takes 40 bytes (one frame and a quarter) and then dies.
		processes.encoderStdins.add(diesAfter(40));
		processes.encoderStdins.add(software);
		CosmicVideoPipeline pipeline = pipeline(processes, HARDWARE_PROBE);
		FakeProcess capture = new FakeProcess(trickle(tinyFrames(), 24), OutputStream.nullOutputStream());
		CosmicRecording.VideoCapture video = pipeline.startVideo(capture, TINY,
				temp.resolve("out.mkv"), 60, RecordingQuality.HIGH);

		CosmicVideoPipeline.copyFrames(video);

		assertEquals("libx264", video.encoderSpec().codec());
		assertTrue(video.encoder().restartedAtMs().isPresent());
		assertEquals(2, processes.encoderStarts.get(), processes.commands.toString());
		assertEquals("libx264", processes.commands.getLast().get(
				processes.commands.getLast().indexOf("-c:v") + 1), processes.commands.toString());
		byte[] received = software.toByteArray();
		assertEquals(0, received.length % TINY_FRAME, "the replacement must get whole frames");
		assertEquals(3 * TINY_FRAME, received.length);
		// The replacement starts on frame 2: frame 1 was half-consumed by the failed session.
		assertEquals(2, received[0]);
		assertEquals(TINY_FRAMES - 1, received[received.length - 1]);
	}

	@Test
	@DisplayName("a libx264 failure is not retried: the copy just ends")
	void softwareFailureIsNotRetried() throws Exception {
		Commands processes = new Commands(false);
		processes.encoderStdins.add(diesAfter(40));
		CosmicVideoPipeline pipeline = pipeline(processes,
				VideoEncoderProbe.of(List.of(VideoEncoderSpec.SOFTWARE)));
		FakeProcess capture = new FakeProcess(trickle(tinyFrames(), 24), OutputStream.nullOutputStream());
		CosmicRecording.VideoCapture video = pipeline.startVideo(capture, TINY,
				temp.resolve("out.mkv"), 60, RecordingQuality.HIGH);

		CosmicVideoPipeline.copyFrames(video);

		assertEquals("libx264", video.encoderSpec().codec());
		assertTrue(video.encoder().restartedAtMs().isEmpty());
		assertEquals(1, processes.encoderStarts.get(), processes.commands.toString());
	}

	@Test
	@DisplayName("a healthy hardware encoder receives the whole stream")
	void healthyHardwareIsKept() throws Exception {
		Commands processes = new Commands(false);
		ByteArrayOutputStream hardware = new ByteArrayOutputStream();
		processes.encoderStdins.add(hardware);
		CosmicVideoPipeline pipeline = pipeline(processes, HARDWARE_PROBE);
		FakeProcess capture = new FakeProcess(trickle(tinyFrames(), 24), OutputStream.nullOutputStream());
		CosmicRecording.VideoCapture video = pipeline.startVideo(capture, TINY,
				temp.resolve("out.mkv"), 60, RecordingQuality.HIGH);

		CosmicVideoPipeline.copyFrames(video);

		assertEquals("h264_vaapi", video.encoderSpec().codec());
		assertEquals(1, processes.encoderStarts.get(), processes.commands.toString());
		assertEquals(TINY_FRAME * TINY_FRAMES, hardware.size());
	}

	@Test
	@DisplayName("the raw frame size follows the helper's pixel format")
	void frameBytesPerFormat() {
		assertEquals(1920L * 1080 * 4,
				CosmicVideoPipeline.frameBytes(new CaptureFormat(1920, 1080, "rgba", 0)));
		assertEquals(0, CosmicVideoPipeline.frameBytes(new CaptureFormat(1920, 1080, "nv12", 0)));
	}

	@Test
	@DisplayName("the upload filter is appended after the rotation and the yuv420p layout")
	void filtersKeepTheUploadTail() {
		String rotated = CosmicVideoPipeline.transposeFilter(1);
		assertEquals("transpose=1", rotated);
		// The real chain is built internally; assert the pieces the upload tail relies on.
		List<String> uploaded = dev.gravaai.media.export.VideoEncoderArguments
				.appendUpload(List.of("format=yuv420p"), VAAPI);
		assertEquals(List.of("format=yuv420p", "format=nv12", "hwupload"), uploaded);
		List<String> hostFrames = dev.gravaai.media.export.VideoEncoderArguments
				.appendUpload(List.of("format=yuv420p"), VideoEncoderSpec.SOFTWARE);
		assertEquals(List.of("format=yuv420p"), hostFrames);
	}
}
