package dev.gravaai.ui.editor.timeline.core;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dev.gravaai.core.project.ZoomFocus;

/**
 * Cursor-telemetry zoom suggestions (port of {@code timeline/zoomSuggestionUtils.ts}): explicit
 * click clusters become suggested zoom regions, and fresh recordings are only auto-zoomed for
 * eligible captures.
 */
public final class ZoomSuggestionUtils {

	public static final double MIN_DWELL_DURATION_MS = 450;
	public static final double MAX_DWELL_DURATION_MS = 2600;
	public static final double DWELL_MOVE_THRESHOLD = 0.02;
	public static final double MIN_FRESH_RECORDING_AUTO_ZOOM_SOURCE_ASPECT_RATIO = 1.2;
	/**
	 * Max gap between consecutive clicks before they are split into separate zoom clusters. Twice
	 * the pad, so the padded regions of split clusters never overlap (overlapping suggestions are
	 * dropped against the reserved spans).
	 */
	public static final double CLICK_CLUSTER_MERGE_GAP_MS = 3000;
	/**
	 * Padding added before the first click and after the last click in a cluster. The zoom-in
	 * transition runs ≈1522 ms from the region start, so 1500 ms lands the zoom exactly on the
	 * click and holds it ≈1.5 s before the ease out — short pads make the in/out overlap and whip
	 * (motion-sickness jarring).
	 */
	public static final double CLICK_CLUSTER_PAD_MS = 1500;
	/**
	 * A press held longer than this is a deliberate "show this detail" gesture: it suggests a
	 * magnifying-glass loupe over the hold instead of a click zoom (the card's 200 ms rule).
	 */
	public static final double HOLD_LOUPE_MIN_MS = 200;
	/**
	 * A suggested loupe starts this long before the press, so the lens has grown in by the time
	 * the button goes down, and lingers this long after the release so it does not vanish the
	 * instant the button comes up.
	 */
	public static final double LOUPE_LEAD_MS = 120;
	public static final double LOUPE_TAIL_MS = 350;
	/** A suggested loupe is on screen at least this long: a 250 ms hold must not flash a lens. */
	public static final double LOUPE_MIN_MS = 1_000;

	/**
	 * Port of {@code CursorTelemetryPoint}; {@code interactionType} and {@code cursorType} are null
	 * for position-only samples (the media {@code CursorSample} documents the same absence).
	 */
	public record CursorTelemetryPoint(double timeMs, double cx, double cy,
			@Nullable String interactionType, @Nullable String cursorType) {
	}

	/** Port of {@code ZoomDwellCandidate}. */
	public record ZoomDwellCandidate(double centerTimeMs, ZoomFocus focus, double strength) {
	}

	/** Port of {@code CursorInteractionCandidate["kind"]}. */
	public enum InteractionKind {
		DWELL,
		CLICK_LIKE,
		DOUBLE_CLICK_LIKE,
		TEXT_FOCUS_LIKE,
		DROPDOWN_OPEN,
		TEXT_SELECTION,
		TEXT_FIELD_CLICK
	}

	public enum InteractionSource {
		EXPLICIT,
		HEURISTIC
	}

	/** Port of {@code CursorInteractionCandidate}. */
	public record CursorInteractionCandidate(double centerTimeMs, ZoomFocus focus, double strength,
			InteractionKind kind, InteractionSource source) {
	}

	/** Port of {@code SuggestedZoomRegion}. */
	public record SuggestedZoomRegion(double startMs, double endMs, ZoomFocus focus) {
	}

	/**
	 * A press-and-hold span: the magnifying glass is placed over the held mouse position (the
	 * lens follows the cursor at render time, so no focus is needed).
	 */
	public record SuggestedLoupeRegion(double startMs, double endMs) {
	}

	public enum SuggestionStatus {
		OK,
		NO_TELEMETRY,
		NO_INTERACTIONS,
		NO_SLOTS
	}

	/** Port of {@code InteractionZoomSuggestionResult}, extended with the loupe placements. */
	public record SuggestionResult(SuggestionStatus status, List<SuggestedZoomRegion> suggestions,
			List<SuggestedLoupeRegion> loupeSuggestions) {

		public SuggestionResult(SuggestionStatus status, List<SuggestedZoomRegion> suggestions) {
			this(status, suggestions, List.of());
		}

		public SuggestionResult {
			suggestions = suggestions == null ? List.of() : suggestions;
			loupeSuggestions = loupeSuggestions == null ? List.of() : loupeSuggestions;
		}
	}

