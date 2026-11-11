package dev.gravaai.ui.editor.transcript;

import org.jspecify.annotations.Nullable;

import java.util.List;

import dev.gravaai.core.project.ClipRegion;

/**
 * A one-click transcript cleanup waiting for confirmation: the action, the batch it computed, and
 * the rule needed to recompute that batch when the project changes under the preview.
 *
 * <p>The panel holds one of these instead of a plan plus a separately tracked "which action was
 * that" flag, so the label, undo text and re-run rule can never disagree with the batch on screen.
 */
public sealed interface TranscriptCleanUp {

	/** The button/undo phrase for this cleanup ("Remove filler words", "Shorten pauses"). */
	String label();

	/** The batch computed the last time this cleanup ran. */
	TranscriptCutPlan plan();

	/** Re-runs the same rule against the current words and clips. */
	TranscriptCleanUp recompute(List<TranscriptWords.Word> words, List<ClipRegion> clips);

	/**
	 * Re-runs the rule and drops the cleanup when nothing is left to cut; the panel shows no
	 * preview in that case, exactly as if the user had clicked the button now.
	 */
	default @Nullable TranscriptCleanUp refreshed(List<TranscriptWords.Word> words,
			List<ClipRegion> clips) {
		TranscriptCleanUp next = recompute(words, clips);
		return next.plan().isEmpty() ? null : next;
	}

	/** Language-aware filler removal. */
	record Fillers(TranscriptCutPlan plan, String language) implements TranscriptCleanUp {

		@Override
		public String label() {
			return "Remove filler words";
		}

		@Override
		public TranscriptCleanUp recompute(List<TranscriptWords.Word> words,
				List<ClipRegion> clips) {
			return new Fillers(TranscriptCutPlan.fillers(words, clips, language), language);
		}
	}

	/** Silence shortening with an explicit minimum and kept amount (timeline milliseconds). */
	record Pauses(TranscriptCutPlan plan, long minPauseMs, long keepMs)
			implements TranscriptCleanUp {

		@Override
		public String label() {
			return "Shorten pauses";
		}

		@Override
		public TranscriptCleanUp recompute(List<TranscriptWords.Word> words,
				List<ClipRegion> clips) {
			return new Pauses(TranscriptCutPlan.pauses(words, clips, minPauseMs, keepMs),
					minPauseMs, keepMs);
		}
	}
}
