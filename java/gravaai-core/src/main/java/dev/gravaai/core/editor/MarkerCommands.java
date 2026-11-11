package dev.gravaai.core.editor;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.MarkerTimeline;
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

	/** The clip under a timeline position, or null when that time is past the last clip. */
	private static @Nullable ClipRegion clipAtTimelineMs(List<ClipRegion> clips, long timelineMs) {
		return Clips.findClipAtTimelineTime(timelineMs, clips);
	}

	/** {@code handleAddMarker}: drops a marker at a timeline time, named from the marker count. */
	public static final class Add implements EditorCommand {
		private final long timelineMs;
		private final @Nullable String name;

		public Add(long timelineMs, @Nullable String name) {
			this.timelineMs = Math.max(0, timelineMs);
			this.name = name;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<TimelineMarker> markers = store.state().markers();
			String id = store.nextId("marker", markers.stream().map(TimelineMarker::id).toList());
			long sourceMs = Clips.mapTimelineTimeToSourceTime(timelineMs,
					store.state().clipRegions());
			String resolvedName = name == null || name.isBlank() ? nextName(markers)
					: TimelineMarker.cleanName(name);
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
			List<TimelineMarker> next = new ArrayList<>();
			for (TimelineMarker marker : store.state().markers()) {
				next.add(marker.id().equals(id) ? marker.withName(name) : marker);
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
			TimelineMarker marker = store.state().markers().stream()
					.filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
			if (marker == null) {
				return false;
			}
			long sourceMs = Clips.mapTimelineTimeToSourceTime(timelineMs,
					store.state().clipRegions());
			if (timelineMs > 0 && clipAtTimelineMs(store.state().clipRegions(), timelineMs) == null) {
				return false;
			}
			return applySource(store, marker, sourceMs);
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
			TimelineMarker marker = store.state().markers().stream()
					.filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
			return marker != null && applySource(store, marker, sourceMs);
		}
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
			if (id.equals(store.selectedMarkerId())) {
				store.setSelectedMarkerId(null);
			}
			return true;
		}
	}

	/** {@code handleMarkerSelect}. */
	public static final class Select implements EditorCommand {
		private final @Nullable String id;

		public Select(@Nullable String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			if (id != null && store.state().markers().stream()
					.noneMatch(marker -> marker.id().equals(id))) {
				return false;
			}
			store.setSelectedMarkerId(id);
			return true;
		}
	}

	/** The marker at a timeline position, for the ruler hit test. */
	public static @Nullable TimelineMarker markerAtTimelineMs(List<TimelineMarker> markers,
			List<ClipRegion> clips, double timelineMs, double toleranceMs) {
		TimelineMarker best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (MarkerTimeline.Fragment fragment : MarkerTimeline.projectMarkers(markers, clips)) {
			double distance = Math.abs(fragment.timelineMs() - timelineMs);
			if (distance <= toleranceMs && distance < bestDistance) {
				best = fragment.marker();
				bestDistance = distance;
			}
		}
		return best;
	}
}
