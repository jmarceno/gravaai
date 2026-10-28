package dev.gravaai.ui.editor.transcript;

import java.util.List;

import dev.gravaai.core.editor.TranscriptCut;
import dev.gravaai.core.project.ClipRegion;

/**
 * Cut seam for the transcript panel. The UI selects words and emits source {@link
 * TranscriptCut.SourceRange}s; the app decides how they are applied (EditorStore
 * {@code CutSourceRanges} in the default {@link StoreTranscriptCutController}, or the
 * {@code cut_pauses}/{@code cut_fillers} planners in services.agent).
 */
public interface TranscriptCutController {

	/** Applies source-range cuts; returns false when the cut would remove the whole video. */
	boolean cut(List<TranscriptCut.SourceRange> ranges);

	/** Undo the last edit (the store command history); false when there is nothing to undo. */
	default boolean undo() {
		return false;
	}

	/** Optional planner seam: select/cut filler words through services.agent. */
	default boolean cutFillers(String language) {
		return false;
	}

	/** Optional planner seam: cut pauses through services.agent. */
	default boolean cutPauses(double minPauseMs, double keepMs) {
		return false;
	}

	/** Optional restore_words planner seam (only footage a cut borders can be restored). */
	default boolean restoreWords(List<String> wordKeys) {
		return false;
	}

	/** Clips after the cut, for tests/shell inspection. */
	default List<ClipRegion> clips() {
		return List.of();
	}
}
