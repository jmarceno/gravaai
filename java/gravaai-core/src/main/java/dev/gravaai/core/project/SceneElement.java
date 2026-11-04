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
 * @param space SCREEN (fixed overlay) or SCENE (follows the camera zoom and pan)
 * @param motion host-applied keyframed motion around the box; empty when none
 * @param blur motion-blur trails; {@link ElementMotion.Blur#NONE} when off
 */
public record SceneElement(String id, String component, long startMs, long endMs, Box box,
		ObjectNode props, int zIndex, Space space, List<ElementMotion.Track> motion,
		ElementMotion.Blur blur) {

	/** Where the box lives: over the output frame, or inside the camera transform. */
	public enum Space {
		SCREEN, SCENE;

		public String wire() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		public static java.util.Optional<Space> fromWire(String value) {
			for (Space space : values()) {
				if (space.name().equalsIgnoreCase(value)) {
					return java.util.Optional.of(space);
				}
			}
			return java.util.Optional.empty();
		}
	}

	/** A plain screen-space element without motion. */
	public SceneElement(String id, String component, long startMs, long endMs, Box box,
			ObjectNode props, int zIndex) {
		this(id, component, startMs, endMs, box, props, zIndex, Space.SCREEN, List.of(),
				ElementMotion.Blur.NONE);
	}

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
		motion = List.copyOf(motion);
	}

	public boolean isActiveAt(double timeMs) {
		return timeMs >= startMs && timeMs < endMs;
	}

	public long durationMs() {
		return endMs - startMs;
	}

	public SceneElement withSpan(long nextStartMs, long nextEndMs) {
		return new SceneElement(id, component, nextStartMs, nextEndMs, box, props, zIndex, space, motion,
				blur);
	}

	public SceneElement withBox(Box nextBox) {
		return new SceneElement(id, component, startMs, endMs, nextBox, props, zIndex, space, motion, blur);
	}

	public SceneElement withProps(ObjectNode nextProps) {
		return new SceneElement(id, component, startMs, endMs, box, nextProps, zIndex, space, motion, blur);
	}

	public SceneElement withZIndex(int nextZIndex) {
		return new SceneElement(id, component, startMs, endMs, box, props, nextZIndex, space, motion, blur);
	}

	public SceneElement withSpace(Space nextSpace) {
		return new SceneElement(id, component, startMs, endMs, box, props, zIndex, nextSpace, motion, blur);
	}

	public SceneElement withMotion(List<ElementMotion.Track> nextMotion) {
		return new SceneElement(id, component, startMs, endMs, box, props, zIndex, space, nextMotion, blur);
	}

	public SceneElement withBlur(ElementMotion.Blur nextBlur) {
		return new SceneElement(id, component, startMs, endMs, box, props, zIndex, space, motion, nextBlur);
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