	/** Port of the {@code buildInteractionZoomSuggestions} params object. */
	public record SuggestionParams(List<CursorTelemetryPoint> cursorTelemetry, double totalMs,
			double defaultDurationMs, List<Span> reservedSpans, double spacingMs, double mergeGapMs,
			double padMs) {
		public SuggestionParams(List<CursorTelemetryPoint> cursorTelemetry, double totalMs,
				double defaultDurationMs, List<Span> reservedSpans) {
			this(cursorTelemetry, totalMs, defaultDurationMs, reservedSpans, 0,
					CLICK_CLUSTER_MERGE_GAP_MS, CLICK_CLUSTER_PAD_MS);
		}

		public SuggestionParams(List<CursorTelemetryPoint> cursorTelemetry, double totalMs,
				double defaultDurationMs) {
			this(cursorTelemetry, totalMs, defaultDurationMs, List.of());
		}
	}

	private static final List<String> EXPLICIT_CLICK_TYPES = List.of("click", "double-click",
			"right-click", "middle-click");

	private ZoomSuggestionUtils() {
	}

	/** Port of {@code shouldAutoApplyFreshRecordingZoomsForSource}. */
	public static boolean shouldAutoApplyFreshRecordingZoomsForSource(@Nullable Integer sourceWidth,
			@Nullable Integer sourceHeight, String platform) {
		if ("win32".equals(platform)) {
			return true;
		}
		if (sourceWidth == null || sourceHeight == null || sourceWidth <= 0 || sourceHeight <= 0) {
			return true;
		}
		return ((double) sourceWidth / sourceHeight) >= MIN_FRESH_RECORDING_AUTO_ZOOM_SOURCE_ASPECT_RATIO;
	}

	/** Port of {@code isExplicitClickType}; {@code null} (position-only sample) is never a click. */
	public static boolean isExplicitClickType(@Nullable String interactionType) {
		return interactionType != null && EXPLICIT_CLICK_TYPES.contains(interactionType);
	}

	/**
	 * The press-and-hold spans in a telemetry stream: every explicit click paired with its own
	 * mouseup — the first one before the next press — that is held for more than
	 * {@link #HOLD_LOUPE_MIN_MS}. A press whose mouseup was never recorded pairs with nothing
	 * (it must not borrow a later press's release). Each span becomes a loupe placement instead
	 * of a click zoom.
	 */
	public static List<SuggestedLoupeRegion> detectHoldSpans(List<CursorTelemetryPoint> samples) {
		List<CursorTelemetryPoint> sorted = new ArrayList<>(samples);
		sorted.sort(Comparator.comparingDouble(CursorTelemetryPoint::timeMs));
		List<SuggestedLoupeRegion> holds = new ArrayList<>();
		CursorTelemetryPoint press = null;
		for (CursorTelemetryPoint sample : sorted) {
			if (isExplicitClickType(sample.interactionType())) {
				press = sample;
			} else if ("mouseup".equals(sample.interactionType()) && press != null) {
				if (sample.timeMs() - press.timeMs() > HOLD_LOUPE_MIN_MS) {
					holds.add(new SuggestedLoupeRegion(press.timeMs(), sample.timeMs()));
				}
				press = null;
			}
		}
		return holds;
	}

