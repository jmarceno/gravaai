package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;

/**
 * The encoder-selection seam (card "GPU hardware encoding for export and recording"). Everything
 * here is pure data: no GPU device is opened and no ffmpeg process is started.
 */
class VideoEncoderProbeTest {

	@Test
	@DisplayName("parses video encoder names and ignores audio encoders and headers")
	void parsesEncoders() {
		Set<String> names = VideoEncoderProbe.parseEncoders("""
				 Encoders:
				 V..... = Video
				 ------
				 V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
				 V....D h264_vaapi           H.264/AVC (VAAPI) (codec h264)
				 V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
				 A....D aac                  AAC (Advanced Audio Coding)
				  V....D av1_amf             AMD AMF AV1 encoder (codec av1)
				""");
		assertTrue(names.contains("libx264"));
		assertTrue(names.contains("h264_vaapi"));
		assertTrue(names.contains("h264_nvenc"));
		assertFalse(names.contains("aac"));
	}

	@Test
	@DisplayName("a probe always offers the software fallback last")
	void softwareIsAlwaysAvailable() {
		VideoEncoderProbe probe = VideoEncoderProbe.of(List.of(VideoEncoderSpec.SOFTWARE));
		assertEquals("libx264", probe.preferred().codec());
		assertFalse(probe.preferred().hardware());
	}

