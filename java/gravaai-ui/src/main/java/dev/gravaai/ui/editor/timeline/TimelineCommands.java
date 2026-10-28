package dev.gravaai.ui.editor.timeline;

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
		public void deleteZoom(String id) {
		}

		@Override
		public void addCaption(double startMs, double endMs) {
		}
	};

	/** Move the playhead (timeline milliseconds). */
	void seek(double timeMs);

	/**
	 * Live playhead drag (timeline milliseconds): called for every pointer move, so it must not
	 * block; the preview shows the newest position. Defaults to a seek.
	 */
	default void scrub(double timeMs) {
		seek(timeMs);
	}

	/** Select one block; {@code null} clears the selection. */
	void select(TimelineSelection selection);

	/** Clear every block selection. */
	void clearSelection();

	/**
	 * Commit a drag. For the primary clip row the span is the compacted sequence position (the
	 * editor reorders the clip); other rows are plain moves on their lane.
	 */
	void move(String itemId, String rowId, Span span);

	/** Commit a resized edge. */
	void resizeEnd(String itemId, Span span);

	/** Split the clip at a timeline time. */
	void splitClip(String clipId, double timeMs);

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

	/** Delete one zoom block (used by the select-all-zooms Delete action). */
	void deleteZoom(String id);

	/** Add a caption cue for the hover/click ghost in the caption strip. */
	void addCaption(double startMs, double endMs);
}
