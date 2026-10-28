package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.media.export.CaptionSidecars.Cue;
import dev.gravaai.media.export.CaptionSidecars.Format;
import dev.gravaai.media.export.CaptionSidecars.Payload;

/** Port of {@code electron/ipc/register/exportCaptionSidecars.test.ts}. */
class CaptionSidecarsTest {

	@Test
	@DisplayName("serializes SRT cues with stable numbering and timestamps")
	void serializesSrt() {
		assertEquals("1\n00:00:01,234 --> 00:00:05,678\nHello\nworld",
				CaptionSidecars.serializeSrt(List.of(new Cue(1234, 5678, "Hello\nworld"))));
	}

	@Test
	@DisplayName("serializes VTT cues with header and dot timestamps")
	void serializesVtt() {
		assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nCaption",
				CaptionSidecars.serializeVtt(List.of(new Cue(0, 2000, "Caption"))));
	}

	@Test
	@DisplayName("drops malformed cues when normalizing sidecar payloads")
	void dropsMalformedCues() {
		List<Cue> normalized = CaptionSidecars.normalizeCues(List.of(
				new Cue(0, 1000, "ok"),
				new Cue(2000, 1000, "bad range"),
				new Cue(1000, 2000, "   "),
				new Cue(0, 1000, "  trimmed  ")));
		assertEquals(List.of(new Cue(0, 1000, "ok"), new Cue(0, 1000, "trimmed")), normalized);
	}

	@Test
	@DisplayName("returns a warning result instead of throwing when sidecar writes fail")
	void writeFailureIsBestEffort(@TempDir Path tempDir) {
		Path missingParent = tempDir.resolve("missing").resolve("export.mp4");
		CaptionSidecars.WriteResult result = CaptionSidecars.writeBestEffort(missingParent,
				new Payload(Format.SRT, List.of(new Cue(0, 1000, "Caption"))));
		assertFalse(result.wroteAny());
		assertNotNull(result.error());
	}

	@Test
	@DisplayName("writes requested caption sidecars when the filesystem succeeds")
	void writesSidecars(@TempDir Path tempDir) throws Exception {
		Path videoPath = tempDir.resolve("clip.mp4");
		CaptionSidecars.WriteResult result = CaptionSidecars.writeBestEffort(videoPath,
				new Payload(Format.BOTH, List.of(new Cue(0, 1000, "Caption"))));
		assertTrue(result.wroteAny());
		assertEquals(null, result.error());
		assertTrue(Files.readString(tempDir.resolve("clip.srt"))
				.contains("00:00:00,000 --> 00:00:01,000"));
		assertTrue(Files.readString(tempDir.resolve("clip.vtt")).contains("WEBVTT"));
	}

	@Test
	@DisplayName("appends a non-fatal caption warning only when sidecar writes fail")
	void appendsWarning() {
		assertEquals("Video exported successfully Captions could not be saved alongside the video.",
				CaptionSidecars.withSidecarMessage("Video exported successfully",
						new CaptionSidecars.WriteResult(false, "disk full")));
		assertEquals("Video exported successfully", CaptionSidecars.withSidecarMessage(
				"Video exported successfully", new CaptionSidecars.WriteResult(true, null)));
	}
}
