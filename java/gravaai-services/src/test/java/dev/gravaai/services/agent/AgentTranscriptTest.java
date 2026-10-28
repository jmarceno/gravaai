package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionCueWord;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipSequence;
import dev.gravaai.services.agent.AgentTranscript.Range;
import dev.gravaai.services.agent.AgentTranscript.Word;

/** Port of {@code src/components/video-editor/transcriptCut.test.ts}. */
class AgentTranscriptTest {

	private static Word word(String text, long startMs, long endMs) {
		return new Word("cue:" + text, "cue", text, startMs, endMs, true);
	}

	private static CaptionCue cue(String id, long startMs, long endMs, String text,
			List<CaptionCueWord> words) {
		return new CaptionCue(id, startMs, endMs, text, words);
	}

	private static ClipRegion clip(String id, long startMs, long endMs, double speed,
			Long sourceStartMs, Long sourceMaxMs, Boolean muted, Boolean showSourceAudio) {
		return new ClipRegion(id, startMs, endMs, sourceStartMs, null, sourceMaxMs, speed, muted,
				showSourceAudio, null, null);
	}

	@Test
	@DisplayName("keeps every word of a cue with word timings in cue order")
	void keepsWordTimings() {
		List<Word> words = AgentTranscript.flattenTranscriptWords(List.of(cue("c1", 1000, 2000,
				"hello world", List.of(new CaptionCueWord("hello", 1000, 1400, null),
						new CaptionCueWord("world", 1500, 1900, null)))));
		assertEquals(List.of(new Word("c1:0", "c1", "hello", 1000, 1400, false),
				new Word("c1:1", "c1", "world", 1500, 1900, true)), words);
	}

	@Test
	@DisplayName("falls back to one entry per cue when there are no word timings")
	void fallsBackToCueEntries() {
		List<Word> words = AgentTranscript.flattenTranscriptWords(
				List.of(cue("c2", 500, 900, "  whole cue  ", null)));
		assertEquals(List.of(new Word("c2:-1", "c2", "whole cue", 500, 900, true)), words);
	}

	@Test
	@DisplayName("drops zero-length cues and skips cues out of order")
	void dropsEmptyCues() {
		List<Word> words = AgentTranscript.flattenTranscriptWords(List.of(
				cue("late", 4000, 4500, "late", null), cue("empty", 100, 100, "gone", null),
				cue("blank", 200, 300, "   ", null), cue("early", 0, 50, "early", null)));
		assertEquals(List.of("early:-1", "late:-1"),
				words.stream().map(Word::key).toList());
	}

	@Test
	@DisplayName("merges overlapping and touching ranges and sorts the result")
	void mergesRanges() {
		assertEquals(List.of(new Range(1000, 3500), new Range(5000, 6000)),
				AgentTranscript.mergeSourceRanges(List.of(new Range(5000, 6000),
						new Range(1000, 2000), new Range(1500, 3000), new Range(3000, 3500))));
	}

	@Test
	@DisplayName("drops invalid ranges")
	void dropsInvalidRanges() {
		assertEquals(List.of(new Range(100, 251)), AgentTranscript.mergeSourceRanges(
				List.of(new Range(2000, 2000), new Range(3000, 1000), new Range(100, 251))));
	}

	@Test
	@DisplayName("takes the trailing silence of a mid-sentence word without reaching the next one")
	void trailingSilenceMidSentence() {
		List<Word> words = List.of(word("the", 1000, 1200), word("quick", 1250, 1600),
				word("brown", 2400, 2700));
		assertEquals(List.of(new Range(1250, 1850)),
				AgentTranscript.wordsToCutRanges(words, Set.of("cue:quick")));
	}

	@Test
	@DisplayName("never cuts past the next word when the silence is shorter than the gap")
	void shortSilence() {
		List<Word> words = List.of(word("quick", 1000, 1600), word("brown", 1650, 1900));
		assertEquals(List.of(new Range(1000, 1650)),
				AgentTranscript.wordsToCutRanges(words, Set.of("cue:quick")));
	}

	@Test
	@DisplayName("caps the trailing silence at the gap and extends the last word")
	void capsLastWordGap() {
		List<Word> words = List.of(word("quick", 1000, 1300), word("brown", 5000, 5200));
		assertEquals(List.of(new Range(1000, 1550)),
				AgentTranscript.wordsToCutRanges(words, Set.of("cue:quick")));
		assertEquals(List.of(new Range(5000, 5450)),
				AgentTranscript.wordsToCutRanges(words, Set.of("cue:brown")));
	}

	@Test
	@DisplayName("merges adjacent selected words into one range")
	void mergesAdjacentWords() {
		List<Word> words = List.of(word("a", 100, 200), word("b", 210, 300),
				word("c", 900, 1000));
		assertEquals(List.of(new Range(100, 550)),
				AgentTranscript.wordsToCutRanges(words, Set.of("cue:a", "cue:b")));
	}

	@Test
	@DisplayName("returns nothing when no word is selected")
	void noSelection() {
		assertEquals(List.of(), AgentTranscript.wordsToCutRanges(
				List.of(word("a", 100, 200)), Set.of()));
	}

