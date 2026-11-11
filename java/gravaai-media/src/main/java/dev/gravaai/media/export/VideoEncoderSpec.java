package dev.gravaai.media.export;


/**
 * One usable H.264 encoder and everything that differs between the software and hardware families
 * (card "GPU hardware encoding for export and recording").
 *
 * <p>A spec is pure data: the argument builders ask it what to pass and never branch on a vendor
 * name themselves. The three things that actually differ are
 * <ul>
 *   <li>the codec id ({@code libx264}, {@code h264_vaapi}, {@code h264_nvenc}, …);</li>
 *   <li>the rate control — x264 takes {@code -crf}, the vendors take a bitrate target, and VA-API
 *       rejects the presets x264 relies on;</li>
 *   <li>the pixel pipeline — VA-API and Vulkan video only accept GPU surfaces, so the frames must
 *       be uploaded with a filter fragment instead of arriving as I420.</li>
 * </ul>
 *
 * @param codec the ffmpeg codec id, e.g. {@code libx264} or {@code h264_vaapi}
 * @param family the vendor family the arguments are shaped for
 */
public record VideoEncoderSpec(String codec, Family family) {

	/** The encoder families the app knows how to drive, in probe order. */
	public enum Family {
		/** Software x264: the universal fallback. */
		X264("libx264", false),
		/** AMD/Intel VA-API. Needs a render node and takes bitrate or explicit-QP rate control. */
		VAAPI("h264_vaapi", true),
		/** NVIDIA NVENC. Takes ffmpeg's plain host frames. */
		NVENC("h264_nvenc", false),
		/** Intel Quick Sync. Takes host frames (or its own surfaces). */
		QSV("h264_qsv", false),
		/** AMD AMF. Takes host frames. */
		AMF("h264_amf", false),
		/** Vulkan video; youngest of the family and last in the probe order. */
		VULKAN("h264_vulkan", true);

		private final String codec;
		private final boolean uploadsFrames;

		Family(String codec, boolean uploadsFrames) {
			this.codec = codec;
			this.uploadsFrames = uploadsFrames;
		}

		/** The ffmpeg codec id of this family's H.264 encoder. */
		public String codec() {
			return codec;
		}

		/**
		 * Whether the encoder only accepts GPU frames, so the filter chain must end in
		 * {@code format=nv12,hwupload}. The other families take host I420/RGBA directly.
		 */
		public boolean uploadsFrames() {
			return uploadsFrames;
		}

		/** Whether this family runs on the GPU (the honest "(GPU)" label). */
		public boolean hardware() {
			return this != X264;
		}
	}

	/** The software fallback every job can always use. */
	public static final VideoEncoderSpec SOFTWARE = new VideoEncoderSpec(Family.X264.codec(), Family.X264);

	public VideoEncoderSpec {
		if (codec == null || codec.isBlank()) {
			throw new IllegalArgumentException("an encoder spec needs a codec id");
		}
		if (family == null) {
			throw new IllegalArgumentException("an encoder spec needs a family");
		}
	}

	/** Whether this encoder runs on the GPU. */
	public boolean hardware() {
		return family.hardware();
	}

	/** The label the export dialog and the recording HUD show; names what actually ran. */
	public String label() {
		return codec + (hardware() ? " (GPU)" : " (CPU)");
	}

	/**
	 * The {@code ffmpeg} device-initialisation arguments this family needs, or an empty list.
	 *
	 * <p>VA-API needs the render node spelled out; the others discover their device themselves.
	 */
	public java.util.List<String> deviceArguments() {
		if (family != Family.VAAPI) {
			return java.util.List.of();
		}
		return java.util.List.of("-init_hw_device", "vaapi=va:" + vaapiRenderNode(),
				"-filter_hw_device", "va");
	}

	/**
	 * The VA-API render node: the first {@code /dev/dri/renderD*}, or {@code renderD128} when the
	 * directory cannot be listed. Probing only looks for the node's presence; whether the card can
	 * actually encode is settled by running the job.
	 */
	static String vaapiRenderNode() {
		return VaapiDevices.firstRenderNode().map(java.nio.file.Path::toString)
				.orElse("/dev/dri/renderD128");
	}
}