	/** Port of {@code normalizeCursorTelemetry}. */
	public static List<CursorTelemetryPoint> normalizeCursorTelemetry(List<CursorTelemetryPoint> telemetry,
			double totalMs) {
		List<CursorTelemetryPoint> normalized = new ArrayList<>();
		for (CursorTelemetryPoint sample : telemetry) {
			if (!Double.isFinite(sample.timeMs()) || !Double.isFinite(sample.cx())
					|| !Double.isFinite(sample.cy())) {
				continue;
			}
			normalized.add(new CursorTelemetryPoint(clamp(sample.timeMs(), 0, totalMs),
					clamp(sample.cx(), 0, 1), clamp(sample.cy(), 0, 1), sample.interactionType(),
					sample.cursorType()));
		}
		normalized.sort(Comparator.comparingDouble(CursorTelemetryPoint::timeMs));

		for (CursorInteractionCandidate candidate : detectInteractionCandidates(normalized)) {
			if (candidate.kind() == InteractionKind.TEXT_SELECTION) {
				normalized = applyCursorTypeInRange(normalized, candidate.centerTimeMs() - 140,
						candidate.centerTimeMs() + 1200, "text");
			} else if (candidate.kind() == InteractionKind.TEXT_FIELD_CLICK
					|| candidate.kind() == InteractionKind.TEXT_FOCUS_LIKE) {
				normalized = applyCursorTypeInRange(normalized, candidate.centerTimeMs() - 100,
						candidate.centerTimeMs() + 900, "text");
			}
		}

		for (CursorTelemetryPoint sample : normalized) {
			if (!"click".equals(sample.interactionType())
					&& !"double-click".equals(sample.interactionType())) {
				continue;
			}
			CursorTelemetryPoint mouseUp = null;
			for (CursorTelemetryPoint candidate : normalized) {
				if (candidate.timeMs() > sample.timeMs()
						&& "mouseup".equals(candidate.interactionType())) {
					mouseUp = candidate;
					break;
				}
			}
			if (mouseUp == null) {
				continue;
			}
			double dragDuration = mouseUp.timeMs() - sample.timeMs();
			double dragDistance = Math.hypot(mouseUp.cx() - sample.cx(), mouseUp.cy() - sample.cy());
			if (dragDuration >= 160 && dragDistance > 0.015) {
				boolean isTextDrag = Math.abs(mouseUp.cx() - sample.cx()) > Math.abs(
						mouseUp.cy() - sample.cy()) * 1.8;
				normalized = applyCursorTypeInRange(normalized, sample.timeMs(), mouseUp.timeMs(),
						isTextDrag ? "text" : "closed-hand");
			}
		}
		return normalized;
	}

	/** Port of {@code detectZoomDwellCandidates}. */
	public static List<ZoomDwellCandidate> detectZoomDwellCandidates(List<CursorTelemetryPoint> samples) {
		if (samples.size() < 2) {
			return List.of();
		}
		List<ZoomDwellCandidate> candidates = new ArrayList<>();
		int runStart = 0;

		class Run {
			void push(int startIndex, int endIndexExclusive) {
				if (endIndexExclusive - startIndex < 2) {
					return;
				}
				CursorTelemetryPoint start = samples.get(startIndex);
				CursorTelemetryPoint end = samples.get(endIndexExclusive - 1);
				double runDuration = end.timeMs() - start.timeMs();
				if (runDuration < MIN_DWELL_DURATION_MS || runDuration > MAX_DWELL_DURATION_MS) {
					return;
				}
				double sumCx = 0;
				double sumCy = 0;
				for (int index = startIndex; index < endIndexExclusive; index++) {
					sumCx += samples.get(index).cx();
					sumCy += samples.get(index).cy();
				}
				int count = endIndexExclusive - startIndex;
				candidates.add(new ZoomDwellCandidate(
						Math.round((start.timeMs() + end.timeMs()) / 2),
						new ZoomFocus(sumCx / count, sumCy / count), runDuration));
			}
		}

		Run run = new Run();
		for (int index = 1; index < samples.size(); index++) {
			CursorTelemetryPoint prev = samples.get(index - 1);
			CursorTelemetryPoint curr = samples.get(index);
			double distance = Math.hypot(curr.cx() - prev.cx(), curr.cy() - prev.cy());
			if (distance > DWELL_MOVE_THRESHOLD) {
				run.push(runStart, index);
				runStart = index;
			}
		}
		run.push(runStart, samples.size());
		return candidates;
	}

