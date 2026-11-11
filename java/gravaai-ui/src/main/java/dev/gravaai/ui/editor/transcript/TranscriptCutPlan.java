package dev.gravaai.ui.editor.transcript;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.gravaai.core.editor.TranscriptCut;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipSequence;
import dev.gravaai.core.project.Clips;

/**
 * A pending batch of transcript cuts (fillers or pauses), computed before anything is applied so
 * the panel can list what will disappear and the timeline can show it.
 *
 * <p>Ranges are source ranges clipped to the footage that is still on the timeline: a word that was
 * already cut is never proposed again, and a pause never proposes silence that a previous edit
 * already removed. Applying the whole plan is one {@code ClipCommands.CutSourceRanges} command, so
 * one undo restores it.
 *
 * @param ranges the source ranges to cut (merged, in source order)
 * @param wordKeys transcript word keys the plan removes (empty for pauses)
 * @param removedMs timeline milliseconds the edit takes off the sequence
 */
public record TranscriptCutPlan(List<TranscriptCut.SourceRange> ranges, Set<String> wordKeys,
		long removedMs) {

	public TranscriptCutPlan {
		ranges = List.copyOf(ranges);
		wordKeys = Set.copyOf(wordKeys);
	}

	/** True when there is nothing to apply (no words/pauses left matching the rule). */
	public boolean isEmpty() {
		return ranges.isEmpty();
	}

	/** Total cut source time; the timeline loss differs when a clip is sped up. */
	public long removedSourceMs() {
		long total = 0;
		for (TranscriptCut.SourceRange range : ranges) {
			total += range.endMs() - range.startMs();
		}
		return total;
	}

	/** Filler-word plan for the words still on the timeline. */
	public static TranscriptCutPlan fillers(List<TranscriptWords.Word> words,
			List<ClipRegion> clips, String language) {
		Set<String> fillers = new LinkedHashSet<>(TranscriptWords.selectFillerWords(words, language));
		Set<String> kept = new LinkedHashSet<>();
		for (TranscriptWords.Word word : words) {
			if (fillers.contains(word.key()) && TranscriptWords.isWordKept(word, clips)) {
				kept.add(word.key());
			}
		}
		if (kept.isEmpty()) {
			return new TranscriptCutPlan(List.of(), Set.of(), 0);
		}
		List<TranscriptCut.SourceRange> ranges = TranscriptWords.wordsToCutRanges(words, kept,
				TranscriptWords.DEFAULT_TRAILING_GAP_MS);
		List<TranscriptCut.SourceRange> clipped = clipToKept(ranges, clips);
		return new TranscriptCutPlan(clipped, kept, computeRemovedTimelineMs(clipped, clips));
	}

	/**
	 * Pause plan: every silence of at least {@code minPauseMs} between two words that are still on
	 * the timeline is shortened to {@code keepMs}.
	 *
	 * <p>The measurement happens in timeline time, not source time. After an earlier edit (a filler
	 * cut, a manual cut, a speed change) two words can sit next to each other with a pause the
	 * source clock does not show, or with a source gap the timeline no longer has; only the edited
	 * timeline says what the viewer actually hears. The resulting timeline spans are mapped back to
	 * source ranges so the cuts apply through the normal clip command.
	 */
	public static TranscriptCutPlan pauses(List<TranscriptWords.Word> words,
			List<ClipRegion> clips, long minPauseMs, long keepMs) {
		List<TranscriptCut.SourceRange> spoken = new ArrayList<>();
		for (TranscriptWords.Word word : words) {
			TranscriptCut.SourceRange span = timelineSpanMs(word, clips);
			if (span != null) {
				spoken.add(span);
			}
		}
		spoken.sort(Comparator.comparingLong(TranscriptCut.SourceRange::startMs));
		List<TranscriptCut.SourceRange> pauses = TranscriptCut.pauseRanges(spoken, minPauseMs,
				Math.max(0, keepMs));
		List<TranscriptCut.SourceRange> ranges = sourceRangesForTimelineSpans(pauses, clips);
		return new TranscriptCutPlan(ranges, Set.of(), computeRemovedTimelineMs(ranges, clips));
	}

	/**
	 * The word's span in timeline milliseconds, clamped to the clip that holds it (a word whose
	 * edge was cut only counts from the footage that remains). Null when no clip holds its centre.
	 */
	static TranscriptCut.@Nullable SourceRange timelineSpanMs(TranscriptWords.Word word,
			List<ClipRegion> clips) {
		double midpointMs = (word.startMs() + word.endMs()) / 2.0;
		for (ClipRegion clip : clips) {
			long clipStartMs = Clips.getClipSourceStartMs(clip);
			long clipEndMs = Clips.getClipSourceEndMs(clip);
			if (midpointMs < clipStartMs || midpointMs >= clipEndMs) {
				continue;
			}
			double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
			long startMs = Math.round(clip.startMs()
					+ (Math.max(word.startMs(), clipStartMs) - clipStartMs) / speed);
			long endMs = Math.round(clip.startMs()
					+ (Math.min(word.endMs(), clipEndMs) - clipStartMs) / speed);
			return endMs > startMs ? new TranscriptCut.SourceRange(startMs, endMs) : null;
		}
		return null;
	}

	/** Maps timeline spans back to source ranges, one range per clip they cover. */
	static List<TranscriptCut.SourceRange> sourceRangesForTimelineSpans(
			List<TranscriptCut.SourceRange> timelineSpans, List<ClipRegion> clips) {
		List<TranscriptCut.SourceRange> ranges = new ArrayList<>();
		for (TranscriptCut.SourceRange span : timelineSpans) {
			for (ClipRegion clip : clips) {
				long startMs = Math.max(span.startMs(), clip.startMs());
				long endMs = Math.min(span.endMs(), clip.endMs());
				if (endMs <= startMs) {
					continue;
				}
				double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
				long sourceStartMs = Clips.getClipSourceStartMs(clip);
				ranges.add(new TranscriptCut.SourceRange(
						Math.round(sourceStartMs + (startMs - clip.startMs()) * speed),
						Math.round(sourceStartMs + (endMs - clip.startMs()) * speed)));
			}
		}
		return TranscriptCut.mergeSourceRanges(ranges);
	}

	/** Intersects source ranges with the clips' source windows, merging the pieces. */
	static List<TranscriptCut.SourceRange> clipToKept(List<TranscriptCut.SourceRange> ranges,
			List<ClipRegion> clips) {
		List<TranscriptCut.SourceRange> pieces = new ArrayList<>();
		for (TranscriptCut.SourceRange range : TranscriptCut.mergeSourceRanges(ranges)) {
			for (ClipRegion clip : clips) {
				long clipStartMs = Clips.getClipSourceStartMs(clip);
				long clipEndMs = Clips.getClipSourceEndMs(clip);
				long startMs = Math.max(range.startMs(), clipStartMs);
				long endMs = Math.min(range.endMs(), clipEndMs);
				if (endMs > startMs) {
					pieces.add(new TranscriptCut.SourceRange(startMs, endMs));
				}
			}
		}
		return TranscriptCut.mergeSourceRanges(pieces);
	}

	/**
	 * Timeline duration the cuts take off: the cuts are replayed on a copy of the sequence and the
	 * packed result is measured, so clip speed is accounted for.
	 */
	static long computeRemovedTimelineMs(List<TranscriptCut.SourceRange> ranges,
			List<ClipRegion> clips) {
		if (ranges.isEmpty()) {
			return 0;
		}
		long beforeMs = endMs(clips);
		long[] nextId = {0};
		List<ClipRegion> next = TranscriptCut.removeSourceRangesFromClips(clips, ranges,
				() -> "transcript-preview-" + nextId[0]++);
		if (next == clips) {
			return 0;
		}
		long afterMs = 0;
		for (ClipRegion clip : ClipSequence.packClipSequence(next)) {
			afterMs = Math.max(afterMs, clip.endMs());
		}
		return Math.max(0, beforeMs - afterMs);
	}

	private static long endMs(List<ClipRegion> clips) {
		long end = 0;
		for (ClipRegion clip : clips) {
			end = Math.max(end, clip.endMs());
		}
		return end;
	}
}
