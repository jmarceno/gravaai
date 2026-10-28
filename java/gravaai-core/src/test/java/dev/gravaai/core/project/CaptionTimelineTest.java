package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code captionTimeline.test.ts}. */
class CaptionTimelineTest {

	private static final List<ClipRegion> CLIPS = List.of(
			new ClipRegion("a", 0, 1000, 0L, null, null, 3, null, null, null, null),
			new ClipRegion("b", 2000, 3000, 6000L, null, null, 3, null, null, null, null));

	private static CaptionCue cue(String id, long startMs, long endMs, String text) {
		return new CaptionCue(id, startMs, endMs, text, null);
	}

	@Test
	@DisplayName("omits deleted captions and splits crossing cues around gaps with distinct identities")
	void splitsCrossingCues() {
		List<CaptionTimeline.Fragment> fragments = CaptionTimeline.projectCaptionCues(
				List.of(cue("deleted", 3100, 5900, "gone"), cue("crossing", 1500, 7500, "kept")),
				CLIPS);
		assertEquals(List.of("500.0:1000.0", "2000.0:2500.0"),
				fragments.stream()
						.map(fragment -> fragment.startMs() + ":" + fragment.endMs())
						.toList());
		assertEquals(List.of("crossing", "crossing"),
				fragments.stream().map(CaptionTimeline.Fragment::sourceCueId).toList());
		assertEquals(2, fragments.stream().map(CaptionTimeline.Fragment::id).distinct().count());
	}

	@Test
	@DisplayName("projects reused footage at every timeline position")
	void projectsReusedFootage() {
		CaptionCue cue = cue("cue", 0, 1500, "hello");
		ClipRegion reused = new ClipRegion("b", 2000, 3000, 0L, null, null, 3, null, null, null, null);
		List<CaptionTimeline.Fragment> fragments = CaptionTimeline.projectCaptionCues(
				List.of(cue), List.of(CLIPS.get(0), reused));
		assertEquals(List.of("0.0:500.0", "2000.0:2500.0"),
				fragments.stream()
						.map(fragment -> fragment.startMs() + ":" + fragment.endMs())
						.toList());
		assertEquals(List.of(), CaptionTimeline.projectCaptionCues(List.of(cue), List.of()));
	}

	@Test
	@DisplayName("retimes within the selected clip even at its cut boundary")
	void retimesWithinClip() {
		assertEquals(new CaptionTimeline.SourceSpan(1500, 3000),
				CaptionTimeline.captionSpanToSource(CLIPS.get(0), 500, 1000));
		assertEquals(new CaptionTimeline.SourceSpan(6000, 9000),
				CaptionTimeline.captionSpanToSource(CLIPS.get(1), 2000, 4000));
	}

	@Test
	@DisplayName("preserves the other fragment when resizing one edge of a crossing cue")
	void preservesOtherFragment() {
		List<CaptionTimeline.Fragment> fragments = CaptionTimeline.projectCaptionCues(
				List.of(cue("cue", 1500, 7500, "hello")), CLIPS);
		assertEquals(new CaptionTimeline.SourceSpan(1800, 7500),
				CaptionTimeline.retimeCaptionFragment(fragments.get(0), 600, 1000));
		assertEquals(new CaptionTimeline.SourceSpan(1500, 7800),
				CaptionTimeline.retimeCaptionFragment(fragments.get(1), 2000, 2600));
		assertTrue(fragments.size() == 2);
	}
}
