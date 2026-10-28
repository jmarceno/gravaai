package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.media.ffmpeg.VideoStreamInfo;

/** Real-ffmpeg tests of {@link Mp4Encoder}: the produced file is probed back (plan §5.10). */
class Mp4EncoderTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("encodes raw synthetic frames to an H.264 MP4 that probes back correctly")
	void encodesH264() throws Exception {
		Path output = tempDir.resolve("export.mp4");
		Mp4ExportRequest request = new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 1.0, output, ExportAudioPlan.NONE);
		List<ExportProgress> progress = new CopyOnWriteArrayList<>();
		ExportResult result = Mp4Encoder.encode(request,
				new SyntheticFrameProvider(160, 90, 24), progress::add, ExportCancellation.NONE);
		assertTrue(Files.isRegularFile(output));
		assertTrue(result.bytes() > 0);
		assertEquals(24, result.frames());
		MediaInfo info = MediaProbe.probe(output);
		assertTrue(info.hasVideo());
		VideoStreamInfo video = info.video().orElseThrow();
		assertEquals("h264", video.codec());
		assertEquals(160, video.width());
		assertEquals(90, video.height());
		assertEquals(1.0, info.durationSeconds(), 0.2);
		assertFalse(info.hasAudio());
		assertTrue(hasMoovBeforeMdat(output), "faststart MP4 should put moov before mdat");
		assertFalse(progress.isEmpty());
		assertEquals(100.0, progress.get(progress.size() - 1).percentage(), 0.001);
	}

	@Test
	@DisplayName("encodes H.264 video plus one aac audio input from a single filter graph")
	void encodesVideoAndAudio() throws Exception {
		Path output = tempDir.resolve("export-with-audio.mp4");
		ExportAudioPlan audioPlan = new ExportAudioPlan(List.of(ExportFixtures.aac()),
				"[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[aout]");
		Mp4ExportRequest request = new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.BALANCED, 1.0, output, audioPlan);
		Mp4Encoder.encode(request, new SyntheticFrameProvider(160, 90, 24),
				ExportProgressListener.NONE, ExportCancellation.NONE);
		MediaInfo info = MediaProbe.probe(output);
		assertTrue(info.hasVideo());
		assertTrue(info.hasAudio());
		assertEquals("aac", info.audio().orElseThrow().codec());
		assertEquals(48_000, info.audio().orElseThrow().sampleRate());
		assertEquals(2, info.audio().orElseThrow().channels());
		assertEquals(1.0, info.durationSeconds(), 0.25);
	}

	@Test
	@DisplayName("cancelling mid-encode kills ffmpeg and deletes the temp output")
	void cancelDeletesOutput() throws Exception {
		Path output = tempDir.resolve("cancelled.mp4");
		AtomicBoolean cancelled = new AtomicBoolean(false);
		ExportFrameProvider slowProvider = new ExportFrameProvider() {
			@Override
			public int width() {
				return 160;
			}

			@Override
			public int height() {
				return 90;
			}

			@Override
			public int frameRate() {
				return 24;
			}

			@Override
			public void renderFrame(long frameIndex, double timeSeconds, java.nio.ByteBuffer rgba)
					throws Exception {
				Thread.sleep(30);
				if (frameIndex >= 5) {
					cancelled.set(true);
				}
				new SyntheticFrameProvider(160, 90, 24).renderFrame(frameIndex, timeSeconds, rgba);
			}
		};
		Mp4ExportRequest request = new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 2.0, output, ExportAudioPlan.NONE);
		assertThrows(ExportCancelledException.class, () -> Mp4Encoder.encode(request, slowProvider,
				ExportProgressListener.NONE, cancelled::get));
		assertFalse(Files.exists(output));
	}

	@Test
	@DisplayName("rejects a frame provider whose dimensions do not match the request")
	void rejectsMismatchedProvider() {
		Mp4ExportRequest request = new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 1.0, tempDir.resolve("x.mp4"), ExportAudioPlan.NONE);
		assertThrows(IllegalArgumentException.class, () -> Mp4Encoder.encode(request,
				new SyntheticFrameProvider(320, 180, 24), ExportProgressListener.NONE,
				ExportCancellation.NONE));
	}

	private static boolean hasMoovBeforeMdat(Path file) throws Exception {
		byte[] bytes = Files.readAllBytes(file);
		int moov = indexOf(bytes, "moov".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		int mdat = indexOf(bytes, "mdat".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		return moov >= 0 && mdat >= 0 && moov < mdat;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		for (int index = 0; index + needle.length <= haystack.length; index++) {
			boolean match = true;
			for (int offset = 0; offset < needle.length; offset++) {
				if (haystack[index + offset] != needle[offset]) {
					match = false;
					break;
				}
			}
			if (match) {
				return index;
			}
		}
		return -1;
	}
}