	/** Port of {@code detectInteractionCandidates}. */
	public static List<CursorInteractionCandidate> detectInteractionCandidates(
			List<CursorTelemetryPoint> samples) {
		List<CursorInteractionCandidate> explicit = new ArrayList<>();
		for (CursorTelemetryPoint clickSample : samples) {
			if (!isExplicitClickType(clickSample.interactionType())) {
				continue;
			}
			InteractionKind kind = classifyPostClickBehavior(samples, clickSample);
			double baseStrength = switch (kind) {
				case DOUBLE_CLICK_LIKE -> 1500;
				case DROPDOWN_OPEN -> 1200;
				case TEXT_SELECTION -> 1300;
				case TEXT_FIELD_CLICK -> 1100;
				default -> 900;
			};
			explicit.add(new CursorInteractionCandidate(Math.round(clickSample.timeMs()),
					new ZoomFocus(clickSample.cx(), clickSample.cy()), baseStrength, kind,
					InteractionSource.EXPLICIT));
		}

		List<CursorInteractionCandidate> dwell = new ArrayList<>();
		for (ZoomDwellCandidate candidate : detectZoomDwellCandidates(samples)) {
			InteractionKind kind = candidate.strength() >= 1100
					? InteractionKind.TEXT_FOCUS_LIKE
					: candidate.strength() <= 800 ? InteractionKind.CLICK_LIKE
							: InteractionKind.DWELL;
			dwell.add(new CursorInteractionCandidate(candidate.centerTimeMs(), candidate.focus(),
					candidate.strength(), kind, InteractionSource.HEURISTIC));
		}

		List<CursorInteractionCandidate> doubleClicks = new ArrayList<>();
		List<CursorInteractionCandidate> sortedByTime = new ArrayList<>(dwell);
		sortedByTime.sort(Comparator.comparingDouble(CursorInteractionCandidate::centerTimeMs));
		for (int index = 1; index < sortedByTime.size(); index++) {
			CursorInteractionCandidate prev = sortedByTime.get(index - 1);
			CursorInteractionCandidate curr = sortedByTime.get(index);
			double timeGap = curr.centerTimeMs() - prev.centerTimeMs();
			double spatialGap = Math.hypot(curr.focus().cx() - prev.focus().cx(),
					curr.focus().cy() - prev.focus().cy());
			boolean bothShort = prev.strength() <= 900 && curr.strength() <= 900;
			if (bothShort && timeGap <= 450 && spatialGap <= 0.035) {
				doubleClicks.add(new CursorInteractionCandidate(
						Math.round((prev.centerTimeMs() + curr.centerTimeMs()) / 2),
						new ZoomFocus((prev.focus().cx() + curr.focus().cx()) / 2,
								(prev.focus().cy() + curr.focus().cy()) / 2),
						prev.strength() + curr.strength() + 500, InteractionKind.DOUBLE_CLICK_LIKE,
						InteractionSource.HEURISTIC));
			}
		}

		List<CursorInteractionCandidate> all = new ArrayList<>();
		all.addAll(explicit);
		all.addAll(dwell);
		all.addAll(doubleClicks);
		return all;
	}

