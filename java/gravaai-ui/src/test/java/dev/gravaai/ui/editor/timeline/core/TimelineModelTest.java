package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationType;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.ClipCameraLayout;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipTransition;
import dev.gravaai.core.project.ClipTransitionKind;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code timeline/model/timelineModel.test.ts}. */
class TimelineModelTest {

	private static AnnotationRegion annotation(AnnotationType type, String content, Integer trackIndex) {
		return new AnnotationRegion("a1", 200, 1200, type, content, null, null, null, null, null, 0,
				trackIndex, null, null, null);
	}

	private static TimelineModel.ItemsRequest request(List<ZoomRegion> zooms, List<ClipRegion> clips,
			List<AnnotationRegion> annotations, List<AudioRegion> audios) {
		return new TimelineModel.ItemsRequest(zooms, clips, annotations, audios, List.of());
	}

	@Test
	@DisplayName("packs keystroke presses into lanes so overlapping presses stack")
	void packsKeystrokeLanes() {
		List<TimelineRenderItem> items = TimelineModel.buildTimelineItems(
				new TimelineModel.ItemsRequest(List.of(), List.of(), List.of(), List.of(), List.of(),
						java.util.Map.of(), List.of(), List.of(
								new dev.gravaai.core.project.KeystrokeElement("key-1", 0, 900, "S", List.of("ctrl")),
								new dev.gravaai.core.project.KeystrokeElement("key-2", 200, 1100, "A", List.of()),
								new dev.gravaai.core.project.KeystrokeElement("key-3", 1200, 2000, "Z", List.of()))));

		assertEquals("row-keystrokes-0", find(items, "key-1").rowId());
		assertEquals("row-keystrokes-1", find(items, "key-2").rowId());
		assertEquals("row-keystrokes-0", find(items, "key-3").rowId());
		assertEquals("Ctrl + S", find(items, "key-1").label());
		assertEquals(TimelineRenderItem.Variant.KEYSTROKE, find(items, "key-1").variant());
	}

	@Test
	@DisplayName("keeps non-overlapping keystroke presses in the first lane")
	void keepsSequentialKeystrokesInFirstLane() {
		List<TimelineRenderItem> items = TimelineModel.buildTimelineItems(
				new TimelineModel.ItemsRequest(List.of(), List.of(), List.of(), List.of(), List.of(),
						java.util.Map.of(), List.of(), List.of(
								new dev.gravaai.core.project.KeystrokeElement("key-1", 0, 900, "A", List.of()),
								new dev.gravaai.core.project.KeystrokeElement("key-2", 900, 1800, "B", List.of()))));

		assertEquals("row-keystrokes-0", find(items, "key-1").rowId());
		assertEquals("row-keystrokes-0", find(items, "key-2").rowId());
	}

	@Test
	@DisplayName("maps regions to timeline items and labels")
	void mapsRegionsToItems() {
		List<TimelineRenderItem> items = TimelineModel.buildTimelineItems(request(
				List.of(new ZoomRegion("z1", 0, 1000, ZoomDepth.TWO, null, null)),
				List.of(new ClipRegion("c1", 0, 4000, null, null, null, 1.0, null, null, null, null)),
				List.of(annotation(AnnotationType.TEXT, "Hello timeline", 1)),
				List.of(new AudioRegion("au1", 500, 2000, "/tmp/foo.mp3", 1.0, null, 0))));

		assertEquals(4, items.size());
		assertEquals("row-annotation-1", find(items, "a1").rowId());
		assertEquals("foo", find(items, "au1").label());
	}

