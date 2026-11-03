package dev.gravaai.core.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.project.CameraLayout;
import dev.gravaai.core.project.CameraLayoutSegment;
import dev.gravaai.core.project.CameraLayoutStyle;

/**
 * The Camera layout track operations (one class per command, like {@link ZoomCommands}). Segments
 * never overlap: switching the layout at a time splits the segment under it, and moving an edge
 * stops at the neighbouring segments.
 */
public final class CameraLayoutCommands {

	/** Shortest segment a drag or a switch may leave behind. */
	public static final long MIN_SEGMENT_MS = 100;

	private CameraLayoutCommands() {}

	private static List<String> ids(List<CameraLayoutSegment> segments) {
		return segments.stream().map(CameraLayoutSegment::id).toList();
	}

	private static Optional<CameraLayoutSegment> find(EditorStore store, String id) {
		return store.state().cameraLayoutSegments().stream()
				.filter(segment -> segment.id().equals(id)).findFirst();
	}

	private static void select(EditorStore store, @Nullable String id) {
		store.clearSelectionsExcept(null, null, null, null, null, null);
		store.setSelectedCameraLayoutId(id);
	}

	/** Selects one segment (or clears the camera layout selection with {@code id = null}). */
	public static final class Select implements EditorCommand {
		private final @Nullable String id;

		public Select(@Nullable String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			select(store, id);
			return true;
		}
	}

	/**
	 * Switches the layout from {@code timeMs} on: inside a segment it splits it there (or relabels
	 * it when {@code timeMs} is its start); in a gap it adds a segment up to the next segment or
	 * {@code timelineEndMs}. The resulting segment is selected.
	 */
	public static final class SwitchAt implements EditorCommand {
		private final double timeMs;
		private final double timelineEndMs;
		private final CameraLayout layout;

		public SwitchAt(double timeMs, double timelineEndMs, CameraLayout layout) {
			this.timeMs = timeMs;
			this.timelineEndMs = timelineEndMs;
			this.layout = layout;
		}

		@Override
		public boolean apply(EditorStore store) {
			long at = Math.max(0, Math.round(timeMs));
			List<CameraLayoutSegment> segments = store.state().cameraLayoutSegments();
			List<CameraLayoutSegment> next = new ArrayList<>(segments.size() + 1);
			String selected = null;
			for (CameraLayoutSegment segment : segments) {
				if (selected != null || !segment.contains(at)) {
					next.add(segment);
					continue;
				}
				if (segment.layout().equals(layout)) {
					select(store, segment.id());
					return true;
				}
				if (at - segment.startMs() < MIN_SEGMENT_MS) {
					next.add(segment.withLayout(layout));
					selected = segment.id();
				} else if (segment.endMs() - at < MIN_SEGMENT_MS) {
					return false;
				} else {
					selected = store.nextId("camera", ids(segments));
					next.add(segment.withSpan(segment.startMs(), at));
					next.add(new CameraLayoutSegment(selected, at, segment.endMs(), layout));
				}
			}
			if (selected == null) {
				long end = Math.round(timelineEndMs);
				for (CameraLayoutSegment segment : segments) {
					if (segment.startMs() > at) {
						end = Math.min(end, segment.startMs());
					}
				}
				if (end - at < MIN_SEGMENT_MS) {
					return false;
				}
				selected = store.nextId("camera", ids(segments));
				next.add(new CameraLayoutSegment(selected, at, end, layout));
			}
			store.setCameraLayoutSegments(next);
			select(store, selected);
			return true;
		}
	}

	/** Changes the layout of one segment. */
	public static final class ChangeLayout implements EditorCommand {
		private final String id;
		private final CameraLayout layout;

		public ChangeLayout(String id, CameraLayout layout) {
			this.id = id;
			this.layout = layout;
		}

		@Override
		public boolean apply(EditorStore store) {
			Optional<CameraLayoutSegment> current = find(store, id);
			if (current.isEmpty() || current.get().layout().equals(layout)) {
				return false;
			}
			store.setCameraLayoutSegments(store.state().cameraLayoutSegments().stream()
					.map(segment -> segment.id().equals(id) ? segment.withLayout(layout) : segment)
					.toList());
			return true;
		}
	}

	/** Overrides (or with an empty value, clears) one webcam style field of a segment. */
	public static final class SetStyle implements EditorCommand {
		private final String id;
		private final String field;
		private final OptionalDouble value;

		public SetStyle(String id, String field, OptionalDouble value) {
			if (!CameraLayoutStyle.FIELDS.contains(field)) {
				throw new IllegalArgumentException("unknown camera style field " + field);
			}
			this.id = id;
			this.field = field;
			this.value = value;
		}

		@Override
		public boolean apply(EditorStore store) {
			Optional<CameraLayoutSegment> current = find(store, id);
			if (current.isEmpty()) {
				return false;
			}
			CameraLayoutStyle next = current.get().style().with(field, value);
			if (next.equals(current.get().style())) {
				return false;
			}
			store.setCameraLayoutSegments(store.state().cameraLayoutSegments().stream()
					.map(segment -> segment.id().equals(id) ? segment.withStyle(next) : segment)
					.toList());
			return true;
		}
	}

	/** How the camera arrives at a segment's start: an instant cut or a smooth move. */
	public static final class SetTransition implements EditorCommand {
		private final String id;
		private final CameraLayoutSegment.Transition transition;

		public SetTransition(String id, CameraLayoutSegment.Transition transition) {
			this.id = id;
			this.transition = transition;
		}

		@Override
		public boolean apply(EditorStore store) {
			Optional<CameraLayoutSegment> current = find(store, id);
			if (current.isEmpty() || current.get().transition() == transition) {
				return false;
			}
			store.setCameraLayoutSegments(store.state().cameraLayoutSegments().stream()
					.map(segment -> segment.id().equals(id) ? segment.withTransition(transition)
							: segment)
					.toList());
			return true;
		}
	}

	/** Moves or resizes one segment, stopping at its neighbours. */
	public static final class ChangeSpan implements EditorCommand {
		private final String id;
		private final double startMs;
		private final double endMs;

		public ChangeSpan(String id, double startMs, double endMs) {
			this.id = id;
			this.startMs = startMs;
			this.endMs = endMs;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<CameraLayoutSegment> segments = store.state().cameraLayoutSegments();
			int index = ids(segments).indexOf(id);
			if (index < 0) {
				return false;
			}
			long floor = index > 0 ? segments.get(index - 1).endMs() : 0;
			long ceiling = index + 1 < segments.size() ? segments.get(index + 1).startMs()
					: Long.MAX_VALUE;
			long start = Math.max(floor, Math.round(startMs));
			long end = Math.min(ceiling, Math.round(endMs));
			if (end - start < MIN_SEGMENT_MS) {
				return false;
			}
			List<CameraLayoutSegment> next = new ArrayList<>(segments);
			next.set(index, segments.get(index).withSpan(start, end));
			store.setCameraLayoutSegments(next);
			return true;
		}
	}

	/** Removes one segment; that stretch falls back to the project layout. */
	public static final class Delete implements EditorCommand {
		private final String id;

		public Delete(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<CameraLayoutSegment> segments = store.state().cameraLayoutSegments();
			List<CameraLayoutSegment> next = segments.stream()
					.filter(segment -> !segment.id().equals(id)).toList();
			if (next.size() == segments.size()) {
				return false;
			}
			store.setCameraLayoutSegments(next);
			if (id.equals(store.selectedCameraLayoutId())) {
				store.setSelectedCameraLayoutId(null);
			}
			return true;
		}
	}
}
