package dev.gravaai.media.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link MediaProbe} against the committed fixtures (plan §5.7). */
class MediaProbeTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("probes the 2 s 1080p60 H.264 fixture")
	void probesH264Fixture() {
		MediaInfo info = MediaProbe.probe(MediaFixtures.h264());
		assertEquals(2.0, info.durationSeconds(), 0.05);
		assertTrue(info.hasVideo());
		assertFalse(info.hasAudio());
		VideoStreamInfo video = info.video().orElseThrow();
		assertEquals("h264", video.codec());
		assertEquals(1920, video.width());
		assertEquals(1080, video.height());
		assertEquals(60.0, video.fps(), 0.01);
		assertEquals(0, video.rotationDegrees());
		assertEquals(2.0, video.durationSeconds(), 0.05);
	}

	@Test
	@DisplayName("probes the 2 s 720p30 VP9 WebM fixture")
	void probesVp9Fixture() {
		MediaInfo info = MediaProbe.probe(MediaFixtures.vp9());
		assertEquals(2.0, info.durationSeconds(), 0.05);
		assertTrue(info.hasVideo());
		assertFalse(info.hasAudio());
		VideoStreamInfo video = info.video().orElseThrow();
		assertEquals("vp9", video.codec());
		assertEquals(1280, video.width());
		assertEquals(720, video.height());
		assertEquals(30.0, video.fps(), 0.01);
		assertEquals(0, video.rotationDegrees());
	}

	@Test
	@DisplayName("probes the 48 kHz stereo AAC fixture")
	void probesAacFixture() {
		MediaInfo info = MediaProbe.probe(MediaFixtures.aac());
		assertEquals(2.0, info.durationSeconds(), 0.05);
		assertFalse(info.hasVideo());
		assertTrue(info.hasAudio());
		AudioStreamInfo audio = info.audio().orElseThrow();
		assertEquals("aac", audio.codec());
		assertEquals(48_000, audio.sampleRate());
		assertEquals(2, audio.channels());
		assertEquals("stereo", audio.channelLayout());
		assertEquals(2.0, audio.durationSeconds(), 0.05);
	}

	@Test
	@DisplayName("reads the 90 degree rotation from the display matrix")
	void readsDisplayMatrixRotation() {
		MediaInfo info = MediaProbe.probe(MediaFixtures.rotated());
		VideoStreamInfo video = info.video().orElseThrow();
		assertEquals(1080, video.width());
		assertEquals(1920, video.height());
		assertEquals(90, video.rotationDegrees());
	}

	@Test
	@DisplayName("a missing file is rejected before FFmpeg is called")
	void missingFileIsRejected() {
		Path missing = tempDir.resolve("does-not-exist.mp4");
		assertThrows(IllegalArgumentException.class, () -> MediaProbe.probe(missing));
	}

	@Test
	@DisplayName("a non-media file raises an FfmpegException")
	void nonMediaFileRaisesFfmpegException() throws Exception {
		Path garbage = tempDir.resolve("garbage.mp4");
		Files.writeString(garbage, "definitely not a media container");
		FfmpegException failure = assertThrows(FfmpegException.class,
				() -> MediaProbe.probe(garbage));
		assertNotNull(failure.operation());
	}
}