	@Test
	@DisplayName("keeps the outgoing transition on the last piece only")
	void transitionOnLastPiece() {
		ClipRegion withTransition = new ClipRegion("a", 0, 10_000, null, null, null, 1, null,
				null, null, new dev.gravaai.core.project.ClipTransition(
						dev.gravaai.core.project.ClipTransitionKind.FADE_BLACK, 800));
		List<ClipRegion> next = AgentTranscriptTestSupport.removeSourceRanges(
				List.of(withTransition), List.of(new Range(2000, 3000), new Range(5000, 6000)));
		assertEquals(3, next.size());
		assertEquals(null, next.get(0).transitionOut());
		assertEquals(null, next.get(1).transitionOut());
		assertEquals(withTransition.transitionOut(), next.get(2).transitionOut());
	}

	@Test
	@DisplayName("converts the removed source span back through the clip's speed")
	void speedConversion() {
		ClipRegion sped = clip("a", 0, 5000, 2, 0L, null, null, false);
		List<ClipRegion> next = AgentTranscriptTestSupport.removeSourceRanges(List.of(sped),
				List.of(new Range(4000, 6000)));
		assertEquals(2, next.size());
		assertEquals("a", next.get(0).id());
		assertEquals(0, next.get(0).sourceStartMs());
		assertEquals(2000, next.get(0).endMs());
		assertEquals("new", next.get(1).id());
		assertEquals(6000, next.get(1).sourceStartMs());
		assertEquals(2000, next.get(1).endMs());
	}

	@Test
	@DisplayName("returns the same list when the cut touches no clip")
	void unchangedWhenNoClipTouched() {
		List<ClipRegion> clips = List.of(clip("a", 0, 2000, 1, null, null, null, false),
				clip("b", 2000, 4000, 1, null, null, null, false));
		assertEquals(clips, AgentTranscriptTestSupport.removeSourceRanges(clips,
				List.of(new Range(9000, 9500))));
		assertEquals(clips, AgentTranscriptTestSupport.removeSourceRanges(clips, List.of()));
	}

	@Test
	@DisplayName("removes a clip whose whole source span is cut")
	void removesFullyCutClip() {
		List<ClipRegion> clips = List.of(clip("a", 0, 2000, 1, null, null, null, false),
				clip("b", 2000, 4000, 1, null, null, null, false),
				clip("c", 4000, 6000, 1, null, null, null, false));
		List<ClipRegion> next = AgentTranscriptTestSupport.removeSourceRanges(clips,
				List.of(new Range(2000, 4000)));
		assertEquals(List.of("a", "c"), next.stream().map(ClipRegion::id).toList());
	}

	@Test
	@DisplayName("refuses to remove the whole video")
	void refusesWholeVideo() {
		List<ClipRegion> clips = List.of(clip("a", 0, 2000, 1, null, null, null, false),
				clip("b", 2000, 4000, 1, null, null, null, false));
		assertEquals(clips,
				AgentTranscriptTestSupport.removeSourceRanges(clips, List.of(new Range(0, 4000))));
	}

	@Test
	@DisplayName("keeps clip flags and repacks into a contiguous timeline")
	void keepsFlagsAndRepacks() {
		ClipRegion flagged = clip("a", 0, 10_000, 1, null, 10_000L, true, false);
		List<ClipRegion> next = ClipSequence.packClipSequence(
				AgentTranscriptTestSupport.removeSourceRanges(List.of(flagged),
						List.of(new Range(2000, 3000))));
		assertEquals(2, next.size());
		assertEquals("a", next.get(0).id());
		assertEquals(0, next.get(0).startMs());
		assertEquals(2000, next.get(0).endMs());
		assertEquals(0, next.get(0).sourceStartMs());
		assertEquals(true, next.get(0).muted());
		assertEquals(10_000L, next.get(0).sourceMaxMs());
		assertEquals("new", next.get(1).id());
		assertEquals(2000, next.get(1).startMs());
		assertEquals(9000, next.get(1).endMs());
		assertEquals(3000, next.get(1).sourceStartMs());
		assertEquals(true, next.get(1).muted());
	}

	@Test
	@DisplayName("is true inside the kept footage and false once its centre is cut")
	void isWordKept() {
		List<ClipRegion> clips = List.of(new ClipRegion("a", 0, 2000, 0L, null, null, 1, null,
				null, null, null));
		assertTrue(AgentTranscript.isWordKept(word("kept", 100, 300), clips));
		assertFalse(AgentTranscript.isWordKept(word("cut", 2100, 2300), clips));
		assertTrue(AgentTranscript.isWordKept(word("edge", 1900, 2000), clips));
	}

	@Test
	@DisplayName("matches English fillers through punctuation and skips real words")
	void englishFillers() {
		List<Word> words = fillerWords();
		assertEquals(Set.of("cue:Um,"), AgentTranscript.selectFillerWords(words, "en"));
	}

	@Test
	@DisplayName("matches Portuguese fillers but not the verb \"é\"")
	void portugueseFillers() {
		assertEquals(Set.of("cue:hã", "cue:éé…"),
				AgentTranscript.selectFillerWords(fillerWords(), "pt-BR"));
	}

	@Test
	@DisplayName("uses both lists for auto")
	void autoFillers() {
		assertEquals(Set.of("cue:Um,", "cue:hã", "cue:éé…"),
				AgentTranscript.selectFillerWords(fillerWords(), "auto"));
	}

	@Test
	@DisplayName("selects nothing for an unknown language")
	void unknownLanguage() {
		assertEquals(Set.of(), AgentTranscript.selectFillerWords(fillerWords(), "de"));
	}

	private static List<Word> fillerWords() {
		return List.of(word("Um,", 0, 200), word("umbrella", 200, 500), word("hã", 500, 700),
				word("é", 700, 800), word("éé…", 800, 900), word("like", 800, 1000));
	}
}
