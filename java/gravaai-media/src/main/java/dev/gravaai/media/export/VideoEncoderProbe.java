package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import dev.gravaai.platform.process.ProcessResult;
import dev.gravaai.platform.process.ProcessRunner;

/**
 * Which H.264 encoders the bundled ffmpeg can run, and which one a job should use
 * (card "GPU hardware encoding for export and recording").
 *
 * <p>The answer comes from one {@code ffmpeg -encoders} run per JVM plus the device prerequisites
 * the vendor encoders need (VA-API has to see a render node). Presence proves the encoder was
 * compiled in, not that the card can run it — a driver that rejects the session only shows up when
 * the job starts, which is exactly what the per-job fallback in {@link Mp4Encoder} and the
 * recording pipeline handle.
 *
 * <p>The probe is an interface so tests can hand the pipeline a fixed answer and never open a GPU
 * device.
 */
public interface VideoEncoderProbe {

	/** The encoders this ffmpeg build can start, best first. Never empty: libx264 is the floor. */
	List<VideoEncoderSpec> available();

	/** The encoder jobs should start with. */
	default VideoEncoderSpec preferred() {
		return available().getFirst();
	}

	/**
	 * The encoder to run this job with.
	 *
	 * @param hardwareAllowed false when the caller must stay on the CPU (a user override, a test)
	 */
	default VideoEncoderSpec select(boolean hardwareAllowed) {
		if (!hardwareAllowed) {
			return VideoEncoderSpec.SOFTWARE;
		}
		return preferred();
	}

	/**
	 * Export resolution from which a hardware encoder is used.
	 *
	 * <p>Measured on the weakest supported GPU (Radeon RX 560, Polaris/VCE 3, kernel 7.2.15) with
	 * 1080p/1440p/4K {@code testsrc2}: VA-API never beats libx264 in wall-clock, but it decodes and
	 * encodes at ~40% CPU instead of 600-760%, and the wall-clock gap shrinks as the frame grows
	 * (1.66x slower at 720p, 1.49x at 1080p, 1.21x at 1440p, 1.32x at 4K). Below 4K the export is
	 * faster on the CPU and the machine is not saturated anyway; at 4K the export is long enough
	 * that leaving the compositor and the rest of the app 13 cores is worth the smaller gap.
	 */
	long EXPORT_HARDWARE_MIN_PIXELS = 3840L * 2160L;

	/**
	 * The encoder to run an export with at the given output size.
	 *
	 * <p>Recording always prefers hardware ({@link #select(boolean)}): it has to keep up with the
	 * capture, not run as fast as possible, and the freed CPU is what keeps compositing smooth.
	 *
	 * @param width the export width in pixels
	 * @param height the export height in pixels
	 */
	default VideoEncoderSpec selectForExport(int width, int height) {
		VideoEncoderSpec preferred = preferred();
		if (!preferred.hardware()) {
			return preferred;
		}
		long pixels = (long) Math.max(1, width) * Math.max(1, height);
		return pixels >= EXPORT_HARDWARE_MIN_PIXELS ? preferred : VideoEncoderSpec.SOFTWARE;
	}

	/** Probes the bundled CLI once per JVM and caches the answer. */
	static VideoEncoderProbe bundled() {
		return CachedHolder.PROBE;
	}

	/** A probe backed by a fixed list, for tests and for callers that already know the answer. */
	static VideoEncoderProbe of(List<VideoEncoderSpec> specs) {
		if (specs == null || specs.isEmpty()) {
			throw new IllegalArgumentException("a probe needs at least one encoder");
		}
		List<VideoEncoderSpec> copy = List.copyOf(specs);
		return () -> copy;
	}

	/** The real probe: {@code ffmpeg -encoders} parsed once, then device prerequisites. */
	final class FfmpegProbe implements VideoEncoderProbe {

		/** The {@code -encoders} probe is a short read-only query; it must never stall a job start. */
		private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

		private final Path ffmpeg;
		private volatile @Nullable List<VideoEncoderSpec> cached;

		FfmpegProbe(Path ffmpeg) {
			this.ffmpeg = ffmpeg;
		}

		@Override
		public List<VideoEncoderSpec> available() {
			List<VideoEncoderSpec> specs = cached;
			if (specs == null) {
				specs = probe();
				cached = specs;
			}
			return specs;
		}

		private List<VideoEncoderSpec> probe() {
			Set<String> encoders = listedEncoders();
			List<VideoEncoderSpec> specs = new ArrayList<>();
			// Probe order: VA-API, NVENC, QSV, AMF, Vulkan video last (immature), then the CPU.
			for (VideoEncoderSpec.Family family : List.of(
					VideoEncoderSpec.Family.VAAPI,
					VideoEncoderSpec.Family.NVENC,
					VideoEncoderSpec.Family.QSV,
					VideoEncoderSpec.Family.AMF,
					VideoEncoderSpec.Family.VULKAN)) {
				if (!encoders.contains(family.codec())) {
					continue;
				}
				if (family == VideoEncoderSpec.Family.VAAPI && !VaapiDevices.present()) {
					continue;
				}
				specs.add(new VideoEncoderSpec(family.codec(), family));
			}
			specs.add(VideoEncoderSpec.SOFTWARE);
			return List.copyOf(specs);
		}

		/** The encoder names in {@code ffmpeg -encoders}; empty when the CLI cannot be run. */
		private Set<String> listedEncoders() {
			ProcessResult result;
			try {
				result = ProcessRunner.run(
						List.of(ffmpeg.toString(), "-hide_banner", "-encoders"), PROBE_TIMEOUT);
			} catch (IOException | InterruptedException error) {
				if (error instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				return Set.of();
			}
			if (!result.succeeded()) {
				return Set.of();
			}
			return parseEncoders(result.stdout());
		}
	}

	/**
	 * Parses {@code ffmpeg -encoders} output into the set of encoder names.
	 *
	 * <p>Lines look like {@code " V....D h264_vaapi           H.264/AVC (VAAPI)"}: six capability
	 * flags, the name, then the description. Only video encoders ({@code V}) are considered.
	 */
	static Set<String> parseEncoders(String output) {
		Set<String> names = new java.util.HashSet<>();
		for (String line : output.split("\n")) {
			String trimmed = line.strip();
			if (trimmed.length() < 8 || trimmed.charAt(0) != 'V') {
				continue;
			}
			String[] parts = trimmed.split("\\s+");
			if (parts.length < 2) {
				continue;
			}
			String name = parts[1].toLowerCase(Locale.ROOT);
			if (!name.isEmpty() && !name.startsWith("=")) {
				names.add(name);
			}
		}
		return Set.copyOf(names);
	}

	/** One process-wide probe: {@code ffmpeg -encoders} is not worth running per export. */
	final class CachedHolder {

		private static final VideoEncoderProbe PROBE = new FfmpegProbe(FfmpegCli.binary());

		private CachedHolder() {}
	}

	/** The names of the encoders in {@code specs}, for logging and doctor output. */
	static String describe(List<VideoEncoderSpec> specs) {
		return specs.stream().map(VideoEncoderSpec::codec).collect(Collectors.joining(", "));
	}
}
