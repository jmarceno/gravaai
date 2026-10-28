package dev.gravaai.ui.editor.captions;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionEditing;
import dev.gravaai.core.project.CaptionOps;

/**
 * Pure decisions of the caption list editor (port of the {@code CaptionListPanel} helpers and the
 * commit rules in {@code useCaptionCommands}): timecode parsing/formatting, split clamping, merge
 * availability, and the commit decisions for text and timing drafts.
 */
public final class CaptionListModel {

	private CaptionListModel() {
	}

	/** Port of {@code formatTimecode}: {@code m:ss.SSS}. */
	public static String formatTimecode(double ms) {
		long safeMs = Math.max(0, Math.round(ms));
		long minutes = safeMs / 60_000;
		long seconds = (safeMs % 60_000) / 1_000;
		long millis = safeMs % 1_000;
		return minutes + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds) + "."
				+ String.format(java.util.Locale.ROOT, "%03d", millis);
	}

	/** Port of {@code parseTimecode}; empty when the draft is not a valid timecode. */
	public static OptionalDouble parseTimecode(String value) {
		if (value == null) {
			return OptionalDouble.empty();
		}
		java.util.regex.Matcher match = java.util.regex.Pattern
				.compile("^(?:(\\d+):)?(\\d{1,2})(?:\\.(\\d{1,3}))?$").matcher(value.trim());
		if (!match.matches()) {
			return OptionalDouble.empty();
		}
		long minutes = match.group(1) == null ? 0 : Long.parseLong(match.group(1));
		long seconds = Long.parseLong(match.group(2));
		String millisText = match.group(3) == null ? ""
				: (match.group(3) + "000").substring(0, 3);
		long millis = millisText.isEmpty() ? 0 : Long.parseLong(millisText);
		return OptionalDouble.of((minutes * 60 + seconds) * 1_000 + millis);
	}

	/** Index of the selected cue, or -1 (the panel hides when it is -1). */
	public static int selectedIndex(List<CaptionCue> cues, String selectedCaptionId) {
		if (cues == null || selectedCaptionId == null) {
			return -1;
		}
		for (int index = 0; index < cues.size(); index++) {
			if (selectedCaptionId.equals(cues.get(index).id())) {
				return index;
			}
		}
		return -1;
	}

	/** Port of {@code canMerge}: only when a later cue exists. */
	public static boolean canMerge(int index, int cueCount) {
		return index >= 0 && index < cueCount - 1;
	}

	/** Port of the {@code clampNumber(currentTimeMs, cue.startMs, cue.endMs)} split position. */
	public static double splitAt(CaptionCue cue, double currentTimeMs) {
		return Math.min(cue.endMs(), Math.max(cue.startMs(), currentTimeMs));
	}

	/**
	 * Port of {@code commitText}: returns the normalized text when it is non-empty and different,
	 * else null (the panel then restores the stored text).
	 */
	public static String decideTextCommit(String draft, CaptionCue cue) {
		if (cue == null) {
			return null;
		}
		String normalized = CaptionEditing.normalizeCaptionEditText(draft == null ? "" : draft);
		if (normalized.isEmpty() || normalized.equals(cue.text())) {
			return null;
		}
		return normalized;
	}

	/** One committed timing range in milliseconds. */
	public record TimingCommit(long startMs, long endMs) {
	}

	/**
	 * Port of {@code commitTiming}: parses both drafts, rejects unparsable or inverted ranges and
	 * returns null when the timing did not change.
	 */
	public static TimingCommit decideTimingCommit(String startDraft, String endDraft,
			CaptionCue cue) {
		if (cue == null) {
			return null;
		}
		OptionalDouble start = parseTimecode(startDraft);
		OptionalDouble end = parseTimecode(endDraft);
		if (start.isEmpty() || end.isEmpty() || end.getAsDouble() <= start.getAsDouble()) {
			return null;
		}
		long startMs = Math.round(start.getAsDouble());
		long endMs = Math.round(end.getAsDouble());
		if (startMs == cue.startMs() && endMs == cue.endMs()) {
			return null;
		}
		return new TimingCommit(startMs, endMs);
	}

	/**
	 * Port of the {@code CaptionEditTarget} built by {@code useCaptionCommands.handleCaptionTextEdit}
	 * for a cue with word timings (the store's {@code CaptionCommands.TextEdit} performs the same
	 * word-aware update, this helper exposes the target for tests and the inline editor).
	 */
	public static CaptionEditing.EditTarget editTarget(CaptionCue cue) {
		var words = CaptionEditing.normalizeCaptionWords(cue);
		java.util.List<CaptionEditing.WordRef> refs = new java.util.ArrayList<>(words.size());
		for (int index = 0; index < words.size(); index++) {
			var word = words.get(index);
			refs.add(new CaptionEditing.WordRef(cue.id(), index, word.startMs(), word.endMs(),
					word.text(), Boolean.TRUE.equals(word.leadingSpace())));
		}
		return new CaptionEditing.EditTarget(cue.id(), cue.startMs(), cue.endMs(), cue.text(),
				refs);
	}

	/** Port of the deleted-cue selection rule: dropping the selected cue clears the selection. */
	public static String selectionAfterDelete(String selectedCaptionId, String deletedId) {
		return deletedId != null && deletedId.equals(selectedCaptionId) ? null : selectedCaptionId;
	}

	/** Finds the next cue for a merge action (the cue after {@code index}). */
	public static Optional<CaptionCue> mergeTarget(List<CaptionCue> cues, int index) {
		if (cues == null || !canMerge(index, cues.size())) {
			return Optional.empty();
		}
		return Optional.of(cues.get(index + 1));
	}

	/** Convenience for the panel: the retime span record used by {@code CaptionCommands.Retime}. */
	public static CaptionOps.RetimeSpan retimeSpan(TimingCommit commit) {
		return new CaptionOps.RetimeSpan(commit.startMs(), commit.endMs());
	}
}
