package dev.gravaai.ui.editor.timeline;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.project.ClipTransition;
import dev.gravaai.ui.editor.timeline.core.Span;

/**
 * Write seam between the timeline view and the editor commands (Lead decision for P2.7: the real
 * {@code EditorStore} commands land from core-storage later; this interface is the one adapter).
 *
 * <p>The view calls these methods for user gestures; the implementation applies the store command,
 * pushes undo history and the view redraws from {@link TimelineDataSource}. Tests record the calls.
 */
public interface TimelineCommands {

	/** A no-op command sink for previews and tests that do not edit. */
	TimelineCommands NONE = new TimelineCommands() {
		@Override
		public void seek(double timeMs) {
		}

		@Override
		public void select(TimelineSelection selection) {
		}

		@Override
		public void clearSelection() {
		}

		@Override
		public void move(String itemId, String rowId, Span span) {
		}

		@Override
		public void resizeEnd(String itemId, Span span) {
		}

		@Override
		public void splitClip(String clipId, double timeMs) {
		}

		@Override
		public void deleteSelection() {
		}

		@Override
		public void addZoom(double startMs, double endMs) {
		}

		@Override
		public void addSuggestedZoom(double startMs, double endMs,
				dev.gravaai.core.project.ZoomFocus focus) {
		}

		@Override
		public boolean addSuggestedLoupe(double startMs, double endMs) {
			return false;
		}

		@Override
		public void deleteZoom(String id) {
		}

		@Override
		public void addCaption(double startMs, double endMs) {
		}
	};

	/** Move the playhead (timeline milliseconds). */
	void seek(double timeMs);

	/** Flips a Camera layout switch between an instant cut and a smooth move. */
	default void toggleCameraLayoutTransition(String segmentId) {}

	/**
	 * Flips the Camera layout switch-out of a segment (into the gap after it) between an instant
	 * cut and a smooth move.
	 */
	default void toggleCameraLayoutTransitionOut(String segmentId) {}

	/**
	 * Live playhead drag (timeline milliseconds): called for every pointer move, so it must not
	 * block; the preview shows the newest position. Defaults to a seek.
	 */
	default void scrub(double timeMs) {
		seek(timeMs);
	}

	/** Select one block; {@code null} clears the selection. */
	void select(TimelineSelection selection);

	/**
	 * Select many blocks of one kind ({@code "zoom"}, {@code "annotation"} or {@code "audio"}), with
	 * {@code primaryId} as the inspector target. The default is a no-op so preview-only command
	 * sinks stay valid.
	 */
	default void selectMany(String kind, String primaryId, java.util.Collection<String> ids) {
	}

	/** Clear every block selection. */
	void clearSelection();

	/**
	 * Commit a drag. For the primary clip row the span is the compacted sequence position (the
	 * editor reorders the clip); other rows are plain moves on their lane.
	 */
	void move(String itemId, String rowId, Span span);

	/**
	 * Commit a multi-selection move: every id of {@code spansByItemId} shifts to its span as one
	 * undoable batch. The default is a no-op so preview-only command sinks stay valid.
	 */
	default void moveMany(@org.jspecify.annotations.Nullable String rowId,
			java.util.Map<String, Span> spansByItemId) {
	}

	/** Commit a resized edge. */
	void resizeEnd(String itemId, Span span);

	/** Split the clip at a timeline time. */
	void splitClip(String clipId, double timeMs);

	/**
	 * Sets the outgoing fade of one clip (Fade through black + duration); {@code null} removes it.
	 * The default is a no-op so preview-only command sinks stay valid.
	 */
	default void setClipFade(String clipId, @Nullable ClipTransition transition) {
	}

	/** Delete the current selection. */
	void deleteSelection();

	/** Add a zoom block for the hover/click ghost in the zoom lane. */
	void addZoom(double startMs, double endMs);

	/**
	 * Edits one source-audio track setting for a clip (inspector volume/normalize). The default is a
	 * no-op so preview-only command sinks stay valid; StoreTimelineCommands wires it once
	 * {@code SourceAudioCommands.SetTrackSettings} lands in core.
	 */
	default void setSourceAudioTrack(String clipId, String trackId, double volume,
			boolean normalize) {
	}

	/** Add a cursor-telemetry suggested zoom with the click-derived focus. */
	void addSuggestedZoom(double startMs, double endMs, dev.gravaai.core.project.ZoomFocus focus);

	/**
	 * Add a magnifying-glass loupe over a press-and-hold span (the lens follows the cursor);
	 * false when a loupe already covers the span.
	 */
	boolean addSuggestedLoupe(double startMs, double endMs);

	/** Delete one zoom block (used by the select-all-zooms Delete action). */
	void deleteZoom(String id);

	/** Add a caption cue for the hover/click ghost in the caption strip. */
	void addCaption(double startMs, double endMs);

	/** Add a color/luminosity grade over the empty-lane click / playhead span on the Color track. */
	default void addGrade(double startMs, double endMs) {
	}

	/**
	 * Add a Camera layout block over a range drawn on the empty Camera track. The block starts on
	 * the project layout (a no-op until its options change) and is trimmed to the gaps beside it.
	 */
	default void addCameraLayout(double startMs, double endMs) {
	}

	/** Add a marker at a timeline time (the {@code M} shortcut); the editor names it. */
	default void addMarker(double timelineMs) {
	}

	/** Move a marker to a timeline position (ruler drag); no-op over a gap. */
	default void moveMarker(String markerId, double timelineMs) {
	}

	/** Rename a marker; a blank name falls back to the generic {@code Marker N}. */
	default void renameMarker(String markerId, String name) {
	}

	/** Promote a marker to a chapter, or demote it when {@code chapter} is false. */
	default void setMarkerChapter(String markerId, boolean chapter) {
	}

	/** Delete one marker. */
	default void deleteMarker(String markerId) {
	}

	/** Select a marker in the editor store (ruler click); {@code null} clears it. */
	default void selectMarker(@org.jspecify.annotations.Nullable String markerId) {
	}
}
