package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** API transcription chunking (electron/ipc/meeting/apiTranscription.ts). */
class ApiTranscriptionTest {

	@Test
	@DisplayName("cuts API uploads into ten minute mono mp3 segments")
	void cutsTenMinuteMonoChunks() {
		// The command does not repeat the ffmpeg executable: the caller runs it via MediaTools.
		assertEquals(List.of(
				"-hide_banner", "-loglevel", "error", "-nostdin", "-y",
				"-i", "/r/a.m4a",
				"-map", "0:a:0", "-vn",
				"-ac", "1", "-ar", "16000",
				"-c:a", "libmp3lame", "-b:a", "48k",
				"-f", "segment", "-segment_time", "600", "-reset_timestamps", "1",
				"/t/chunk-%03d.mp3"),
				ApiTranscription.chunkCommand("/r/a.m4a", "/t/chunk-%03d.mp3"));
		assertEquals(600, ApiTranscription.CHUNK_SECONDS);
	}
}
