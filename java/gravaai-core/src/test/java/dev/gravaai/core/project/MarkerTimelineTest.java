package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link MarkerTimeline} + {@link Chapters}: source-time markers onto the edited timeline. */
class MarkerTimelineTest {

	/**
	 * Clip a keeps source 0–1000 at 1x on timeline 0–1000; clip b keeps source 5000–6000 at 2x on
	 * timeline 2000–2500. Source 1000–5000 was cut away.
	 */
	private static final List<ClipRegion> CLIPS = List.of(
			new ClipRegion("a", 0, 1000, 0L, null, null, 1, null, null, null, null),
			new ClipRegion("b", 2000, 2500, 5000L, null, null, 2, null, null, null, null));

	private static TimelineMarker marker(String id, long sourceMs, String name, boolean chapter) {
		return new TimelineMarker(id, sourceMs, name, chapter);
	}

	@Test
	@DisplayName("a marker rides its footage: clip speed decides where it lands on the timeline")
	void projectsThroughClipSpeed() {
		List<MarkerTimeline.Fragment> fragments = MarkerTimeline.projectMarkers(List.of(
				marker("first", 500, "First", false),
				marker("second", 5500, "Second", false)), CLIPS);
		assertEquals(List.of("first", "second"),
				fragments.stream().map(fragment -> fragment.marker().id()).toList());
		assertEquals(List.of("a", "b"),
				fragments.stream().map(fragment -> fragment.clip().id()).toList());
		// 500 ms into clip a (1x) → 500; 500 ms into clip b (2x) → 2000 + 250.
		assertEquals(List.of(500L, 2250L),
				fragments.stream().map(MarkerTimeline.Fragment::timelineMs).toList());
	}

	@Test
	@DisplayName("a marker on trimmed-away footage is hidden, not snapped to a clip edge")
	void hidesTrimmedMarkers() {
		// Source 2500–3500 is not in any clip.
		assertEquals(List.of(), MarkerTimeline.projectMarkers(
				List.of(marker("gone", 3000, "Cut", false)), CLIPS));
		assertTrue(MarkerTimeline.timelineMs(marker("gone", 3000, "Cut", false), CLIPS).isEmpty());
	}

	@Test
	@DisplayName("a marker on the last frame of a clip belongs to that clip")
	void lastFrameBoundary() {
		TimelineMarker end = marker("end", 1000, "End", false);
		assertEquals(java.util.OptionalLong.of(1000L), MarkerTimeline.timelineMs(end, List.of(CLIPS.get(0))));
		assertEquals(java.util.OptionalLong.of(2500L), MarkerTimeline.timelineMs(
				marker("end2", 6000, "End", false), List.of(CLIPS.get(1))));
	}

	@Test
	@DisplayName("fragments come back in timeline order regardless of the source order")
	void ordersByTimeline() {
		List<MarkerTimeline.Fragment> fragments = MarkerTimeline.projectMarkers(List.of(
				marker("late", 5500, "Late", false),
				marker("early", 0, "Early", false)), CLIPS);
		assertEquals(List.of("Early", "Late"),
				fragments.stream().map(fragment -> fragment.marker().name()).toList());
	}

	@Test
	@DisplayName("chapters collapse duplicate timestamps and ignore plain markers")
	void chapterList() {
		List<Chapters.Chapter> chapters = Chapters.build(List.of(
				marker("start", 0, "Start", true),
				marker("dupe", 0, "Same", true),
				marker("later", 500, "Later", true),
				marker("plain", 250, "Not a chapter", false)), CLIPS, 3000);
		assertEquals(List.of(
				new Chapters.Chapter(0, "Start"),
				new Chapters.Chapter(500, "Later")), chapters);
	}

	@Test
	@DisplayName("a chapter on trimmed footage does not appear in the list")
	void chapterOnCutFootage() {
		assertEquals(List.of(), Chapters.build(
				List.of(marker("gone", 3000, "Gone", true)), CLIPS, 3000));
	}

	@Test
	@DisplayName("a chapter list starting after 00:00 gets the implicit Intro chapter")
	void insertsIntro() {
		List<Chapters.Chapter> chapters = Chapters.build(
				List.of(marker("a", 500, "Middle", true)), CLIPS, 3000);
		assertEquals(List.of(
				new Chapters.Chapter(0, Chapters.INTRO_TITLE),
				new Chapters.Chapter(500, "Middle")), chapters);
	}

	@Test
	@DisplayName("a chapter starting at 00:00 does not get an Intro in front of it")
	void noIntroWhenFirstIsAtZero() {
		List<Chapters.Chapter> chapters = Chapters.build(
				List.of(marker("a", 0, "Opening", true)), CLIPS, 3000);
		assertEquals(List.of(new Chapters.Chapter(0, "Opening")), chapters);
	}

	@Test
	@DisplayName("no chapters for markers that were never promoted")
	void noChapters() {
		assertEquals(List.of(), Chapters.build(
				List.of(marker("a", 0, "Marker 1", false)), CLIPS, 3000));
	}

	@Test
	@DisplayName("chapter timestamps are MM:SS and gain an hour past 60 minutes")
	void timestamps() {
		assertEquals("00:00", Chapters.timestamp(0));
		assertEquals("01:05", Chapters.timestamp(65_400));
		assertEquals("1:00:00", Chapters.timestamp(3_600_000));
	}

	@Test
	@DisplayName("the sidecar text is one timestamped line per chapter, starting at 00:00")
	void sidecarText() {
		String text = Chapters.sidecarText(List.of(
				new Chapters.Chapter(0, "Intro"),
				new Chapters.Chapter(65_000, "Demo")));
		assertEquals("00:00 Intro\n01:05 Demo\n", text);
		assertTrue(text.startsWith("00:00 "));
	}

	@Test
	@DisplayName("marker names are trimmed, collapsed and never blank; long ones are clamped")
	void names() {
		assertEquals("Hello World", TimelineMarker.cleanName("  Hello   World  "));
		assertEquals("Marker", TimelineMarker.cleanName("   "));
		assertEquals("Marker", TimelineMarker.cleanName(null));
		assertEquals("Marker 3", TimelineMarker.defaultName(3));
		String longName = "x".repeat(TimelineMarker.MAX_NAME_LENGTH + 20);
		assertEquals(TimelineMarker.MAX_NAME_LENGTH,
				new TimelineMarker("m", 0, longName, false).name().length());
	}

	@Test
	@DisplayName("a marker keeps its identity through retime, rename and chapter toggles")
	void immutableUpdates() {
		TimelineMarker marker = marker("m", 100, "Name", false);
		assertEquals(marker("m", 900, "Name", false), marker.withStartMs(900));
		assertEquals(marker("m", 100, "Other", false), marker.withName("Other"));
		assertEquals(marker("m", 100, "Name", true), marker.withChapter(true));
		assertFalse(marker.withChapter(true).equals(marker));
	}
}
