package dev.gravaai.ui.editor.transcript;

import org.jspecify.annotations.Nullable;

import java.util.List;

import dev.gravaai.core.editor.TranscriptCut;
import dev.gravaai.core.project.ClipRegion;

/**
 * Cut seam for the transcript panel. The UI selects words (or a one-click filler/pause batch) and
 * emits source {@link TranscriptCut.SourceRange}s; the app decides how they are applied — the
 * default {@link StoreTranscriptCutController} issues one {@code ClipCommands.CutSourceRanges}
 * command to the {@code EditorStore}, so one undo restores the batch.
 */
public interface TranscriptCutController {

	/** Applies source-range cuts; returns false when the cut would remove the whole video. */
	boolean cut(List<TranscriptCut.SourceRange> ranges);

	/**
	 * Applies source-range cuts with a history label (the one-click transcript actions), so the
	 * undo menu says what it restores. A null label records an unlabeled step.
	 */
	default boolean cut(List<TranscriptCut.SourceRange> ranges, @Nullable String undoLabel) {
		return cut(ranges);
	}

	/** Undo the last edit (the store command history); false when there is nothing to undo. */
	default boolean undo() {
		return false;
	}

	/** Clip regions after the cut, for tests/shell inspection. */
	default List<ClipRegion> clips() {
		return List.of();
	}
}
