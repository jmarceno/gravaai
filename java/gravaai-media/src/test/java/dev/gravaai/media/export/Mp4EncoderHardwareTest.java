package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.media.ffmpeg.VideoStreamInfo;

/**
 * The hardware encode path against this machine's real ffmpeg and GPU (card "GPU hardware encoding
 * for export and recording").
 *
 * <p>Disabled by default: the rest of the suite must never open a GPU device. Run it deliberately,
 * on hardware that supports the encoder, with
 * {@code -Dgravaai.gpuEncodeTests=true -Dgravaai.ffmpeg.bin=/usr/bin/ffmpeg}.
 *
 * <p>On the RX 560 this is the exact shape that hung the card on kernel 7.2.6 before the VCE fix
 * ({@code 2ee9836545e6}); with the fix present it encodes normally, which is why it is now part of
 * the suite's opt-in hardware gate.
 */
@EnabledIfSystemProperty(named = "gravaai.gpuEncodeTests", matches = "true")
class Mp4EncoderHardwareTest {

	@Test
	@DisplayName("probes h264_vaapi as the preferred encoder on this machine")
	void probeSelectsHardware() {
		VideoEncoderProbe probe = VideoEncoderProbe.bundled();
		assertFalse(probe.available().isEmpty());
		VideoEncoderSpec preferred = probe.preferred();
		assertTrue(preferred.hardware(),
				"expected a GPU encoder from " + probe.available() + " (set gravaai.ffmpeg.bin to a"
						+ " GPU-capable ffmpeg)");
		assertTrue(probe.select(false).codec().equals("libx264"),
				"hardwareAllowed=false must stay on the CPU");
	}

	@Test
	@DisplayName("encodes a 4K export through h264_vaapi and probes the file back")
	void encodesOnTheGpu() throws Exception {
		Path output = Path.of(System.getProperty("java.io.tmpdir"), "gravaai-hw-export.mp4");
		// 4K because exports below that size stay on libx264 by policy (measured break-even).
		Mp4ExportRequest request = new Mp4ExportRequest(3840, 2160, 30, 24_000_000,
				ExportEncodingMode.BALANCED, 0.2, output, ExportAudioPlan.NONE);
		VideoEncoderProbe probe = VideoEncoderProbe.bundled();
		VideoEncoderSpec hardware = probe.available().stream()
				.filter(VideoEncoderSpec::hardware)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no GPU encoder in " + probe.available()));
		Mp4Encoder.encode(request, new SyntheticFrameProvider(3840, 2160, 30),
				ExportProgressListener.NONE, ExportCancellation.NONE, VideoEncoderProbe.of(List.of(hardware)));
		assertTrue(java.nio.file.Files.isRegularFile(output));
		MediaInfo info = MediaProbe.probe(output);
		VideoStreamInfo video = info.video().orElseThrow();
		assertEquals("h264", video.codec());
		assertEquals(3840, video.width());
		assertEquals(2160, video.height());
	}

	@Test
	@DisplayName("falls back to libx264 when the hardware encoder cannot start")
	void fallsBackToSoftware() throws Exception {
		Path output = Path.of(System.getProperty("java.io.tmpdir"), "gravaai-hw-fallback.mp4");
		Mp4ExportRequest request = new Mp4ExportRequest(320, 180, 30, 2_000_000,
				ExportEncodingMode.FAST, 1.0, output, ExportAudioPlan.NONE);
		// A codec that ffmpeg knows nothing about makes the first attempt fail exactly like a broken
		// driver session, so the retry on libx264 exercises the production fallback.
		VideoEncoderSpec broken = new VideoEncoderSpec("h264_nonexistent",
				VideoEncoderSpec.Family.NVENC);
		Mp4Encoder.encode(request, new SyntheticFrameProvider(320, 180, 30),
				ExportProgressListener.NONE, ExportCancellation.NONE, VideoEncoderProbe.of(List.of(broken)));
		assertTrue(java.nio.file.Files.isRegularFile(output));
		assertEquals("h264", MediaProbe.probe(output).video().orElseThrow().codec());
	}

	@Test
	@DisplayName("the real probe reports only encoders this ffmpeg can actually use")
	void probeParsesEncoders() {
		Set<String> parsed = VideoEncoderProbe.parseEncoders("""
				 Encoders:
				 V..... = Video
				 ------
				 V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
				 V....D h264_vaapi           H.264/AVC (VAAPI) (codec h264)
				 A....D aac                  AAC (Advanced Audio Coding)
				""");
		assertTrue(parsed.contains("libx264"));
		assertTrue(parsed.contains("h264_vaapi"));
		assertFalse(parsed.contains("aac"), "audio encoders must not be treated as video encoders");
	}
}
