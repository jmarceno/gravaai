package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.ZoomFocus;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils.CursorTelemetryPoint;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils.SuggestionResult;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils.SuggestionStatus;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils.SuggestedZoomRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code zoomSuggestionUtils.test.ts} with the same cases and expected values. */
class ZoomSuggestionUtilsTest {

	private static final double TOTAL_MS = 30_000;

	private static CursorTelemetryPoint makeClick(double timeMs) {
		return makeClick(timeMs, 0.5, 0.5, "click");
	}

	private static CursorTelemetryPoint makeClick(double timeMs, double cx, double cy) {
		return new CursorTelemetryPoint(timeMs, cx, cy, "click", null);
	}

	private static CursorTelemetryPoint makeClick(double timeMs, double cx, double cy,
			String interactionType) {
		return new CursorTelemetryPoint(timeMs, cx, cy, interactionType, null);
	}

	private static CursorTelemetryPoint makeMouseUp(double timeMs, double cx, double cy) {
		return new CursorTelemetryPoint(timeMs, cx, cy, "mouseup", null);
	}

	private static CursorTelemetryPoint makeMove(double timeMs, double cx, double cy) {
		return new CursorTelemetryPoint(timeMs, cx, cy, "move", null);
	}

	/** Wraps click samples with surrounding move events to mimic real mixed telemetry. */
	private static List<CursorTelemetryPoint> withMoves(List<CursorTelemetryPoint> clicks,
			double totalMs) {
		List<CursorTelemetryPoint> samples = new ArrayList<>();
		samples.add(makeMove(0, 0.5, 0.5));
		samples.addAll(clicks);
		samples.add(makeMove(totalMs, 0.5, 0.5));
		return samples;
	}

	private static SuggestionResult suggest(List<CursorTelemetryPoint> telemetry, double totalMs) {
		return ZoomSuggestionUtils.buildInteractionZoomSuggestions(
				new ZoomSuggestionUtils.SuggestionParams(telemetry, totalMs, 3_000));
	}

	@Test
	@DisplayName("allows automatic fresh-recording zooms for landscape captures")
	void allowsLandscapeCaptures() {
		assertTrue(ZoomSuggestionUtils.shouldAutoApplyFreshRecordingZoomsForSource(1920, 1080));
		assertTrue(ZoomSuggestionUtils.shouldAutoApplyFreshRecordingZoomsForSource(1280, 960));
	}

	@Test
	@DisplayName("blocks automatic fresh-recording zooms for narrow or near-square captures")
	void blocksNarrowCaptures() {
		assertFalse(ZoomSuggestionUtils.shouldAutoApplyFreshRecordingZoomsForSource(960, 1020));
		assertFalse(ZoomSuggestionUtils.shouldAutoApplyFreshRecordingZoomsForSource(1080, 1080));
	}

	@Test
	@DisplayName("does not block when source dimensions are not available yet")
	void allowsMissingDimensions() {
		assertTrue(ZoomSuggestionUtils.shouldAutoApplyFreshRecordingZoomsForSource(null, null));
	}

