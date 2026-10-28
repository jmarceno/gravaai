package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of {@code timeline/core/clipPresentation.test.ts}. */
class ClipPresentationTest {

	private static final double EPSILON = 1e-9;

	private static final List<ClipPresentation.Presentation> SPLIT_CLIPS = List.of(
			new ClipPresentation.Presentation(new Span(0, 1000), new Span(0, 900)),
			new ClipPresentation.Presentation(new Span(1000, 2000), new Span(1100, 2000)));

	@ParameterizedTest(name = "at {0} ms/px")
	@ValueSource(doubles = {1, 10, 100})
	@DisplayName("reserves a 24px seam without changing media spans at %s ms/px")
	void reservesSeam(double msPerPixel) {
		List<Span> clips = List.of(new Span(0, 10000), new Span(10000, 20000));
		Span left = ClipPresentation.getClipDisplaySpan(clips.get(0), clips, msPerPixel);
		Span right = ClipPresentation.getClipDisplaySpan(clips.get(1), clips, msPerPixel);

		assertEquals(24, (right.start() - left.end()) / msPerPixel, EPSILON);
		assertEquals(0, left.start());
		assertEquals(20000, right.end());
		assertEquals(10000, clips.get(0).end());
		assertEquals(10000, clips.get(1).start());
	}

	@Test
	@DisplayName("does not inset a single clip and leaves tiny clips visible")
	void doesNotInsetSingleClip() {
		Span span = new Span(0, 10);
		assertEquals(span, ClipPresentation.getClipDisplaySpan(span, List.of(span), 20));
		Span tiny = ClipPresentation.getClipDisplaySpan(span,
				List.of(span, new Span(10, 20)), 20);
		assertTrue(tiny.end() - tiny.start() > 0);
	}

	@Test
	@DisplayName("clips caption previews to the owning filmstrip, excluding its gutter")
	void clipsCaptionPreviews() {
		List<ClipPresentation.Presentation> clips = SPLIT_CLIPS;
		assertEquals(new Span(720, 900),
				ClipPresentation.getEmbeddedCaptionSpan(new Span(800, 1000), clips));
		assertEquals(new Span(1100, 1280),
				ClipPresentation.getEmbeddedCaptionSpan(new Span(1000, 1200), clips));
		assertNull(ClipPresentation.getEmbeddedCaptionSpan(new Span(2100, 2200), clips));
	}

	@Test
	@DisplayName("jumps the playhead across a split gutter at the cut")
	void jumpsPlayheadAcrossSplitGutter() {
		assertEquals(450, ClipPresentation.getPlayheadDisplayTime(500, SPLIT_CLIPS), EPSILON);
		assertEquals(899.1, ClipPresentation.getPlayheadDisplayTime(999, SPLIT_CLIPS), EPSILON);
		assertEquals(1100, ClipPresentation.getPlayheadDisplayTime(1000, SPLIT_CLIPS), EPSILON);
		assertEquals(1100.9, ClipPresentation.getPlayheadDisplayTime(1001, SPLIT_CLIPS), EPSILON);
		assertEquals(2000, ClipPresentation.getPlayheadDisplayTime(2000, SPLIT_CLIPS), EPSILON);
	}

	@Test
	@DisplayName("seeks to the cut from either side of a decorative gutter")
	void seeksToCutFromEitherSide() {
		for (double time : new double[] {900, 950, 1000, 1050, 1100}) {
			assertEquals(1000, ClipPresentation.getTimeAtClipSeam(time, SPLIT_CLIPS), EPSILON);
		}
		assertEquals(700 / 0.9, ClipPresentation.getTimeAtClipSeam(700, SPLIT_CLIPS), EPSILON);
		assertEquals(700, ClipPresentation.getPlayheadDisplayTime(700, List.of()), EPSILON);
	}

	@Test
	@DisplayName("preserves authored empty timeline time")
	void preservesAuthoredEmptyTimelineTime() {
		List<ClipPresentation.Presentation> clips = List.of(SPLIT_CLIPS.get(0),
				new ClipPresentation.Presentation(new Span(1500, 2500), new Span(1600, 2500)));
		assertEquals(1200, ClipPresentation.getTimeAtClipSeam(1200, clips), EPSILON);
		assertEquals(1200, ClipPresentation.getPlayheadDisplayTime(1200, clips), EPSILON);
	}

	@Test
	@DisplayName("round-trips all media time without a frozen playhead at either side of the cut")
	void roundTripsAllMediaTime() {
		for (double time = 0; time <= 2000; time += 7) {
			assertEquals(time,
					ClipPresentation.getTimeAtClipSeam(
							ClipPresentation.getPlayheadDisplayTime(time, SPLIT_CLIPS), SPLIT_CLIPS),
					1e-6);
		}
	}

	@Test
	@DisplayName("aligns effect endpoints with opposite sides of a zero-duration seam")
	void alignsEffectEndpoints() {
		assertEquals(new Span(450, 900),
				ClipPresentation.getRegionDisplaySpan(new Span(500, 1000), SPLIT_CLIPS));
		assertEquals(new Span(1100, 1550),
				ClipPresentation.getRegionDisplaySpan(new Span(1000, 1500), SPLIT_CLIPS));
	}

	@ParameterizedTest(name = "at {0} ms/px")
	@ValueSource(doubles = {1, 10, 50})
	@DisplayName("soft-snaps within eight screen pixels at %s ms/px and releases outside")
	void softSnapsWithinEightPixels(double msPerPixel) {
		List<Span> clips = List.of(new Span(0, 10000), new Span(10000, 20000));
		List<ClipPresentation.Presentation> presentation = clips.stream()
				.map(span -> new ClipPresentation.Presentation(span,
						ClipPresentation.getClipDisplaySpan(span, clips, msPerPixel)))
				.toList();
		double edge = presentation.get(0).displaySpan().end();
		double near = ClipPresentation.getTimeAtClipSeam(edge - 5 * msPerPixel, presentation);
		double far = ClipPresentation.getTimeAtClipSeam(edge - 12 * msPerPixel, presentation);

		assertEquals(new Span(1000, 10000), ClipPresentation.snapRegionSpan(
				new Span(1000, near), List.of(10000.0), presentation, msPerPixel,
				ClipPresentation.Edge.END));
		assertEquals(far, ClipPresentation.snapRegionSpan(
				new Span(1000, far), List.of(10000.0), presentation, msPerPixel,
				ClipPresentation.Edge.END).end(), EPSILON);
		Span dragged = ClipPresentation.snapRegionSpan(new Span(near - 2000, near),
				List.of(10000.0), presentation, msPerPixel);
		assertEquals(new Span(8000, 10000), dragged);
	}

	@Test
	@DisplayName("snaps to other block edges and preserves drag duration")
	void snapsToOtherBlockEdges() {
		assertEquals(new Span(1500, 1800),
				ClipPresentation.snapRegionSpan(new Span(1510, 1810), List.of(1500.0), List.of(), 2));
		assertEquals(new Span(1550, 1850),
				ClipPresentation.snapRegionSpan(new Span(1550, 1850), List.of(1500.0), List.of(), 2));
	}
}
