package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.media.export.VideoEncoderArguments;
import dev.gravaai.media.export.VideoEncoderProbe;
import dev.gravaai.media.export.VideoEncoderSpec;
import dev.gravaai.services.recording.RecordingQuality;
import dev.gravaai.services.recording.WindowSources;
import dev.gravaai.services.recording.cosmic.CosmicRecording.CaptureFormat;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * The capture-to-encoder topology for COSMIC screen video, ported from
 * {@code electron/ipc/recording/cosmic.ts}.
 *
 * <p>The compositor helper writes raw frames to stdout; ffmpeg reads them from a pipe and encodes
 * to a temp Matroska file. The transform the helper reports becomes the transpose filter, so a
 * rotated output is saved upright. The encoder is a hardware one when this machine and the bundled
 * ffmpeg can run it, libx264 otherwise (card "GPU hardware encoding for export and recording").
 *
 * <p>A hardware session the driver rejects fails on the first frame, after the copy already
 * started. The copy thread then moves that recording onto libx264 ({@link RecordingEncoder}),
 * realigned to the next frame boundary of the helper's stream, so the recording keeps going.
 */
final class CosmicVideoPipeline {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	private static final Pattern FORMAT_LINE = Pattern.compile("^FORMAT (\\d+) (\\d+) (\\w+) (\\d+)$");
	/** The compositor's pointer reading, normalized to the recorded frame. */
	private static final Pattern CURSOR_LINE = Pattern.compile("^CURSOR (\\d+(?:\\.\\d+)?) (\\d+(?:\\.\\d+)?)$");
	/** What the stream records: {@code monitor} or {@code window}. */
	private static final Pattern SOURCE_LINE = Pattern.compile("^SOURCE (\\w+)$");
	private static final int COPY_BUFFER_BYTES = 4 * 1024 * 1024;

	private final RecordingProcesses processes;
	private final RecordingEnvironment environment;
	private final CosmicProcessIo processIo;
	private final VideoEncoderProbe encoderProbe;

	CosmicVideoPipeline(
			RecordingProcesses processes,
			RecordingEnvironment environment,
			CosmicProcessIo processIo) {
		this(processes, environment, processIo, VideoEncoderProbe.bundled());
	}

	CosmicVideoPipeline(
			RecordingProcesses processes,
			RecordingEnvironment environment,
			CosmicProcessIo processIo,
			VideoEncoderProbe encoderProbe) {
		this.processes = processes;
		this.environment = environment;
		this.processIo = processIo;
		this.encoderProbe = encoderProbe;
	}

	/** The encoder this pipeline will start: hardware when available, libx264 otherwise. */
	VideoEncoderSpec selectedEncoder() {
		return encoderProbe.select(true);
	}

	/** Starts the capture helper at the chosen frame rate; its FORMAT line decides the encoder's input geometry. */
	SpawnedProcess startCapture(String outputName, int fps) throws IOException {
		return processes.spawn(
				List.of(environment.captureHelper().toString(), outputName, String.valueOf(fps)),
				new SpawnOptions(true, true, true));
	}

	/** Resolves with the helper's FORMAT line, or throws its error; pointer lines are ignored. */
	CaptureFormat readCaptureFormat(SpawnedProcess capture) throws IOException {
		return readCaptureFormat(capture, (cx, cy) -> {
			// Only the caller that records cursor telemetry listens for these.
		});
	}

