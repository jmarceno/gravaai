package dev.gravaai.services.captions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the re-segmentation behaviour in electron/ipc/captions/silence.ts. */
class CaptionSilenceTest {

	private static CaptionCue cue(String id, long startMs, long endMs, String text) {
		return new CaptionCue(id, startMs, endMs, text);
	}

	@Test
	@DisplayName("parses silencedetect stderr into ordered intervals")
	void parsesSilenceIntervals() {
		String stderr = """
				[silencedetect @ 0x1] silence_start: 1.25
				[silencedetect @ 0x1] silence_end: 2.5 | silence_duration: 1.25
				[silencedetect @ 0x2] silence_start: 3.0
				""";
		List<CaptionSilence.SilenceInterval> intervals =
				CaptionSilence.parseSilenceIntervals(stderr);
		assertEquals(2, intervals.size());
		assertEquals(1_250, intervals.get(0).startMs());
		assertEquals(2_500, intervals.get(0).endMs());
		assertEquals(3_000, intervals.get(1).startMs());
		assertEquals(CaptionSilence.INFINITE_END, intervals.get(1).endMs());
	}

	@Test
	@DisplayName("splits a transcript at a long silence and trims the edges")
	void splitsAtLongSilence() {
		List<CaptionCue> cues = List.of(
				cue("c1", 0, 1_000, "first part"),
				cue("c2", 3_000, 4_000, "second part"));
		List<CaptionSilence.SilenceInterval> silences = List.of(
				new CaptionSilence.SilenceInterval(1_000, 3_000));
		List<CaptionCue> result = CaptionSilence.resegmentCuesBySilence(
				cues, silences, CaptionSilence.ResegmentOptions.defaults());
		assertEquals(2, result.size());
		assertEquals("first part", result.get(0).text());
		assertEquals("second part", result.get(1).text());
		assertTrue(result.get(0).endMs() < result.get(1).startMs());
	}

	@Test
	@DisplayName("merges continuous speech and keeps separate later regions")
	void mergesContinuousSpeechAndKeepsLaterRegions() {
		List<CaptionCue> cues = List.of(
				cue("c1", 0, 500, "hello"),
				cue("c2", 600, 1_000, "world"),
				cue("c3", 5_000, 6_000, "hallucination"));
		List<CaptionSilence.SilenceInterval> silences = List.of(
				new CaptionSilence.SilenceInterval(1_000, 5_000));
		List<CaptionCue> result = CaptionSilence.resegmentCuesBySilence(
				cues, silences, new CaptionSilence.ResegmentOptions(1_500L, 0L, 150L));
		assertEquals(2, result.size());
		assertEquals("hello world", result.get(0).text());
		assertEquals("hallucination", result.get(1).text());
	}

	@Test
	@DisplayName("padSpans never overlaps and respects edge padding")
	void padsWithoutOverlap() {
		List<CaptionSilence.Span> spans = new ArrayList<>(List.of(
				new CaptionSilence.Span(1_000, 2_000),
				new CaptionSilence.Span(2_500, 3_500)));
		CaptionSilence.padSpans(spans, 80);
		assertEquals(920, spans.get(0).startMs);
		assertTrue(spans.get(0).endMs <= spans.get(1).startMs);
		assertEquals(3_580, spans.get(1).endMs);
	}
}