	@Test
	@DisplayName("creates one zoom track for a single isolated click with the cluster padding")
	void createsOneTrackForSingleClick() {
		SuggestionResult result = suggest(withMoves(List.of(makeClick(5_000)), TOTAL_MS), TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(1, result.suggestions().size());
		SuggestedZoomRegion suggestion = result.suggestions().get(0);
		assertEquals(5_000 - ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, suggestion.startMs());
		assertEquals(5_000 + ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, suggestion.endMs());
	}

	@Test
	@DisplayName("accepts a single explicit click sample without needing surrounding moves")
	void acceptsSingleExplicitClick() {
		SuggestionResult result = suggest(List.of(makeClick(5_000)), TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(1, result.suggestions().size());
	}

	@Test
	@DisplayName("accepts right-click telemetry like a standard click")
	void acceptsRightClick() {
		SuggestionResult result = suggest(withMoves(
				List.of(makeClick(5_000, 0.5, 0.5, "right-click")), TOTAL_MS), TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(1, result.suggestions().size());
		SuggestedZoomRegion suggestion = result.suggestions().get(0);
		assertEquals(5_000 - ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, suggestion.startMs());
		assertEquals(5_000 + ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, suggestion.endMs());
	}

	@Test
	@DisplayName("accepts middle-click telemetry like a standard click")
	void acceptsMiddleClick() {
		SuggestionResult result = suggest(withMoves(
				List.of(makeClick(5_000, 0.5, 0.5, "middle-click")), TOTAL_MS), TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(1, result.suggestions().size());
	}

	@Test
	@DisplayName("merges two clicks within the merge gap into one zoom track")
	void mergesClicksWithinGap() {
		double lastClickMs = 4_000 + ZoomSuggestionUtils.CLICK_CLUSTER_MERGE_GAP_MS - 1;
		SuggestionResult result = suggest(withMoves(
				List.of(makeClick(4_000), makeClick(lastClickMs)), TOTAL_MS), TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(1, result.suggestions().size());
		SuggestedZoomRegion suggestion = result.suggestions().get(0);
		assertEquals(4_000 - ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, suggestion.startMs());
		assertEquals(lastClickMs + ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, suggestion.endMs());
	}

	@Test
	@DisplayName("splits two clicks more than the merge gap apart into separate zoom tracks")
	void splitsClicksOutsideGap() {
		double click1 = 3_000;
		double click2 = 3_000 + ZoomSuggestionUtils.CLICK_CLUSTER_MERGE_GAP_MS + 1;
		SuggestionResult result = suggest(withMoves(
				List.of(makeClick(click1), makeClick(click2)), TOTAL_MS), TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(2, result.suggestions().size());
		SuggestedZoomRegion a = result.suggestions().get(0);
		SuggestedZoomRegion b = result.suggestions().get(1);
		assertEquals(click1 - ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, a.startMs());
		assertEquals(click1 + ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, a.endMs());
		assertEquals(click2 - ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, b.startMs());
		assertEquals(click2 + ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, b.endMs());
	}

	@Test
	@DisplayName("chains multiple clicks: 3 in a row within the merge gap become one track")
	void chainsClicks() {
		SuggestionResult result = suggest(withMoves(
				List.of(makeClick(0), makeClick(2_000), makeClick(4_000)), TOTAL_MS), TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(1, result.suggestions().size());
		SuggestedZoomRegion suggestion = result.suggestions().get(0);
		assertEquals(0, suggestion.startMs());
		assertEquals(4_000 + ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS, suggestion.endMs());
	}

	@Test
	@DisplayName("returns no-interactions when there are no click telemetry points")
	void returnsNoInteractions() {
		List<CursorTelemetryPoint> telemetry = List.of(makeMove(0, 0.5, 0.5),
				makeMove(1_000, 0.5, 0.5), makeMove(2_000, 0.6, 0.6),
				makeMove(TOTAL_MS, 0.6, 0.6));

		SuggestionResult result = suggest(telemetry, TOTAL_MS);

		assertEquals(SuggestionStatus.NO_INTERACTIONS, result.status());
		assertEquals(0, result.suggestions().size());
	}

	@Test
	@DisplayName("ignores dwell-derived click-like heuristics when there are no explicit clicks")
	void ignoresDwellHeuristics() {
		List<CursorTelemetryPoint> telemetry = List.of(makeMove(0, 0.5, 0.5),
				makeMove(200, 0.5005, 0.5005), makeMove(400, 0.5008, 0.5008),
				makeMove(600, 0.501, 0.501));

		SuggestionResult result = suggest(telemetry, TOTAL_MS);

		assertEquals(SuggestionStatus.NO_INTERACTIONS, result.status());
		assertEquals(0, result.suggestions().size());
	}

	@Test
	@DisplayName("skips clusters that overlap reserved spans")
	void skipsReservedSpans() {
		SuggestionResult result = ZoomSuggestionUtils.buildInteractionZoomSuggestions(
				new ZoomSuggestionUtils.SuggestionParams(
						withMoves(List.of(makeClick(5_000)), TOTAL_MS), TOTAL_MS, 3_000,
						List.of(new Span(4_000, 6_000))));

		assertEquals(SuggestionStatus.NO_SLOTS, result.status());
		assertEquals(0, result.suggestions().size());
	}

	@Test
	@DisplayName("clamps start to 0 and end to totalMs at video boundaries")
	void clampsBoundaries() {
		SuggestionResult result = suggest(withMoves(List.of(makeClick(200)), 1_000), 1_000);

		assertEquals(SuggestionStatus.OK, result.status());
		SuggestedZoomRegion suggestion = result.suggestions().get(0);
		assertTrue(suggestion.startMs() >= 0);
		assertTrue(suggestion.endMs() <= 1_000);
	}

	@Test
	@DisplayName("keeps the click focus as the suggested zoom focus")
	void keepsClickFocus() {
		SuggestionResult result = suggest(withMoves(List.of(makeClick(5_000, 0.25, 0.75)),
				TOTAL_MS), TOTAL_MS);

		ZoomFocus focus = result.suggestions().get(0).focus();
		assertEquals(0.25, focus.cx());
		assertEquals(0.75, focus.cy());
	}

	// ---- hold-to-loupe (the "Magnifying Glass" card) -------------------------------

	@Test
	@DisplayName("places a loupe over a press held longer than 200 ms instead of a zoom")
	void placesLoupeForHold() {
		SuggestionResult result = suggest(
				withMoves(List.of(makeClick(5_000), makeMouseUp(5_600, 0.5, 0.5)), TOTAL_MS),
				TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(0, result.suggestions().size());
		assertEquals(1, result.loupeSuggestions().size());
		ZoomSuggestionUtils.SuggestedLoupeRegion loupe = result.loupeSuggestions().get(0);
		// A lead-in before the press grows the lens in; a tail after the release lets it linger.
		assertEquals(5_000 - ZoomSuggestionUtils.LOUPE_LEAD_MS, loupe.startMs());
		assertEquals(5_600 + ZoomSuggestionUtils.LOUPE_TAIL_MS, loupe.endMs());
	}

	@Test
	@DisplayName("keeps a quick press-and-release as a click zoom")
	void keepsQuickClickAsZoom() {
		SuggestionResult result = suggest(
				withMoves(List.of(makeClick(5_000), makeMouseUp(5_100, 0.5, 0.5)), TOTAL_MS),
				TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(1, result.suggestions().size());
		assertEquals(0, result.loupeSuggestions().size());
	}

	@Test
	@DisplayName("a hold of exactly 200 ms is still a zoom (the rule is more than 200 ms)")
	void treatsExactThresholdAsZoom() {
		SuggestionResult result = suggest(
				withMoves(List.of(makeClick(5_000), makeMouseUp(5_200, 0.5, 0.5)), TOTAL_MS),
				TOTAL_MS);

		assertEquals(1, result.suggestions().size());
		assertEquals(0, result.loupeSuggestions().size());
	}

	@Test
	@DisplayName("a press without a mouseup cannot be measured and stays a zoom")
	void keepsClickWithoutMouseUpAsZoom() {
		SuggestionResult result = suggest(withMoves(List.of(makeClick(5_000)), TOTAL_MS), TOTAL_MS);

		assertEquals(1, result.suggestions().size());
		assertEquals(0, result.loupeSuggestions().size());
	}

	@Test
	@DisplayName("mixes quick-click zooms with held-press loupes in one result")
	void mixesZoomsAndLoupes() {
		SuggestionResult result = suggest(withMoves(List.of(
				makeClick(4_000), makeMouseUp(4_080, 0.5, 0.5),
				makeClick(12_000), makeMouseUp(13_000, 0.4, 0.6)), TOTAL_MS), TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(1, result.suggestions().size());
		assertEquals(4_000 - ZoomSuggestionUtils.CLICK_CLUSTER_PAD_MS,
				result.suggestions().get(0).startMs());
		assertEquals(1, result.loupeSuggestions().size());
		assertEquals(12_000 - ZoomSuggestionUtils.LOUPE_LEAD_MS,
				result.loupeSuggestions().get(0).startMs());
		assertEquals(13_000 + ZoomSuggestionUtils.LOUPE_TAIL_MS,
				result.loupeSuggestions().get(0).endMs());
	}

	@Test
	@DisplayName("returns ok with only loupes when every press is a hold")
	void returnsOnlyLoupesWhenAllHolds() {
		SuggestionResult result = suggest(
				withMoves(List.of(makeClick(5_000), makeMouseUp(5_800, 0.5, 0.5),
						makeClick(10_000), makeMouseUp(10_900, 0.6, 0.6)), TOTAL_MS), TOTAL_MS);

		assertEquals(SuggestionStatus.OK, result.status());
		assertEquals(0, result.suggestions().size());
		assertEquals(2, result.loupeSuggestions().size());
	}

	@Test
	@DisplayName("detects hold spans straight from a telemetry stream")
	void detectsHoldSpans() {
		List<ZoomSuggestionUtils.SuggestedLoupeRegion> holds = ZoomSuggestionUtils.detectHoldSpans(
				List.of(makeClick(100), makeMouseUp(500, 0.5, 0.5),
						makeClick(1_000), makeMouseUp(1_100, 0.5, 0.5)));

		assertEquals(1, holds.size());
		assertEquals(100, holds.get(0).startMs());
		assertEquals(500, holds.get(0).endMs());
	}

	@Test
	@DisplayName("a press without a recorded mouseup never borrows a later press's release")
	void pressWithoutMouseUpDoesNotPairWithLaterRelease() {
		// The 1 s press lost its mouseup; the 30 s quick click's release must not turn it into
		// a 29 s loupe.
		List<ZoomSuggestionUtils.SuggestedLoupeRegion> holds = ZoomSuggestionUtils.detectHoldSpans(
				List.of(makeClick(1_000), makeClick(30_000), makeMouseUp(30_080, 0.5, 0.5)));

		assertEquals(0, holds.size());
	}

	@Test
	@DisplayName("a short hold still shows the lens for the minimum duration")
	void shortHoldGetsMinimumLoupeDuration() {
		SuggestionResult result = suggest(
				withMoves(List.of(makeClick(5_000), makeMouseUp(5_250, 0.5, 0.5)), TOTAL_MS),
				TOTAL_MS);

		assertEquals(1, result.loupeSuggestions().size());
		ZoomSuggestionUtils.SuggestedLoupeRegion loupe = result.loupeSuggestions().get(0);
		assertEquals(ZoomSuggestionUtils.LOUPE_MIN_MS, loupe.endMs() - loupe.startMs(), 0.001);
		assertEquals(0, result.suggestions().size(), "the hold is not also a click zoom");
	}
}
