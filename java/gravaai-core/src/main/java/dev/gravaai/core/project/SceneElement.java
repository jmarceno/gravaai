package dev.gravaai.core.project;

import java.util.Comparator;
import java.util.List;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.node.ObjectNode;

/**
 * One timed instance of an {@link ElementComponent} on the elements lane.
 *
 * @param id stable internal id ({@code element-3})
 * @param component the component name (project or {@code gravaai/} built-in)
 * @param startMs timeline start
 * @param endMs timeline end (exclusive), greater than {@code startMs}
 * @param box where the element draws, in percent of the output frame
 * @param props JSON props merged over the component's {@code defaultProps}; {@code {}} when none.
 *        The record keeps its own deep copy, so callers must not mutate the returned node.
 * @param zIndex draw order among elements (higher draws on top)
 */
public record SceneElement(String id, String component, long startMs, long endMs, Box box,
		ObjectNode props, int zIndex) {

	/** Draw order: z-index, then start time, then id. */
	public static final Comparator<SceneElement> DRAW_ORDER = Comparator
			.comparingInt(SceneElement::zIndex)
			.thenComparingLong(SceneElement::startMs)
			.thenComparing(SceneElement::id);

	/** Shortest element span the editor allows. */
	public static final long MIN_DURATION_MS = 100;

	/** A rectangle in percent of the output frame (0–100), annotation convention. */
	public record Box(double x, double y, double width, double height) {

		public static final Box FULL = new Box(0, 0, 100, 100);

		public Box {
			x = finiteOr(x, 0);
			y = finiteOr(y, 0);
			width = Math.max(1, Math.min(400, finiteOr(width, 100)));
			height = Math.max(1, Math.min(400, finiteOr(height, 100)));
			x = Math.max(-300, Math.min(300, x));
			y = Math.max(-300, Math.min(300, y));
		}

		private static double finiteOr(double value, double fallback) {
			return Double.isFinite(value) ? value : fallback;
		}
	}

	public SceneElement {
		if (endMs <= startMs) {
			throw new IllegalArgumentException("element " + id + " must end after it starts");
		}
		props = props.deepCopy();
	}

	public boolean isActiveAt(double timeMs) {
		return timeMs >= startMs && timeMs < endMs;
	}

	public long durationMs() {
		return endMs - startMs;
	}

	public SceneElement withSpan(long nextStartMs, long nextEndMs) {
		return new SceneElement(id, component, nextStartMs, nextEndMs, box, props, zIndex);
	}

	public SceneElement withBox(Box nextBox) {
		return new SceneElement(id, component, startMs, endMs, nextBox, props, zIndex);
	}

	public SceneElement withProps(ObjectNode nextProps) {
		return new SceneElement(id, component, startMs, endMs, box, nextProps, zIndex);
	}

	public SceneElement withZIndex(int nextZIndex) {
		return new SceneElement(id, component, startMs, endMs, box, props, nextZIndex);
	}

	/** Empty props object. */
	public static ObjectNode noProps() {
		return Json.nodeFactory().objectNode();
	}

	/** Elements sorted by start (the order of the {@code e1}, {@code e2} … short ids). */
	public static List<SceneElement> byStart(List<SceneElement> elements) {
		return elements.stream().sorted(Comparator.comparingLong(SceneElement::startMs)
				.thenComparingInt(SceneElement::zIndex).thenComparing(SceneElement::id)).toList();
	}
}