	@Test
	@DisplayName("labels zoom blocks with their amount, not their index")
	void labelsZoomBlocksWithDepthAmount() {
		assertEquals("1.25x", TimelineModel.formatZoomScale(ZoomDepth.ONE.scale()));
		assertEquals("1.5x", TimelineModel.formatZoomScale(ZoomDepth.TWO.scale()));
		assertEquals("1.8x", TimelineModel.formatZoomScale(ZoomDepth.THREE.scale()));
		assertEquals("2.2x", TimelineModel.formatZoomScale(ZoomDepth.FOUR.scale()));
		assertEquals("3.5x", TimelineModel.formatZoomScale(ZoomDepth.FIVE.scale()));
		assertEquals("5x", TimelineModel.formatZoomScale(ZoomDepth.SIX.scale()));

		List<TimelineRenderItem> items = TimelineModel.buildTimelineItems(request(List.of(
				new ZoomRegion("z1", 0, 1000, ZoomDepth.THREE, null, null),
				new ZoomRegion("z2", 1000, 2000, ZoomDepth.SIX, null, null)),
				List.of(), List.of(), List.of()));
		assertEquals("1.8x", find(items, "z1").label());
		assertEquals("5x", find(items, "z2").label());
	}

	@Test
	@DisplayName("exposes clip speed for non-default speed labels")
	void exposesClipSpeed() {
		List<TimelineRenderItem> items = TimelineModel.buildTimelineItems(request(List.of(),
				List.of(new ClipRegion("c1", 0, 8000, null, null, null, 0.5, null, null, null, null)),
				List.of(), List.of()));

		assertEquals("c1", items.get(0).id());
		assertEquals("Clip 1 0.5x", items.get(0).label());
		assertEquals(0.5, items.get(0).speedValue());
	}

	@Test
	@DisplayName("keeps a split clip's sourceSpan in source coordinates")
	void keepsSplitClipSourceSpan() {
		List<TimelineRenderItem> items = TimelineModel.buildTimelineItems(request(List.of(),
				List.of(new ClipRegion("c1", 10_000, 60_000, 20_000L, null, null, 2.0, null, null, null,
						null)),
				List.of(), List.of()));

		assertEquals(new Span(10_000, 60_000), items.get(0).span());
		assertEquals(new Span(20_000, 120_000), items.get(0).sourceSpan());
	}

	@Test
	@DisplayName("marks the fading part of each clip around a transition")
	void marksFadingPartOfClips() {
		List<TimelineRenderItem> items = TimelineModel.buildTimelineItems(request(List.of(),
				List.of(
						new ClipRegion("c1", 0, 4000, null, null, null, 1.0, null, null, null,
								new ClipTransition(ClipTransitionKind.FADE_BLACK, 1000)),
						new ClipRegion("c2", 4000, 8000, null, null, null, 1.0, null, null, null,
								new ClipTransition(ClipTransitionKind.FADE_BLACK, 600))),
				List.of(), List.of()));

		assertEquals(500, items.get(0).fadeOutMs());
		assertNull(items.get(0).fadeInMs());
		assertEquals(500, items.get(1).fadeInMs());
		assertEquals(600, items.get(1).fadeOutMs());
	}

	@Test
	@DisplayName("ramps the clip after a gap in from black")
	void rampsClipAfterGap() {
		List<TimelineRenderItem> items = TimelineModel.buildTimelineItems(request(List.of(),
				List.of(
						new ClipRegion("c1", 0, 4000, null, null, null, 1.0, null, null, null,
								new ClipTransition(ClipTransitionKind.FADE_BLACK, 1000)),
						new ClipRegion("c2", 5000, 8000, null, null, null, 1.0, null, null, null, null)),
				List.of(), List.of()));

		assertEquals(500, items.get(0).fadeOutMs());
		assertEquals(500, items.get(1).fadeInMs());
	}

