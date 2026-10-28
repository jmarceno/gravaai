package dev.gravaai.services.captions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of electron/ipc/captions/mergeSources.test.ts. */
class CaptionMergeTest {

	@Test
	@DisplayName("keeps non-overlapping sources and renumbers them")
	void keepsBothSources() {
		List<CaptionCue> mic = List.of(new CaptionCue("m1", 0, 1_000, "mic"));
		List<CaptionCue> system = List.of(new CaptionCue("s1", 2_000, 3_000, "system"));
		List<CaptionCue> merged = CaptionMerge.mergeCaptionSources(mic, system);
		assertEquals(2, merged.size());
		assertEquals("caption-1", merged.get(0).id());
		assertEquals("mic", merged.get(0).text());
		assertEquals("caption-2", merged.get(1).id());
	}

	@Test
	@DisplayName("drops system speech under microphone speech")
	void dropsOverlappingSystemSpeech() {
		List<CaptionCue> mic = List.of(new CaptionCue("m1", 0, 1_000, "mic"));
		List<CaptionCue> system = List.of(new CaptionCue("s1", 0, 1_000, "system"));
		List<CaptionCue> merged = CaptionMerge.mergeCaptionSources(mic, system);
		assertEquals(1, merged.size());
		assertEquals("mic", merged.get(0).text());
	}

	@Test
	@DisplayName("keeps unopposed portions of word-timed system cues")
	void keepsUnopposedSystemWords() {
		CaptionCue system = new CaptionCue("s1", 0, 2_000, "one two",
				List.of(new CaptionWord("one", 0, 500, false),
						new CaptionWord("two", 1_500, 2_000, true)));
		List<CaptionCue> merged = CaptionMerge.mergeCaptionSources(
				List.of(new CaptionCue("m1", 0, 1_000, "mic")),
				List.of(system));
		assertEquals(2, merged.size());
		CaptionCue tail = merged.stream().filter(cue -> cue.startMs() >= 1_000).findFirst().orElseThrow();
		assertEquals("two", tail.text());
	}

	@Test
	@DisplayName("keeps mic sound events that do not overlap system speech")
	void keepsMicSoundEvents() {
		List<CaptionCue> mic = List.of(new CaptionCue("m1", 0, 500, "[music]"));
		List<CaptionCue> system = List.of(new CaptionCue("s1", 1_000, 2_000, "system"));
		List<CaptionCue> merged = CaptionMerge.mergeCaptionSources(mic, system);
		assertEquals(2, merged.size());
		assertEquals("[music]", merged.get(0).text());
	}
}
