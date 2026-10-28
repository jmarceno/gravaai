package dev.gravaai.services.captions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the pure parser behaviour in electron/ipc/captions/parser.ts. */
class CaptionParserTest {

	@Test
	@DisplayName("builds text from words honouring leading spaces")
	void buildsTextFromWords() {
		List<CaptionWord> words = List.of(
				CaptionWord.of("Hello", 0, 100),
				new CaptionWord("world", 100, 200, true),
				CaptionWord.of(".", 200, 220));
		assertEquals("Hello world.", CaptionParser.buildCaptionTextFromWords(words));
	}

	@Test
	@DisplayName("parses whisper JSON word tokens with punctuation and leading spaces")
	void parsesWordTokens() {
		String json = """
				{"transcription":[{
				  "offsets":{"from":0,"to":640},
				  "text":" Hello world.",
				  "tokens":[
				    {"text":" Hello","offsets":{"from":0,"to":320}},
				    {"text":" world","offsets":{"from":320,"to":520}},
				    {"text":".","offsets":{"from":520,"to":640}}
				  ]}]}
				""";
		List<CaptionCue> cues = CaptionParser.parseWhisperJsonCues(json);
		assertEquals(1, cues.size());
		CaptionCue cue = cues.get(0);
		assertEquals("caption-1", cue.id());
		assertEquals(0, cue.startMs());
		assertEquals(640, cue.endMs());
		assertEquals("Hello world.", cue.text());
		assertEquals(2, cue.words().size());
		assertEquals("Hello", cue.words().get(0).text());
		assertTrue(cue.words().get(1).leadingSpace());
	}

	@Test
	@DisplayName("falls back to the segment text when tokens are unusable")
	void fallsBackToSegmentText() {
		String json = """
				{"transcription":[{"offsets":{"from":0,"to":500},"text":" plain text ",
				  "tokens":[{"text":" plain","offsets":{"from":0,"to":250}},
				    {"text":" text","offsets":{"from":250,"to":500}}]}]}
				""";
		List<CaptionCue> cues = CaptionParser.parseWhisperJsonCues(json);
		assertEquals(1, cues.size());
		assertEquals("plain text", cues.get(0).text());
	}

	@Test
	@DisplayName("ignores invalid, empty and zero-length JSON segments")
	void ignoresInvalidSegments() {
		assertTrue(CaptionParser.parseWhisperJsonCues("not json").isEmpty());
		assertTrue(CaptionParser.parseWhisperJsonCues("{}").isEmpty());
		assertTrue(CaptionParser.parseWhisperJsonCues("""
				{"transcription":[{"offsets":{"from":5,"to":5},"text":"x"}]}
				""").isEmpty());
	}

	@Test
	@DisplayName("parses SRT timestamps")
	void parsesSrtTimestamps() {
		assertEquals(3_723_456L, CaptionParser.parseSrtTimestamp("01:02:03,456"));
		assertNull(CaptionParser.parseSrtTimestamp("1:02:03,456"));
		assertNull(CaptionParser.parseSrtTimestamp("bad"));
	}

	@Test
	@DisplayName("parses SRT cues, ids and multi-line text")
	void parsesSrtCues() {
		String srt = """
				1
				00:00:00,000 --> 00:00:01,500
				Hello
				world

				2
				00:00:02,000 --> 00:00:03,000
				Second cue
				""";
		List<CaptionCue> cues = CaptionParser.parseSrtCues(srt);
		assertEquals(2, cues.size());
		assertEquals("caption-1", cues.get(0).id());
		assertEquals("Hello\nworld", cues.get(0).text());
		assertEquals(1_500, cues.get(0).endMs());
		assertEquals(2_000, cues.get(1).startMs());
	}

	@Test
	@DisplayName("retries without JSON only for the old-runtime option error")
	void retriesWithoutJsonOnlyForOptionErrors() {
		assertTrue(CaptionParser.shouldRetryWhisperWithoutJson(
				"error: unknown argument: --output-json-full"));
		assertTrue(CaptionParser.shouldRetryWhisperWithoutJson("unrecognized option '-ojf'"));
		assertFalse(CaptionParser.shouldRetryWhisperWithoutJson("error: failed to load model"));
		assertFalse(CaptionParser.shouldRetryWhisperWithoutJson(null));
	}
}
