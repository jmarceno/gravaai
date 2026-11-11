package dev.gravaai.core.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.TimelineMarker;

/**
 * Timeline marker operations: place, rename, retime, promote to chapter and delete.
 *
 * <p>Markers are stored in source time (like caption cues), so trims and cuts move them with the
 * footage. {@link #moveToTimelineMs} converts the timeline position a drag produced back to source
 * time through the clip under it; a drag onto footage that is not on the timeline does nothing.
 */
public final class MarkerCommands {

	private MarkerCommands() {}

	private static List<TimelineMarker> sorted(List<TimelineMarker> markers) {
		List<TimelineMarker> next = new ArrayList<>(markers);
		next.sort(Comparator.comparingLong(TimelineMarker::startMs));
		return next;
	}

	private static String nextName(List<TimelineMarker> markers) {
		return TimelineMarker.defaultName(markers.size() + 1);
	}

	/** {@code handleAddMarker}: drops a marker at a timeline time, named from the marker count. */
	public static final class Add implements EditorCommand {
		private final long timelineMs;
		private final Optional<String> name;

		/** A marker with the next generic name ({@code Marker N}). */
		public Add(long timelineMs) {
			this(timelineMs, Optional.empty());
		}

		public Add(long timelineMs, String name) {
			this(timelineMs, Optional.of(name));
		}

		private Add(long timelineMs, Optional<String> name) {
			this.timelineMs = Math.max(0, timelineMs);
			this.name = name.filter(candidate -> !candidate.isBlank());
		}

		@Override
		public boolean apply(EditorStore store) {
			List<TimelineMarker> markers = store.state().markers();
			String id = store.nextId("marker", markers.stream().map(TimelineMarker::id).toList());
			long sourceMs = Clips.mapTimelineTimeToSourceTime(timelineMs,
					store.state().clipRegions());
			String resolvedName = name.map(TimelineMarker::cleanName).orElseGet(() -> nextName(markers));
			List<TimelineMarker> next = new ArrayList<>(markers);
			next.add(new TimelineMarker(id, sourceMs, resolvedName, false));
			store.setMarkers(sorted(next));
			store.setSelectedMarkerId(id);
			return true;
		}
	}

	/** {@code handleMarkerRename}: renames a marker; blank names fall back to the generic one. */
	public static final class Rename implements EditorCommand {
		private final String id;
		private final String name;

		public Rename(String id, String name) {
			this.id = id;
			this.name = name;
		}

		@Override
		public boolean apply(EditorStore store) {
			if (store.state().markers().stream().noneMatch(marker -> marker.id().equals(id))) {
				return false;
			}
			List<TimelineMarker> markers = store.state().markers();
			List<TimelineMarker> next = new ArrayList<>();
			for (int index = 0; index < markers.size(); index++) {
				TimelineMarker marker = markers.get(index);
				// A blank name falls back to this marker's generic name, never to an empty flag.
				String resolved = name.isBlank() ? TimelineMarker.defaultName(index + 1)
						: TimelineMarker.cleanName(name);
				next.add(marker.id().equals(id) ? marker.withName(resolved) : marker);
			}
			store.setMarkers(next);
			return true;
		}
	}

	/**
	 * {@code handleMarkerRetime}: moves a marker to a timeline position (the ruler drags in
	 * timeline pixels, the marker stores source time). Returns false over a gap.
	 */
	public static final class MoveToTimelineMs implements EditorCommand {
		private final String id;
		private final long timelineMs;

		public MoveToTimelineMs(String id, long timelineMs) {
			this.id = id;
			this.timelineMs = Math.max(0, timelineMs);
		}

		@Override
		public boolean apply(EditorStore store) {
			List<ClipRegion> clips = store.state().clipRegions();
			// Clips.findClipAtTimelineTime is the legacy nullable lookup: null means a gap.
			boolean overGap = Optional.ofNullable(Clips.findClipAtTimelineTime(timelineMs, clips))
					.isEmpty();
			if (timelineMs > 0 && overGap) {
				return false;
			}
			long sourceMs = Clips.mapTimelineTimeToSourceTime(timelineMs, clips);
			return find(store, id).map(marker -> applySource(store, marker, sourceMs)).orElse(false);
		}
	}

	/** {@code handleMarkerMoveToSourceMs}: the agent/ops path, which speaks source time. */
	public static final class MoveToSourceMs implements EditorCommand {
		private final String id;
		private final long sourceMs;

		public MoveToSourceMs(String id, long sourceMs) {
			this.id = id;
			this.sourceMs = Math.max(0, sourceMs);
		}

		@Override
		public boolean apply(EditorStore store) {
			return find(store, id).map(marker -> applySource(store, marker, sourceMs)).orElse(false);
		}
	}

	private static Optional<TimelineMarker> find(EditorStore store, String id) {
		return store.state().markers().stream().filter(marker -> marker.id().equals(id)).findFirst();
	}

	private static boolean applySource(EditorStore store, TimelineMarker marker, long sourceMs) {
		if (marker.startMs() == sourceMs) {
			return false;
		}
		List<TimelineMarker> next = new ArrayList<>();
		for (TimelineMarker candidate : store.state().markers()) {
			next.add(candidate.id().equals(marker.id()) ? candidate.withStartMs(sourceMs) : candidate);
		}
		// A retimed marker keeps its name and chapter flag and takes its new place in the list.
		store.setMarkers(sorted(next));
		return true;
	}

	/** {@code handleMarkerChapterChange}: promotes a marker to a chapter, or demotes it. */
	public static final class SetChapter implements EditorCommand {
		private final String id;
		private final boolean chapter;

		public SetChapter(String id, boolean chapter) {
			this.id = id;
			this.chapter = chapter;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<TimelineMarker> next = new ArrayList<>();
			boolean found = false;
			for (TimelineMarker marker : store.state().markers()) {
				if (marker.id().equals(id)) {
					found = true;
					next.add(marker.withChapter(chapter));
				} else {
					next.add(marker);
				}
			}
			if (!found) {
				return false;
			}
			store.setMarkers(next);
			return true;
		}
	}

	/** {@code handleMarkerDelete}. */
	public static final class Delete implements EditorCommand {
		private final String id;

		public Delete(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<TimelineMarker> next = new ArrayList<>();
			for (TimelineMarker marker : store.state().markers()) {
				if (!marker.id().equals(id)) {
					next.add(marker);
				}
			}
			if (next.size() == store.state().markers().size()) {
				return false;
			}
			store.setMarkers(next);
			if (store.selectedMarkerId().filter(id::equals).isPresent()) {
				store.clearSelectedMarker();
			}
			return true;
		}
	}

	/** {@code handleMarkerSelect}. */
	public static final class Select implements EditorCommand {
		private final String id;

		public Select(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			if (find(store, id).isEmpty()) {
				return false;
			}
			store.setSelectedMarkerId(id);
			return true;
		}
	}

}
