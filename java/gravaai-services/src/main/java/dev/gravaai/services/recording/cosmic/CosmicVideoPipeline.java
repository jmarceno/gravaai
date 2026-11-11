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
 * <p>A hardware encoder that dies during the first second never had a usable frame: the capture
 * helper keeps producing, so that recording restarts its encoder on libx264 and loses nothing but
 * the frames that never encoded.
 */
final class CosmicVideoPipeline {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	private static final Pattern FORMAT_LINE = Pattern.compile("^FORMAT (\\d+) (\\d+) (\\w+) (\\d+)$");
	private static final int COPY_BUFFER_BYTES = 4 * 1024 * 1024;

	/**
	 * How long to watch a fresh hardware encoder for an immediate exit. Driver/encoder init failures
	 * happen within milliseconds, so this is a short poll rather than a wait: a healthy encoder adds
	 * no noticeable delay to the recording start.
	 */
	private static final long EARLY_EXIT_PROBE_MS = 400;

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

	/** Resolves with the helper's FORMAT line, or throws with its error. */
	CaptureFormat readCaptureFormat(SpawnedProcess capture) throws IOException {
		InputStream helperErrors = capture.stderr();
		if (helperErrors == null) {
			throw new IOException("The screen capture helper did not expose stderr.");
		}
		var reader = new BufferedReader(
				new InputStreamReader(helperErrors, StandardCharsets.UTF_8));
		StringBuilder stderr = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			stderr.append(line).append('\n');
			Matcher match = FORMAT_LINE.matcher(line);
			if (match.matches()) {
				Thread.ofPlatform().daemon().name("gravaai-capture-log").start(() -> drainLines(reader));
				return new CaptureFormat(
						Integer.parseInt(match.group(1)),
						Integer.parseInt(match.group(2)),
						match.group(3),
						Integer.parseInt(match.group(4)));
			}
		}
		String text = stderr.toString().trim();
		throw new IOException(text.isEmpty() ? "The screen capture helper exited early." : text);
	}

	/**
	 * Starts ffmpeg on the raw frames and on the helper's stdout, at the chosen quality.
	 *
	 * <p>The encoder that actually runs is carried on the returned capture, which is what the HUD
	 * and the logs report.
	 */
	CosmicRecording.VideoCapture startVideo(SpawnedProcess capture, CaptureFormat format,
			Path videoPath, int fps, RecordingQuality quality) throws IOException {
		VideoEncoderSpec spec = encoderProbe.select(true);
		SpawnedProcess encoder = startEncoder(format, videoPath, fps, quality, spec);
		if (spec.hardware() && failsImmediately(encoder)) {
			LOG.log(System.Logger.Level.WARNING, "The " + spec.codec() + " recording encoder exited"
					+ " immediately; restarting this recording on libx264");
			encoder.destroy();
			spec = VideoEncoderSpec.SOFTWARE;
			encoder = startEncoder(format, videoPath, fps, quality, spec);
		}
		return new CosmicRecording.VideoCapture(capture, encoder, videoPath, spec);
	}

	/** True when a freshly spawned encoder already exited (it never reached the first frame). */
	private static boolean failsImmediately(SpawnedProcess encoder) {
		long deadline = System.nanoTime() + EARLY_EXIT_PROBE_MS * 1_000_000L;
		while (System.nanoTime() < deadline) {
			if (!encoder.isAlive()) {
				return true;
			}
			try {
				Thread.sleep(20);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	private SpawnedProcess startEncoder(CaptureFormat format, Path videoPath, int fps,
			RecordingQuality quality, VideoEncoderSpec spec) throws IOException {
		return processes.spawn(
				encoderCommand(format, filtersFor(format, spec), videoPath, fps, quality, spec),
				new SpawnOptions(true, false, true));
	}

	/** The encoder's stderr and the helper's remaining stderr are logged in the background. */
	void drainPipeline(SpawnedProcess capture, SpawnedProcess encoder) {
		processIo.drainInto(encoder.stderr(), "[cosmic-capture] ffmpeg:");
		processIo.drainInto(capture.stderr(), "[cosmic-capture] helper:");
	}

	/** Frames go straight through, so the encoder sees exactly the helper's byte stream. */
	void startCopying(SpawnedProcess capture, SpawnedProcess encoder) {
		Thread.ofPlatform().daemon().name("gravaai-av-copy").start(() -> copyFrames(capture, encoder));
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

	/** FFmpeg filter that lines a raw track up with the recording's reference start. */
	private static void copyFrames(SpawnedProcess capture, SpawnedProcess encoder) {
		InputStream captureStdout = capture.stdout();
		OutputStream encoderStdin = encoder.stdin();
		if (captureStdout == null || encoderStdin == null) {
			LOG.log(System.Logger.Level.WARNING, "Frame copy has no pipe to work with");
			return;
		}
		try (ReadableByteChannel input = Channels.newChannel(captureStdout);
				WritableByteChannel output = Channels.newChannel(encoderStdin)) {
			ByteBuffer buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES);
			while (input.read(buffer) != -1) {
				buffer.flip();
				while (buffer.hasRemaining()) {
					output.write(buffer);
				}
				buffer.clear();
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Frame copy ended: " + error);
		}
	}

	private static void drainLines(BufferedReader reader) {
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				LOG.log(System.Logger.Level.WARNING, "[cosmic-capture] helper: " + line.trim());
			}
		} catch (IOException error) {
			// The helper exited; nothing to drain.
		}
	}
}
