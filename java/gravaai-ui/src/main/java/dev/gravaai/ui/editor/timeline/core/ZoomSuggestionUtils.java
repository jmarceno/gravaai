package dev.gravaai.ui.editor.timeline.core;

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
	/** Max gap between consecutive clicks before they are split into separate zoom clusters. */
	public static final double CLICK_CLUSTER_MERGE_GAP_MS = 2500;
	/** Padding added before the first click and after the last click in a cluster. */
	public static final double CLICK_CLUSTER_PAD_MS = 500;

	/** Port of {@code CursorTelemetryPoint} (interaction/cursorType are nullable). */
	public record CursorTelemetryPoint(double timeMs, double cx, double cy, String interactionType,
			String cursorType) {
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

	public enum SuggestionStatus {
		OK,
		NO_TELEMETRY,
		NO_INTERACTIONS,
		NO_SLOTS
	}

	/** Port of {@code InteractionZoomSuggestionResult}. */
	public record SuggestionResult(SuggestionStatus status, List<SuggestedZoomRegion> suggestions) {
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
	public static boolean shouldAutoApplyFreshRecordingZoomsForSource(Integer sourceWidth,
			Integer sourceHeight, String platform) {
		if ("win32".equals(platform)) {
			return true;
		}
		if (sourceWidth == null || sourceHeight == null || sourceWidth <= 0 || sourceHeight <= 0) {
			return true;
		}
		return ((double) sourceWidth / sourceHeight) >= MIN_FRESH_RECORDING_AUTO_ZOOM_SOURCE_ASPECT_RATIO;
	}

	/** Port of {@code isExplicitClickType}. */
	public static boolean isExplicitClickType(String interactionType) {
		return interactionType != null && EXPLICIT_CLICK_TYPES.contains(interactionType);
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
		if (clickCandidates.isEmpty()) {
			return new SuggestionResult(SuggestionStatus.NO_INTERACTIONS, List.of());
		}

		List<ClickCluster> clusters = buildClickClusters(clickCandidates, mergeGapMs);
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
			return new SuggestionResult(SuggestionStatus.NO_SLOTS, List.of());
		}
		suggestions.sort(Comparator.comparingDouble(SuggestedZoomRegion::startMs));
		return new SuggestionResult(SuggestionStatus.OK, suggestions);
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
