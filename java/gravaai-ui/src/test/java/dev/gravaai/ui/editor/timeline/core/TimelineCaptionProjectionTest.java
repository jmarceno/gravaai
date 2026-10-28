package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.ClipRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code captionTimeline.test.ts} at the timeline-model layer. */
class TimelineCaptionProjectionTest {

	private static final String CLIPS = """
			[
			  {"id":"a","startMs":0,"endMs":1000,"sourceStartMs":0,"speed":3},
			  {"id":"b","startMs":2000,"endMs":3000,"sourceStartMs":6000,"speed":3}
			]
			""";

	private static List<TimelineRenderItem> captionItems(List<ClipRegion> clips,
			List<CaptionCue> cues) {
		return TimelineModel.buildTimelineItems(new TimelineModel.ItemsRequest(List.of(), clips,
				List.of(), List.of(), cues)).stream()
				.filter(item -> item.variant() == TimelineRenderItem.Variant.CAPTION).toList();
	}

	private static ClipRegion clip(String id, long startMs, long endMs, long sourceStartMs,
			double speed) {
		return new ClipRegion(id, startMs, endMs, sourceStartMs, null, null, speed, null, null,
				null, null);
	}

	private static CaptionCue cue(String id, long startMs, long endMs, String text) {
		return new CaptionCue(id, startMs, endMs, text, null);
	}

	@Test
	@DisplayName("omits deleted captions and splits crossing cues around gaps with distinct identities")
	void splitsCrossingCues() {
		List<ClipRegion> clips = List.of(clip("a", 0, 1000, 0, 3), clip("b", 2000, 3000, 6000, 3));
		List<TimelineRenderItem> items = captionItems(clips, List.of(cue("deleted", 3100, 5900, "gone"),
				cue("crossing", 1500, 7500, "kept")));

		assertEquals(2, items.size());
		assertEquals(500, items.get(0).span().start(), 0.001);
		assertEquals(1000, items.get(0).span().end(), 0.001);
		assertEquals(2000, items.get(1).span().start(), 0.001);
		assertEquals(2500, items.get(1).span().end(), 0.001);
		assertEquals(2, items.stream().map(TimelineRenderItem::id).distinct().count());
		assertEquals("kept", items.get(0).label());
	}

	@Test
	@DisplayName("projects reused footage at every timeline position")
	void projectsReusedFootage() {
		List<TimelineRenderItem> items = captionItems(
				List.of(clip("a", 0, 1000, 0, 3), clip("b", 2000, 3000, 0, 3)),
				List.of(cue("cue", 0, 1500, "hello")));

		assertEquals(2, items.size());
		assertEquals(0, items.get(0).span().start(), 0.001);
		assertEquals(500, items.get(0).span().end(), 0.001);
		assertEquals(2000, items.get(1).span().start(), 0.001);
		assertEquals(2500, items.get(1).span().end(), 0.001);
	}

	@Test
	@DisplayName("renders no caption blocks when the timeline has no clips")
	void rendersNothingWithoutClips() {
		List<TimelineRenderItem> items = captionItems(List.of(),
				List.of(cue("cue", 0, 1500, "hello")));

		assertTrue(items.isEmpty());
	}
}