	/** Port of {@code buildInteractionZoomSuggestions}. */
	public static SuggestionResult buildInteractionZoomSuggestions(SuggestionParams params) {
		double totalMs = params.totalMs();
		List<Span> reservedSpans = params.reservedSpans() == null ? List.of() : params.reservedSpans();
		double mergeGapMs = params.mergeGapMs();
		double padMs = params.padMs();
		if (totalMs <= 0) {
			return new SuggestionResult(SuggestionStatus.NO_SLOTS, List.of());
		}
		List<CursorTelemetryPoint> normalized = normalizeCursorTelemetry(
				params.cursorTelemetry() == null ? List.of() : params.cursorTelemetry(), totalMs);
		if (normalized.isEmpty()) {
			return new SuggestionResult(SuggestionStatus.NO_TELEMETRY, List.of());
		}
		if (normalized.size() == 1 && !isExplicitClickType(normalized.get(0).interactionType())) {
			return new SuggestionResult(SuggestionStatus.NO_TELEMETRY, List.of());
		}

		List<CursorInteractionCandidate> clickCandidates = detectInteractionCandidates(normalized)
				.stream().filter(candidate -> candidate.source() == InteractionSource.EXPLICIT)
				.toList();

		// Presses held longer than HOLD_LOUPE_MIN_MS place a magnifying glass over the hold
		// instead of a click zoom, so they are both new loupe suggestions and excluded from the
		// click clusters.
		List<SuggestedLoupeRegion> loupeSuggestions = buildLoupeSuggestions(normalized, totalMs);
		java.util.Set<Long> holdStarts = new java.util.HashSet<>();
		for (SuggestedLoupeRegion hold : detectHoldSpans(normalized)) {
			holdStarts.add(Math.round(hold.startMs()));
		}
		List<CursorInteractionCandidate> zoomCandidates = clickCandidates.stream()
				.filter(candidate -> !holdStarts.contains(Math.round(candidate.centerTimeMs())))
				.toList();
		if (zoomCandidates.isEmpty()) {
			if (loupeSuggestions.isEmpty()) {
				return new SuggestionResult(SuggestionStatus.NO_INTERACTIONS, List.of());
			}
			return new SuggestionResult(SuggestionStatus.OK, List.of(), loupeSuggestions);
		}

		List<ClickCluster> clusters = buildClickClusters(zoomCandidates, mergeGapMs);
		List<Span> reserved = new ArrayList<>(reservedSpans);
		reserved.sort(Comparator.comparingDouble(Span::start));
		List<SuggestedZoomRegion> suggestions = new ArrayList<>();

		for (ClickCluster cluster : clusters) {
			double regionStart = Math.max(0, cluster.firstMs() - padMs);
			double regionEnd = Math.min(totalMs, cluster.lastMs() + padMs);
			if (regionEnd <= regionStart) {
				continue;
			}
			boolean hasOverlap = false;
			for (Span span : reserved) {
				if (regionEnd > span.start() && regionStart < span.end()) {
					hasOverlap = true;
					break;
				}
			}
			if (hasOverlap) {
				continue;
			}
			reserved.add(new Span(regionStart, regionEnd));
			suggestions.add(new SuggestedZoomRegion(regionStart, regionEnd, cluster.focus()));
		}

		if (suggestions.isEmpty()) {
			if (loupeSuggestions.isEmpty()) {
				return new SuggestionResult(SuggestionStatus.NO_SLOTS, List.of());
			}
			return new SuggestionResult(SuggestionStatus.OK, List.of(), loupeSuggestions);
		}
		suggestions.sort(Comparator.comparingDouble(SuggestedZoomRegion::startMs));
		return new SuggestionResult(SuggestionStatus.OK, suggestions, loupeSuggestions);
	}

	/**
	 * Clamped, time-sorted hold spans without duplicates (a hold that overlaps an earlier one is
	 * dropped — one mouse press chain places one lens).
	 */
	private static List<SuggestedLoupeRegion> buildLoupeSuggestions(
			List<CursorTelemetryPoint> normalized, double totalMs) {
		List<SuggestedLoupeRegion> holds = new ArrayList<>();
		for (SuggestedLoupeRegion hold : detectHoldSpans(normalized)) {
			double start = clamp(hold.startMs() - LOUPE_LEAD_MS, 0, totalMs);
			double end = clamp(Math.max(hold.endMs() + LOUPE_TAIL_MS, start + LOUPE_MIN_MS), 0,
					totalMs);
			if (end <= start) {
				continue;
			}
			boolean overlaps = false;
			for (SuggestedLoupeRegion accepted : holds) {
				if (end > accepted.startMs() && start < accepted.endMs()) {
					overlaps = true;
					break;
				}
			}
			if (!overlaps) {
				holds.add(new SuggestedLoupeRegion(start, end));
			}
		}
		holds.sort(Comparator.comparingDouble(SuggestedLoupeRegion::startMs));
		return holds;
	}

	private record ClickCluster(double firstMs, double lastMs, ZoomFocus focus) {
	}

	private static List<ClickCluster> buildClickClusters(
			List<CursorInteractionCandidate> clicks, double mergeGapMs) {
		if (clicks.isEmpty()) {
			return List.of();
		}
		List<CursorInteractionCandidate> sorted = new ArrayList<>(clicks);
		sorted.sort(Comparator.comparingDouble(CursorInteractionCandidate::centerTimeMs));
		List<ClickCluster> clusters = new ArrayList<>();

		double clusterStart = sorted.get(0).centerTimeMs();
		double clusterEnd = sorted.get(0).centerTimeMs();
		double bestStrength = sorted.get(0).strength();
		ZoomFocus bestFocus = sorted.get(0).focus();
		double sumCx = sorted.get(0).focus().cx();
		double sumCy = sorted.get(0).focus().cy();
		int count = 1;

		for (int index = 1; index < sorted.size(); index++) {
			CursorInteractionCandidate click = sorted.get(index);
			double gap = click.centerTimeMs() - clusterEnd;
			if (gap <= mergeGapMs) {
				clusterEnd = Math.max(clusterEnd, click.centerTimeMs());
				if (click.strength() > bestStrength) {
					bestStrength = click.strength();
					bestFocus = click.focus();
				}
				sumCx += click.focus().cx();
				sumCy += click.focus().cy();
				count += 1;
			} else {
				clusters.add(new ClickCluster(clusterStart, clusterEnd,
						bestFocus != null ? bestFocus : new ZoomFocus(sumCx / count, sumCy / count)));
				clusterStart = click.centerTimeMs();
				clusterEnd = click.centerTimeMs();
				bestStrength = click.strength();
				bestFocus = click.focus();
				sumCx = click.focus().cx();
				sumCy = click.focus().cy();
				count = 1;
			}
		}
		clusters.add(new ClickCluster(clusterStart, clusterEnd,
				bestFocus != null ? bestFocus : new ZoomFocus(sumCx / count, sumCy / count)));
		return clusters;
	}

