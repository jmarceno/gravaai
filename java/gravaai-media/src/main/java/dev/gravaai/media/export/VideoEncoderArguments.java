package dev.gravaai.media.export;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;

/**
 * The H.264 arguments one {@link VideoEncoderSpec} takes (card "GPU hardware encoding for export
 * and recording").
 *
 * <p>Everything that is not vendor-specific lives with the caller (input geometry, audio, muxing,
 * {@code +faststart}); this class answers only what differs between encoders: which filter chain
 * brings the frames into a layout the encoder accepts, which rate-control flags apply, and which
 * speed knob the family has. x264 keeps {@code -crf} and {@code -preset}/{@code -tune}; the vendors
 * get a bitrate target (or an explicit QP where a caller asks for one) and their own knob.
 */
public final class VideoEncoderArguments {

	private VideoEncoderArguments() {}

	/** Encoders that take GPU surfaces need the frames uploaded; the others take host frames. */
	public static boolean needsUpload(VideoEncoderSpec spec) {
		return spec.family().uploadsFrames();
	}

	/**
	 * The filter chain that precedes the encoder, with the GPU upload appended where needed.
	 *
	 * <p>VA-API and Vulkan video only accept GPU surfaces, so their chain ends in
	 * {@code format=nv12,hwupload}; x264, NVENC, QSV and AMF take host {@code yuv420p}, which the
	 * compositor already produces. The caller owns any colour conversion, so this only appends the
	 * upload tail.
	 *
	 * @param current filters the caller already needs (colour conversion, rotation, scaling, …)
	 * @param spec the encoder about to run
	 */
	public static List<String> appendUpload(List<String> current, VideoEncoderSpec spec) {
		if (!needsUpload(spec)) {
			return List.copyOf(current);
		}
		List<String> filters = new ArrayList<>(current);
		// The upload filter takes host NV12; `format` converts whatever the chain produced.
		filters.add("format=nv12");
		filters.add("hwupload");
		return List.copyOf(filters);
	}

	/**
	 * The rate-control and speed arguments for {@code spec}.
	 *
	 * @param spec the encoder about to run
	 * @param mode FAST/BALANCED/QUALITY, mapped onto each family's own knob
	 * @param bitrate the export bitrate target (the quality-based paths ignore it)
	 * @param quality the quality value: x264's CRF, or the QP for the constant-quality paths
	 * @param constantQuality true to pin quality instead of aiming at {@code bitrate}
	 */
	public static List<String> rateControl(VideoEncoderSpec spec, ExportEncodingMode mode,
			int bitrate, int quality, boolean constantQuality) {
		ExportEncodingMode effective = mode == null ? ExportEncodingMode.BALANCED : mode;
		return switch (spec.family()) {
			case X264 -> x264(effective, quality);
			case VAAPI -> vaapi(effective, bitrate, quality, constantQuality);
			case NVENC -> nvenc(effective, bitrate, quality, constantQuality);
			case QSV -> qsv(effective, bitrate, quality, constantQuality);
			case AMF -> amf(effective, bitrate, quality, constantQuality);
			case VULKAN -> vulkan(effective, bitrate, quality, constantQuality);
		};
	}

	/** x264: the existing CRF + preset mapping, unchanged so exports stay bit-comparable. */
	static List<String> x264(ExportEncodingMode mode, int crf) {
		List<String> args = new ArrayList<>(Mp4Encoder.libx264ModeArguments(mode));
		args.addAll(List.of("-crf", String.valueOf(clampQp(crf))));
		return List.copyOf(args);
	}

	/**
	 * VA-API: a bitrate target in VBR mode, or explicit QP through {@code -rc_mode CQP}.
	 *
	 * <p>This is the oldest path in the family and the reason the mapping stays conservative: the
	 * RX 560 driver exposes only CQP/CBR/VBR (driver rejects QVBR/ICQ), rejects {@code -preset}
	 * outright, and derives the mode from whichever of bitrate/QP is present. Stating
	 * {@code -rc_mode} explicitly keeps the behaviour identical on drivers that matured later.
	 * The mode names are the C constants verbatim ({@code CQP}, {@code VBR}) and ffmpeg matches
	 * them case-sensitively. {@code -quality} is the vendor speed/quality knob (0 best, 32 fastest).
	 */
	static List<String> vaapi(ExportEncodingMode mode, int bitrate, int quality, boolean constantQuality) {
		List<String> args = new ArrayList<>();
		if (constantQuality) {
			args.addAll(List.of("-rc_mode", "CQP", "-qp", String.valueOf(clampQp(quality))));
		} else {
			args.addAll(List.of("-rc_mode", "VBR"));
			args.addAll(bitrateArguments(bitrate));
		}
		args.addAll(List.of("-quality", String.valueOf(vaapiQuality(mode))));
		return List.copyOf(args);
	}

