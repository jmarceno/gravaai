package dev.gravaai.services.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Click/interaction zoom suggestions for the {@code auto_zoom} op (port of the click-cluster part
 * of {@code src/components/video-editor/timeline/zoomSuggestionUtils.ts}, plan §5.16).
 */
public final class AgentAutoZoom {

	static final double CLICK_CLUSTER_MERGE_GAP_MS = 2500;
	static final double CLICK_CLUSTER_PAD_MS = 500;
	static final double DEFAULT_DURATION_MS = 1000;

	/** One cursor telemetry sample (source time), public so app-side planners can build a context. */
	public record Sample(double timeMs, double cx, double cy, String interactionType) {}

	/** One suggested zoom in source time. */
	record Suggestion(double start, double end, double cx, double cy) {}

	/** Everything {@code buildInteractionZoomSuggestions} returns. */
	record Result(List<Suggestion> suggestions, String status) {
		static Result of(List<Suggestion> suggestions) {
			return new Result(List.copyOf(suggestions), "ok");
		}

		static Result status(String status) {
			return new Result(List.of(), status);
		}
	}

	private AgentAutoZoom() {}

	/** {@code buildInteractionZoomSuggestions} for the agent planner. */
	static Result build(List<Sample> telemetry, double totalMs, double defaultDurationMs,
			List<double[]> reservedSpans) {
		List<Sample> samples = normalize(telemetry);
		if (samples.isEmpty()) {
			return Result.status("no-telemetry");
		}
		List<Sample> clicks = samples.stream()
				.filter(sample -> "click".equals(sample.interactionType())).toList();
		if (clicks.isEmpty()) {
			return Result.status("no-clusters");
		}
		List<List<Sample>> clusters = cluster(clicks);
		List<Suggestion> suggestions = new ArrayList<>();
		for (List<Sample> cluster : clusters) {
			double firstMs = cluster.get(0).timeMs();
			double start = Math.max(0, firstMs - CLICK_CLUSTER_PAD_MS);
			double duration = defaultDurationMs > 0 && Double.isFinite(defaultDurationMs)
					? defaultDurationMs
					: DEFAULT_DURATION_MS;
			double end = Math.min(Math.max(start + 1, totalMs), start + duration);
			double sumX = 0;
			double sumY = 0;
			for (Sample sample : cluster) {
				sumX += sample.cx();
				sumY += sample.cy();
			}
			Suggestion suggestion = new Suggestion(start, end, clamp(sumX / cluster.size()),
					clamp(sumY / cluster.size()));
			if (reservedSpans != null && reservedSpans.stream().anyMatch(span -> overlaps(span,
					suggestion.start(), suggestion.end()))) {
				continue;
			}
			suggestions.add(suggestion);
		}
		if (suggestions.isEmpty()) {
			return Result.status("all-reserved");
		}
		suggestions.sort(Comparator.comparingDouble(Suggestion::start));
		return Result.of(suggestions);
	}

	private static List<Sample> normalize(List<Sample> telemetry) {
		List<Sample> samples = new ArrayList<>();
		if (telemetry == null) {
			return samples;
		}
		for (Sample sample : telemetry) {
			if (sample == null || !Double.isFinite(sample.timeMs()) || sample.timeMs() < 0
					|| !Double.isFinite(sample.cx()) || !Double.isFinite(sample.cy())) {
				continue;
			}
			samples.add(new Sample(sample.timeMs(), clamp(sample.cx()), clamp(sample.cy()),
					sample.interactionType()));
		}
		samples.sort(Comparator.comparingDouble(Sample::timeMs));
		return samples;
	}

	private static List<List<Sample>> cluster(List<Sample> clicks) {
		List<Sample> sorted = new ArrayList<>(clicks);
		sorted.sort(Comparator.comparingDouble(Sample::timeMs));
		List<List<Sample>> clusters = new ArrayList<>();
		List<Sample> current = new ArrayList<>();
		for (Sample click : sorted) {
			if (current.isEmpty() || click.timeMs()
					- current.get(current.size() - 1).timeMs() <= CLICK_CLUSTER_MERGE_GAP_MS) {
				current.add(click);
				continue;
			}
			clusters.add(List.copyOf(current));
			current = new ArrayList<>();
			current.add(click);
		}
		if (!current.isEmpty()) {
			clusters.add(List.copyOf(current));
		}
		return clusters;
	}

	private static boolean overlaps(double[] span, double start, double end) {
		return span[0] < end && span[1] > start;
	}

	private static double clamp(double value) {
		return Math.min(1, Math.max(0, value));
	}
}