	@Test
	@DisplayName("passes a custom camera layout to the clip item, never the default one")
	void passesCustomCameraLayout() {
		List<TimelineRenderItem> items = TimelineModel.buildTimelineItems(request(List.of(),
				List.of(
						new ClipRegion("c1", 0, 4000, null, null, null, 1.0, null, null,
								ClipCameraLayout.CAMERA_ONLY, null),
						new ClipRegion("c2", 4000, 8000, null, null, null, 1.0, null, null,
								ClipCameraLayout.DEFAULT, null),
						new ClipRegion("c3", 8000, 9000, null, null, null, 1.0, null, null, null, null)),
				List.of(), List.of()));

		assertEquals(ClipCameraLayout.CAMERA_ONLY, items.get(0).cameraLayout());
		assertNull(items.get(1).cameraLayout());
		assertNull(items.get(2).cameraLayout());
	}

	@Test
	@DisplayName("builds all variant labels for annotation and audio")
	void buildsVariantLabels() {
		assertEquals("Empty text", TimelineModel.getAnnotationLabel(
				annotation(AnnotationType.TEXT, "   ", null)));
		assertEquals("abcdefghijklmnopqrst...", TimelineModel.getAnnotationLabel(
				annotation(AnnotationType.TEXT, "abcdefghijklmnopqrstuvwxyz", null)));
		assertEquals("Image", TimelineModel.getAnnotationLabel(
				annotation(AnnotationType.IMAGE, "x", null)));
		assertEquals("Annotation", TimelineModel.getAnnotationLabel(
				annotation(AnnotationType.FIGURE, "x", null)));
		assertEquals("z", TimelineModel.getAudioLabel(
				new AudioRegion("1", 0, 1, "C:\\x\\y\\z.wav", 1, null, null)));
		assertEquals("Audio", TimelineModel.getAudioLabel(
				new AudioRegion("2", 0, 1, "", 1, null, null)));
	}

	@Test
	@DisplayName("builds row spans for dnd constraints")
	void buildsRowSpans() {
		List<TimelineRegionSpan> spans = TimelineModel.buildAllRegionSpans(
				new TimelineModel.RegionSpansRequest(null, null,
						List.of(new ZoomRegion("z1", 0, 1000, ZoomDepth.TWO, null, null)),
						List.of(new ClipRegion("c1", 0, 4000, null, null, null, 1.0, null, null, null,
								null)),
						List.of(new AudioRegion("au1", 500, 2000, "x.wav", 1, null, 2))));

		assertEquals(List.of("row-zoom", "row-clip", "row-audio-2"),
				spans.stream().map(TimelineRegionSpan::rowId).toList());
	}

	@Test
	@DisplayName("keeps items in their domain rows during dnd")
	void keepsItemsInDomainRows() {
		List<TimelineRenderItem> items = List.of(
				new TimelineRenderItem("a1", "row-annotation-1", new Span(0, 1), null, "A", null, null,
						null, null, null, null, null, null, null, null, null,
						TimelineRenderItem.Variant.ANNOTATION),
				new TimelineRenderItem("au1", "row-audio-2", new Span(0, 1), null, "X", null, null, null,
						null, null, null, null, null, null, null, null,
						TimelineRenderItem.Variant.AUDIO),
				new TimelineRenderItem("z1", TimelineConstants.ZOOM_ROW_ID, new Span(0, 1), null, "Z",
						null, null, null, null, null, null, null, null, null, null, null,
						TimelineRenderItem.Variant.ZOOM));

		assertEquals("row-annotation-1", TimelineModel.resolveDropRowId("a1", "row-audio-0", items));
		assertEquals("row-annotation-3", TimelineModel.resolveDropRowId("a1", "row-annotation-3", items));
		assertEquals("row-audio-2", TimelineModel.resolveDropRowId("au1", "row-annotation-1", items));
		assertEquals("row-audio-7", TimelineModel.resolveDropRowId("au1", "row-audio-7", items));
		assertEquals(TimelineConstants.ZOOM_ROW_ID,
				TimelineModel.resolveDropRowId("z1", "row-audio-1", items));
		assertEquals("row-audio-1", TimelineModel.resolveDropRowId("unknown", "row-audio-1", items));
	}

	private static TimelineRenderItem find(List<TimelineRenderItem> items, String id) {
		return items.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
	}
}
