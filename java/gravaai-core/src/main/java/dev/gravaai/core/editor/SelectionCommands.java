package dev.gravaai.core.editor;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

import org.jspecify.annotations.Nullable;

/**
 * Multi-element selection operations (Ctrl/Cmd+click toggles, select-all-zooms). The primary id
 * drives the inspector exactly like the single {@code *Commands.Select} operations; every
 * multi-selected id is kept in {@link EditorStore#multiSelection()}.
 */
public final class SelectionCommands {

	private SelectionCommands() {}

	/**
	 * Selects many elements of one kind, with {@code primaryId} as the inspector's target. The kind
	 * is one of the timeline selection kinds ({@code "zoom"}, {@code "annotation"}, {@code "audio"});
	 * clips and captions stay single-selection.
	 */
	public static final class SelectMany implements EditorCommand {
		private final String kind;
		private final String primaryId;
		private final Collection<String> ids;

		public SelectMany(String kind, String primaryId, Collection<String> ids) {
			this.kind = kind;
			this.primaryId = primaryId;
			this.ids = List.copyOf(ids);
		}

		@Override
		public boolean apply(EditorStore store) {
			SequencedSet<String> next = new LinkedHashSet<>(ids);
			next.add(primaryId);
			switch (kind) {
				case TimelineSelections.ZOOM -> {
					store.setSelectedZoomId(primaryId);
					store.clearSelectionsExcept(null, primaryId, null, null, null);
					store.setMultiSelection(next);
					store.setActiveEffectSection(EditorStore.SECTION_ZOOM);
				}
				case TimelineSelections.ANNOTATION -> {
					store.setSelectedAnnotationId(primaryId);
					store.clearSelectionsExcept(null, null, primaryId, null, null);
					store.setMultiSelection(next);
				}
				case TimelineSelections.AUDIO -> {
					store.setSelectedAudioId(primaryId);
					store.clearSelectionsExcept(null, null, null, primaryId, null);
					store.setMultiSelection(next);
					store.setActiveEffectSection(EditorStore.SECTION_AUDIO);
				}
				default -> {
					return false;
				}
			}
			return true;
		}
	}

	/**
	 * Clears every timeline selection, including the multi-selection (the Escape path).
	 */
	public static final class ClearAll implements EditorCommand {
		@Override
		public boolean apply(EditorStore store) {
			store.clearSelectionsExcept(null, null, null, null, null);
			return true;
		}
	}

	/** Element kinds shared with the UI timeline selection ({@code TimelineSelection}). */
	public static final class TimelineSelections {
		public static final String ZOOM = "zoom";
		public static final String ANNOTATION = "annotation";
		public static final String AUDIO = "audio";
		public static final String CLIP = "clip";
		public static final String CAPTION = "caption";
		public static final String KEYSTROKE = "keystroke";

		private TimelineSelections() {}
	}

	/** Resolves the kind of a region id against the current state, or null when unknown. */
	public static @Nullable String kindOf(EditorStore store, String id) {
		for (var region : store.state().zoomRegions()) {
			if (region.id().equals(id)) {
				return TimelineSelections.ZOOM;
			}
		}
		for (var region : store.state().clipRegions()) {
			if (region.id().equals(id)) {
				return TimelineSelections.CLIP;
			}
		}
		for (var region : store.state().annotationRegions()) {
			if (region.id().equals(id)) {
				return TimelineSelections.ANNOTATION;
			}
		}
		for (var region : store.state().audioRegions()) {
			if (region.id().equals(id)) {
				return TimelineSelections.AUDIO;
			}
		}
		for (var cue : store.state().autoCaptions()) {
			if (cue.id().equals(id)) {
				return TimelineSelections.CAPTION;
			}
		}
		for (var element : store.state().keystrokeElements()) {
			if (element.id().equals(id)) {
				return TimelineSelections.KEYSTROKE;
			}
		}
		return null;
	}
}