	/** NVENC: VBR around the target, or constqp, with the modern P-presets. */
	static List<String> nvenc(ExportEncodingMode mode, int bitrate, int quality, boolean constantQuality) {
		List<String> args = new ArrayList<>();
		if (constantQuality) {
			args.addAll(List.of("-rc", "constqp", "-qp", String.valueOf(clampQp(quality))));
		} else {
			args.addAll(List.of("-rc", "vbr"));
			args.addAll(bitrateArguments(bitrate));
		}
		args.addAll(switch (mode) {
			case FAST -> List.of("-preset", "p1", "-tune", "ll");
			case QUALITY -> List.of("-preset", "p7", "-tune", "hq");
			case BALANCED -> List.of("-preset", "p4", "-tune", "hq");
		});
		return List.copyOf(args);
	}

	/** QSV: VBR around the target, or ICQ when a fixed quality is wanted. */
	static List<String> qsv(ExportEncodingMode mode, int bitrate, int quality, boolean constantQuality) {
		List<String> args = new ArrayList<>();
		if (constantQuality) {
			args.addAll(List.of("-global_quality", String.valueOf(clampQp(quality))));
		} else {
			args.addAll(bitrateArguments(bitrate));
		}
		args.addAll(switch (mode) {
			case FAST -> List.of("-preset", "veryfast");
			case QUALITY -> List.of("-preset", "veryslow");
			case BALANCED -> List.of("-preset", "medium");
		});
		return List.copyOf(args);
	}

	/** AMF: VBR around the target (or constant QP), with AMF's speed/quality preset. */
	static List<String> amf(ExportEncodingMode mode, int bitrate, int quality, boolean constantQuality) {
		List<String> args = new ArrayList<>();
		if (constantQuality) {
			args.addAll(List.of("-rc", "cqp",
					"-qp_i", String.valueOf(clampQp(quality)),
					"-qp_p", String.valueOf(clampQp(quality))));
		} else {
			args.addAll(List.of("-rc", "vbr_peak"));
			args.addAll(bitrateArguments(bitrate));
		}
		args.addAll(switch (mode) {
			case FAST -> List.of("-preset", "speed");
			case QUALITY -> List.of("-preset", "quality");
			case BALANCED -> List.of("-preset", "balanced");
		});
		return List.copyOf(args);
	}

	/** Vulkan video: cqp when asked for, otherwise vbr; it has tuning modes but no presets. */
	static List<String> vulkan(ExportEncodingMode mode, int bitrate, int quality,
			boolean constantQuality) {
		List<String> args = new ArrayList<>();
		if (constantQuality) {
			args.addAll(List.of("-rc_mode", "cqp", "-qp", String.valueOf(clampQp(quality))));
		} else {
			args.addAll(List.of("-rc_mode", "vbr"));
			args.addAll(bitrateArguments(bitrate));
		}
		args.addAll(switch (mode) {
			case FAST -> List.of("-tune", "ll");
			case QUALITY -> List.of("-tune", "hq");
			case BALANCED -> List.of("-tune", "default");
		});
		return List.copyOf(args);
	}

	/** VA-API's quality knob: lower is better, clamped by the driver to 0-32. */
	static int vaapiQuality(ExportEncodingMode mode) {
		return switch (mode) {
			case FAST -> 8;
			case QUALITY -> 2;
			case BALANCED -> 4;
		};
	}

	/** The bitrate target arguments shared by the vendor encoders (same shape as the x264 path). */
	static List<String> bitrateArguments(int bitrate) {
		return Mp4Encoder.bitrateArguments(bitrate);
	}

	/** H.264 QP is 0-51; values outside are clamped rather than rejected by the driver. */
	static int clampQp(int qp) {
		return Math.min(51, Math.max(0, qp));
	}

	/**
	 * The label the export dialog shows for a spec. The software path keeps its pipeline note
	 * ("GPU compositing + YUV"), which tells the user the compositor still ran on the GPU.
	 */
	public static String label(VideoEncoderSpec spec, boolean yuv420pInput) {
		if (!spec.hardware()) {
			return yuv420pInput
					? spec.codec() + " (CPU) · GPU compositing + YUV"
					: spec.codec() + " (software, CPU)";
		}
		return spec.label();
	}
}