	/**
	 * Resolves with the helper's FORMAT line, or throws with its error, forwarding every pointer
	 * position the helper reports.
	 *
	 * <p>The helpers print {@code CURSOR x y} for a window capture: the compositor measured the
	 * pointer inside the surface it is capturing, so the value is already relative to the recorded
	 * frame and needs no display geometry. Reporting it is optional, so a helper or backend that
	 * cannot measure the pointer simply never sends a line. The compositor sends the first
	 * position as soon as the stream is up, which can precede the FORMAT line — lines before it
	 * are forwarded too, not treated as error text.
	 *
	 * @param cursor receives each normalized position, on the caller's thread or the drain thread
	 */
	CaptureFormat readCaptureFormat(SpawnedProcess capture, CaptureStreamListener cursor)
			throws IOException {
		InputStream helperErrors = capture.stderr();
		if (helperErrors == null) {
			throw new IOException("The screen capture helper did not expose stderr.");
		}
		var reader = new BufferedReader(
				new InputStreamReader(helperErrors, StandardCharsets.UTF_8));
		StringBuilder stderr = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			Matcher match = FORMAT_LINE.matcher(line);
			if (match.matches()) {
				Thread.ofPlatform().daemon().name("gravaai-capture-log")
						.start(() -> drainLines(reader, cursor));
				return new CaptureFormat(
						Integer.parseInt(match.group(1)),
						Integer.parseInt(match.group(2)),
						match.group(3),
						Integer.parseInt(match.group(4)));
			}
			// Protocol lines are forwarded, never kept as error text: an early exit must report
			// what the helper said went wrong, not the pointer readings that preceded it.
			if (!forwardProtocolLine(line, cursor)) {
				stderr.append(line).append('\n');
			}
		}
		String text = stderr.toString().trim();
		throw new IOException(text.isEmpty() ? "The screen capture helper exited early." : text);
	}

	/**
	 * Hands a {@code CURSOR x y} or {@code SOURCE monitor|window} line to the listener.
	 *
	 * @return true when the line was one of them
	 */
	private static boolean forwardProtocolLine(String line, CaptureStreamListener listener) {
		Matcher pointer = CURSOR_LINE.matcher(line);
		if (pointer.matches()) {
			listener.onPoint(Double.parseDouble(pointer.group(1)),
					Double.parseDouble(pointer.group(2)));
			return true;
		}
		Matcher source = SOURCE_LINE.matcher(line);
		if (source.matches()) {
			WindowSources.CaptureSurface.parse(source.group(1)).ifPresent(listener::onSurface);
			return true;
		}
		return false;
	}

	/** Receives what a capture helper reports about the stream besides its frames. */
	@FunctionalInterface
	interface CaptureStreamListener {

		/**
		 * One pointer position the helper measured inside the recorded surface.
		 *
		 * @param cx normalized x inside the recorded frame (0..1)
		 * @param cy normalized y inside the recorded frame (0..1)
		 */
		void onPoint(double cx, double cy);

		/** What the stream records ({@code SOURCE monitor|window}); reported before FORMAT. */
		default void onSurface(WindowSources.CaptureSurface surface) {
		}
	}

	/**
	 * Starts ffmpeg on the raw frames and on the helper's stdout, at the chosen quality.
	 *
	 * <p>Nothing waits here for the session to prove itself: a hardware encoder opens its session on
	 * the first frame, and holding the copy back would stall the helper and shift the video against
	 * the audio tracks. The copy thread replaces a session that fails early instead.
	 */
	CosmicRecording.VideoCapture startVideo(SpawnedProcess capture, CaptureFormat format,
			Path videoPath, int fps, RecordingQuality quality) throws IOException {
		RecordingEncoder encoder = RecordingEncoder.start(encoderProbe.select(true), spec -> {
			SpawnedProcess process = startEncoder(format, videoPath, fps, quality, spec);
			processIo.drainInto(process.stderr(), "[cosmic-capture] ffmpeg:");
			return process;
		});
		return new CosmicRecording.VideoCapture(capture, encoder, videoPath, frameBytes(format));
	}

	/** Bytes of one tightly packed raw frame the helper writes, or 0 for an unknown layout. */
	static long frameBytes(CaptureFormat format) {
		long bytesPerPixel = switch (format.pixelFormat()) {
			case "rgba", "bgra", "rgb0", "bgr0", "argb", "abgr", "0rgb", "0bgr" -> 4;
			case "rgb24", "bgr24" -> 3;
			default -> 0;
		};
		return bytesPerPixel * Math.max(0, format.width()) * Math.max(0, format.height());
	}

	private SpawnedProcess startEncoder(CaptureFormat format, Path videoPath, int fps,
			RecordingQuality quality, VideoEncoderSpec spec) throws IOException {
		return processes.spawn(
				encoderCommand(format, filtersFor(format, spec), videoPath, fps, quality, spec),
				new SpawnOptions(true, false, true));
	}

	/** Frames go straight through, so the encoder sees exactly the helper's byte stream. */
	void startCopying(CosmicRecording.VideoCapture video) {
		Thread.ofPlatform().daemon().name("gravaai-av-copy").start(() -> copyFrames(video));
	}

	/**
	 * The filter chain: rotate first, then a fixed yuv420p pixel layout, plus the GPU upload when
	 * the encoder only takes GPU surfaces.
	 */
	private static String filtersFor(CaptureFormat format, VideoEncoderSpec spec) {
		List<String> filters = new ArrayList<>();
		String transpose = transposeFilter(format.transform());
		if (transpose != null) {
			filters.add(transpose);
		}
		filters.add("format=yuv420p");
		return String.join(",", VideoEncoderArguments.appendUpload(filters, spec));
	}

	/** Visible for {@code RecordingTimestampContractTest}: the encoder arguments are the contract
	 *  that keeps a recording the same length as the time it took. */
	List<String> encoderCommand(CaptureFormat format, String filters, Path videoPath, int fps, int crf) {
		// The raw factor is what the contract test asserts goes into the command, so it is passed
		// through verbatim instead of being mapped back onto a quality level.
		return encoderCommand(format, filters, videoPath, fps, crf, VideoEncoderSpec.SOFTWARE);
	}

	/**
	 * The full encoder command for one recording at a named quality level.
	 *
	 * <p>The wallclock timestamps and the short cluster limit are not encoder-specific and apply to
	 * every family: they are what keeps the recording the same length as the recording.
	 */
	List<String> encoderCommand(CaptureFormat format, String filters, Path videoPath, int fps,
			RecordingQuality quality, VideoEncoderSpec spec) {
		return encoderCommand(format, filters, videoPath, fps, quality.crf(), spec);
	}

	/** The shared command builder; {@code crf} is the x264 factor or the family's constant-quality QP. */
	private List<String> encoderCommand(CaptureFormat format, String filters, Path videoPath, int fps,
			int crf, VideoEncoderSpec spec) {
		List<String> arguments = new ArrayList<>();
		arguments.add(environment.ffmpeg().toString());
		arguments.addAll(List.of("-hide_banner", "-loglevel", "error", "-y"));
		arguments.addAll(spec.deviceArguments());
		arguments.addAll(List.of(
				"-f", "rawvideo",
				"-pix_fmt", format.pixelFormat(),
				"-s", format.width() + "x" + format.height(),
				// Stamp each frame with its arrival time instead of assuming a fixed rate. The
				// helper writes at the chosen fps, but encoding can fall behind on a busy machine.
				// With a fixed "-r" those late frames were still labelled as if they had arrived on
				// time, so a 15 s capture came out as a ~2.5 s clip that played at several times the
				// real speed. Wall-clock timestamps keep the video the same length as the recording,
				// at a variable frame rate.
				"-use_wallclock_as_timestamps", "1",
				"-i", "pipe:0",
				"-vf", filters,
				"-fps_mode", "vfr",
				"-c:v", spec.codec()));
		arguments.addAll(recordingRateControl(crf, spec));
		arguments.addAll(List.of(
				"-g", String.valueOf(fps * 2),
				// Short clusters: a crash or power loss loses at most ~2 s of video.
				"-cluster_time_limit", "2000",
				videoPath.toString()));
		return List.copyOf(arguments);
	}

	/**
	 * The recording rate control: a constant-quality target at every quality level, so the encoder
	 * keeps up with the capture. The CPU path keeps {@code ultrafast + CRF}; the GPU families take
	 * the same QP through their own constant-quality mode (VA-API has no CRF and no presets).
	 */
	private static List<String> recordingRateControl(int crf, VideoEncoderSpec spec) {
		if (!spec.hardware()) {
			return List.of("-preset", "ultrafast", "-crf", String.valueOf(crf));
		}
		return VideoEncoderArguments.rateControl(spec,
				dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode.FAST,
				0, crf, true);
	}

	/**
	 * ffmpeg's transform filter for the helper's reported transform.
	 *
	 * @param transform the rotation/flip code from the helper's FORMAT line
	 * @return the filter chain, or null when the frames need no rotation
	 */
	static @Nullable String transposeFilter(int transform) {
		String flip = transform >= 4 ? "hflip," : "";
		return switch (transform % 4) {
			case 1 -> flip + "transpose=1";
			case 2 -> flip + "hflip,vflip";
			case 3 -> flip + "transpose=2";
			default -> flip.isEmpty() ? null : "hflip";
		};
	}

	/**
	 * Copies the helper's frames into the encoder until the helper ends.
	 *
	 * <p>When a write fails while the encoder may still fall back, the copy moves onto the libx264
	 * replacement and drops bytes up to the next frame boundary of the helper's stream (the failed
	 * process may have taken half a frame), so the replacement starts on a whole frame.
	 */
	static void copyFrames(CosmicRecording.VideoCapture video) {
		InputStream captureStdout = video.capture().stdout();
		RecordingEncoder encoder = video.encoder();
		OutputStream encoderStdin = encoder.stdin();
		if (captureStdout == null || encoderStdin == null) {
			LOG.log(System.Logger.Level.WARNING, "Frame copy has no pipe to work with");
			return;
		}
		long frameBytes = video.frameBytes();
		ReadableByteChannel input = Channels.newChannel(captureStdout);
		WritableByteChannel output = Channels.newChannel(encoderStdin);
		try {
			ByteBuffer buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES);
			// Offset of buffer[0] in the helper's stream, and bytes still to drop before a frame
			// boundary after a swap.
			long bufferOffset = 0;
			long skip = 0;
			while (input.read(buffer) != -1) {
				buffer.flip();
				while (buffer.hasRemaining()) {
					if (skip > 0) {
						int dropped = (int) Math.min(skip, buffer.remaining());
						buffer.position(buffer.position() + dropped);
						skip -= dropped;
						continue;
					}
					try {
						output.write(buffer);
					} catch (IOException writeFailure) {
						if (frameBytes <= 0 || !encoder.canFallBack()) {
							throw writeFailure;
						}
						LOG.log(System.Logger.Level.WARNING, "The " + encoder.spec().codec()
								+ " recording encoder failed (" + writeFailure.getMessage()
								+ "); moving this recording to libx264");
						closeQuietly(output);
						output = Channels.newChannel(encoder.fallBack());
						long position = bufferOffset + buffer.position();
						skip = (frameBytes - position % frameBytes) % frameBytes;
					}
				}
				bufferOffset += buffer.limit();
				buffer.clear();
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Frame copy ended: " + error);
		} finally {
			closeQuietly(input);
			closeQuietly(output);
		}
	}

	private static void closeQuietly(java.nio.channels.Channel channel) {
		try {
			channel.close();
		} catch (IOException error) {
			// Already closed: the process on the other end exited.
		}
	}

	private static void drainLines(BufferedReader reader, CaptureStreamListener cursor) {
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				if (forwardProtocolLine(line, cursor)) {
					continue;
				}
				LOG.log(System.Logger.Level.WARNING, "[cosmic-capture] helper: " + line.trim());
			}
		} catch (IOException error) {
			// The helper exited; nothing to drain.
		}
	}
}