	private static InteractionKind classifyPostClickBehavior(List<CursorTelemetryPoint> samples,
			CursorTelemetryPoint clickSample) {
		if ("double-click".equals(clickSample.interactionType())) {
			return InteractionKind.DOUBLE_CLICK_LIKE;
		}
		double clickTime = clickSample.timeMs();

		CursorTelemetryPoint mouseUpAfter = null;
		for (CursorTelemetryPoint sample : samples) {
			if ("mouseup".equals(sample.interactionType()) && sample.timeMs() > clickTime
					&& sample.timeMs() - clickTime < 3000) {
				mouseUpAfter = sample;
				break;
			}
		}
		if (mouseUpAfter != null) {
			double dragDx = Math.abs(mouseUpAfter.cx() - clickSample.cx());
			double dragDy = Math.abs(mouseUpAfter.cy() - clickSample.cy());
			double dragDuration = mouseUpAfter.timeMs() - clickTime;
			if (dragDuration >= 200 && dragDx > 0.03 && dragDx > dragDy * 1.8) {
				return InteractionKind.TEXT_SELECTION;
			}
		}

		List<CursorTelemetryPoint> moveSamples = new ArrayList<>();
		for (CursorTelemetryPoint sample : samples) {
			if (sample.timeMs() > clickTime + 100 && sample.timeMs() <= clickTime + 2000
					&& (sample.interactionType() == null || "move".equals(sample.interactionType()))) {
				moveSamples.add(sample);
			}
		}
		if (moveSamples.size() < 3) {
			return InteractionKind.TEXT_FIELD_CLICK;
		}

		double maxDist = 0;
		double totalAbsDy = 0;
		double totalAbsDx = 0;
		for (CursorTelemetryPoint sample : moveSamples) {
			maxDist = Math.max(maxDist,
					Math.hypot(sample.cx() - clickSample.cx(), sample.cy() - clickSample.cy()));
			totalAbsDx += Math.abs(sample.cx() - clickSample.cx());
			totalAbsDy += Math.abs(sample.cy() - clickSample.cy());
		}
		if (maxDist < 0.02) {
			return InteractionKind.TEXT_FIELD_CLICK;
		}
		CursorTelemetryPoint lastMoveSample = moveSamples.get(moveSamples.size() - 1);
		double netDy = lastMoveSample.cy() - clickSample.cy();
		if (netDy > 0.03 && totalAbsDy > totalAbsDx * 1.5) {
			return InteractionKind.DROPDOWN_OPEN;
		}
		if (totalAbsDx > 0.03 && totalAbsDx > totalAbsDy * 1.8) {
			return InteractionKind.TEXT_SELECTION;
		}
		return InteractionKind.CLICK_LIKE;
	}

	private static List<CursorTelemetryPoint> applyCursorTypeInRange(List<CursorTelemetryPoint> samples,
			double startMs, double endMs, String cursorType) {
		List<CursorTelemetryPoint> next = new ArrayList<>(samples.size());
		for (CursorTelemetryPoint sample : samples) {
			if (sample.timeMs() >= startMs && sample.timeMs() <= endMs
					&& (sample.cursorType() == null || sample.cursorType().isBlank())) {
				next.add(new CursorTelemetryPoint(sample.timeMs(), sample.cx(), sample.cy(),
						sample.interactionType(), cursorType));
			} else {
				next.add(sample);
			}
		}
		return next;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(value, max));
	}
}