	@Test
	@DisplayName("hardwareAllowed=false pins the job to libx264 even when the GPU is ready")
	void hardwareCanBeRefused() {
		VideoEncoderSpec vaapi = new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);
		VideoEncoderProbe probe = VideoEncoderProbe.of(List.of(vaapi, VideoEncoderSpec.SOFTWARE));
		assertEquals("h264_vaapi", probe.select(true).codec());
		assertEquals("libx264", probe.select(false).codec());
	}

	@Test
	@DisplayName("the first available encoder wins, in probe order")
	void firstAvailableWins() {
		VideoEncoderSpec vaapi = new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);
		VideoEncoderSpec nvenc = new VideoEncoderSpec("h264_nvenc", VideoEncoderSpec.Family.NVENC);
		VideoEncoderProbe probe = VideoEncoderProbe.of(List.of(vaapi, nvenc, VideoEncoderSpec.SOFTWARE));
		assertEquals("h264_vaapi", probe.preferred().codec());
	}

	@Test
	@DisplayName("exports stay on libx264 below 4K, where the hardware encoder is slower here")
	void exportUsesHardwareOnlyFrom4k() {
		VideoEncoderSpec vaapi = new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);
		VideoEncoderProbe probe = VideoEncoderProbe.of(List.of(vaapi, VideoEncoderSpec.SOFTWARE));
		assertEquals("libx264", probe.selectForExport(1280, 720).codec());
		assertEquals("libx264", probe.selectForExport(1920, 1080).codec());
		assertEquals("libx264", probe.selectForExport(2560, 1440).codec());
		assertEquals("h264_vaapi", probe.selectForExport(3840, 2160).codec());
		assertEquals("libx264", probe.selectForExport(0, 0).codec(), "degenerate sizes must not crash");
	}

	@Test
	@DisplayName("recording always prefers hardware, because it only has to keep up with capture")
	void recordingPrefersHardware() {
		VideoEncoderSpec vaapi = new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);
		VideoEncoderProbe probe = VideoEncoderProbe.of(List.of(vaapi, VideoEncoderSpec.SOFTWARE));
		assertEquals("h264_vaapi", probe.select(true).codec());
	}

	@Test
	@DisplayName("an empty probe is rejected rather than silently leaving the job without an encoder")
	void emptyProbeRejected() {
		assertThrows(IllegalArgumentException.class, () -> VideoEncoderProbe.of(List.of()));
	}

	@Test
	@DisplayName("only the GPU-surface families request an upload filter")
	void uploadOnlyForSurfaceEncoders() {
		assertTrue(VideoEncoderSpec.Family.VAAPI.uploadsFrames());
		assertTrue(VideoEncoderSpec.Family.VULKAN.uploadsFrames());
		assertFalse(VideoEncoderSpec.Family.X264.uploadsFrames());
		assertFalse(VideoEncoderSpec.Family.NVENC.uploadsFrames());
		assertFalse(VideoEncoderSpec.Family.QSV.uploadsFrames());
		assertFalse(VideoEncoderSpec.Family.AMF.uploadsFrames());
	}

	@Test
	@DisplayName("labels state whether the encoder ran on the GPU or the CPU")
	void labelsNameTheHardware() {
		assertEquals("h264_vaapi (GPU)", VideoEncoderSpec.Family.VAAPI.codec() + " (GPU)");
		assertEquals("libx264 (CPU)", VideoEncoderSpec.SOFTWARE.label());
		assertTrue(new VideoEncoderSpec("h264_nvenc", VideoEncoderSpec.Family.NVENC).hardware());
	}

	@Test
	@DisplayName("each family maps the export mode onto its own speed knob")
	void rateControlPerFamily() {
		VideoEncoderSpec vaapi = new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);
		List<String> fast = VideoEncoderArguments.rateControl(vaapi, ExportEncodingMode.FAST, 2_000_000, 18, false);
		assertTrue(fast.contains("VBR"), fast.toString());
		assertEquals("8", valueOf(fast, "-quality"), "FAST must prefer speed: " + fast);
		assertFalse(fast.contains("-preset"), "VA-API has no x264 presets: " + fast);
		assertFalse(fast.contains("-crf"), "VA-API has no CRF: " + fast);

		List<String> quality = VideoEncoderArguments.rateControl(vaapi,
				ExportEncodingMode.QUALITY, 2_000_000, 18, false);
		assertEquals("2", valueOf(quality, "-quality"), quality.toString());

		List<String> x264 = VideoEncoderArguments.rateControl(VideoEncoderSpec.SOFTWARE,
				ExportEncodingMode.FAST, 2_000_000, 18, false);
		assertEquals("18", valueOf(x264, "-crf"), x264.toString());
		assertEquals("ultrafast", valueOf(x264, "-preset"), x264.toString());
		assertFalse(x264.contains("-b:v"), "the x264 export path stays on CRF: " + x264);
	}

	@Test
	@DisplayName("the constant-quality paths use an explicit QP instead of a bitrate target")
	void constantQualityUsesQp() {
		VideoEncoderSpec vaapi = new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);
		List<String> args = VideoEncoderArguments.rateControl(vaapi,
				ExportEncodingMode.BALANCED, 2_000_000, 22, true);
		assertEquals("CQP", valueOf(args, "-rc_mode"), args.toString());
		assertEquals("22", valueOf(args, "-qp"), args.toString());
		assertFalse(args.contains("-b:v"), args.toString());

		List<String> nvenc = VideoEncoderArguments.rateControl(
				new VideoEncoderSpec("h264_nvenc", VideoEncoderSpec.Family.NVENC),
				ExportEncodingMode.BALANCED, 2_000_000, 22, true);
		assertEquals("constqp", valueOf(nvenc, "-rc"), nvenc.toString());
		assertEquals("22", valueOf(nvenc, "-qp"), nvenc.toString());
	}

	@Test
	@DisplayName("the software export keeps its full argument shape")
	void softwareExportArgumentsUnchanged() {
		Mp4ExportRequest request = new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.BALANCED, 1.0, java.nio.file.Path.of("/tmp/x.mp4"),
				ExportAudioPlan.NONE);
		List<String> args = Mp4Encoder.buildArguments(request, true, VideoEncoderSpec.SOFTWARE);
		assertTrue(args.contains("libx264"), args.toString());
		assertEquals("medium", valueOf(args, "-preset"), args.toString());
		assertEquals("20", valueOf(args, "-crf"), args.toString());
		assertFalse(args.contains("hwupload"), args.toString());
		assertFalse(args.contains("-init_hw_device"), args.toString());
	}

	@Test
	@DisplayName("the VA-API export uploads the frames and drops both the preset and the output pix_fmt")
	void vaapiExportArguments() {
		Mp4ExportRequest request = new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.BALANCED, 1.0, java.nio.file.Path.of("/tmp/x.mp4"),
				ExportAudioPlan.NONE);
		VideoEncoderSpec vaapi = new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);
		List<String> args = Mp4Encoder.buildArguments(request, true, vaapi);
		assertTrue(args.contains("h264_vaapi"), args.toString());
		assertEquals("format=nv12,hwupload", valueOf(args, "-vf"), args.toString());
		assertTrue(args.contains("-init_hw_device"), args.toString());
		assertTrue(args.contains("-filter_hw_device"), args.toString());
		assertFalse(args.contains("-preset"), "VA-API rejects -preset: " + args);
		assertFalse(args.contains("-crf"), args.toString());
		// Output -pix_fmt yuv420p is rejected for a hardware encoder; the upload filter fixed it.
		int codecIndex = args.indexOf("-c:v");
		assertFalse(args.subList(codecIndex, args.size()).contains("-pix_fmt"),
				"no output pix_fmt for a GPU encoder: " + args);
	}

	@Test
	@DisplayName("RGBA input is converted with BT.709 before the GPU upload")
	void rgbaInputConvertsBeforeUpload() {
		Mp4ExportRequest request = new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 1.0, java.nio.file.Path.of("/tmp/x.mp4"),
				ExportAudioPlan.NONE);
		VideoEncoderSpec vaapi = new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);
		List<String> args = Mp4Encoder.buildArguments(request, false, vaapi);
		assertEquals("scale=out_color_matrix=bt709:out_range=tv,format=nv12,hwupload",
				valueOf(args, "-vf"), args.toString());
	}

	@Test
	@DisplayName("a hardware encoder that fails is retried on libx264 and the file still lands")
	void hardwareFailureRetriesOnSoftware(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp)
			throws Exception {
		java.nio.file.Path output = temp.resolve("fallback.mp4");
		Mp4ExportRequest request = new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 1.0, output, ExportAudioPlan.NONE);
		VideoEncoderSpec broken = new VideoEncoderSpec("h264_this_does_not_exist",
				VideoEncoderSpec.Family.NVENC);
		ExportResult result = Mp4Encoder.encode(request, new SyntheticFrameProvider(160, 90, 24),
				ExportProgressListener.NONE, ExportCancellation.NONE,
				VideoEncoderProbe.of(List.of(broken)));
		assertTrue(java.nio.file.Files.isRegularFile(output));
		assertEquals(24, result.frames());
		assertEquals("h264", dev.gravaai.media.ffmpeg.MediaProbe.probe(output).video().orElseThrow().codec());
	}

	private static String valueOf(List<String> args, String flag) {
		int index = args.indexOf(flag);
		return index < 0 || index + 1 >= args.size() ? "" : args.get(index + 1);
	}

	@Test
	@DisplayName("each GPU candidate proves itself with a tiny encode into the null muxer")
	void verificationEncodeShape() {
		VideoEncoderSpec vaapi = new VideoEncoderSpec("h264_vaapi", VideoEncoderSpec.Family.VAAPI);
		List<String> command = VideoEncoderProbe.verificationCommand(
				java.nio.file.Path.of("/native/ffmpeg"), vaapi);
		assertEquals("/native/ffmpeg", command.getFirst());
		assertTrue(command.contains("-init_hw_device"), command.toString());
		assertTrue(command.indexOf("-init_hw_device") < command.indexOf("-i"), command.toString());
		assertEquals("lavfi", command.get(command.indexOf("-f") + 1), command.toString());
		assertEquals("3", command.get(command.indexOf("-frames:v") + 1), command.toString());
		assertEquals("format=nv12,hwupload", command.get(command.indexOf("-vf") + 1));
		assertEquals("h264_vaapi", command.get(command.indexOf("-c:v") + 1));
		assertEquals("CQP", command.get(command.indexOf("-rc_mode") + 1), command.toString());
		assertEquals(List.of("-f", "null", "-"), command.subList(command.size() - 3, command.size()));

		VideoEncoderSpec nvenc = new VideoEncoderSpec("h264_nvenc", VideoEncoderSpec.Family.NVENC);
		List<String> host = VideoEncoderProbe.verificationCommand(
				java.nio.file.Path.of("/native/ffmpeg"), nvenc);
		assertFalse(host.contains("-vf"), "host-frame encoders need no upload: " + host);
		assertEquals("yuv420p", host.get(host.indexOf("-pix_fmt") + 1), host.toString());
	}
}
