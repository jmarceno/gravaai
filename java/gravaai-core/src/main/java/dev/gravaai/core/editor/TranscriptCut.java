package dev.gravaai.core.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;

/**
 * Source-range cuts used by the clip commands and the transcript panel (port of the
 * {@code removeSourceRangesFromClips} part of {@code transcriptCut.ts}).
 */
public final class TranscriptCut {

	/** A source-time range (milliseconds); TS {@code SourceRange}. */
	public record SourceRange(long startMs, long endMs) {}

	private TranscriptCut() {}

	/** {@code mergeSourceRanges}: overlap/touching ranges become one cut. */
	public static List<SourceRange> mergeSourceRanges(List<SourceRange> ranges) {
		List<SourceRange> sorted = new ArrayList<>();
		for (SourceRange range : ranges) {
			if (range != null && range.endMs() > range.startMs()) {
				sorted.add(new SourceRange(Math.round((double) range.startMs()),
						Math.round((double) range.endMs())));
			}
		}
		sorted.sort((left, right) -> Long.compare(left.startMs(), right.startMs()));

		List<SourceRange> merged = new ArrayList<>();
		for (SourceRange range : sorted) {
			SourceRange previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
			if (previous != null && range.startMs() <= previous.endMs()) {
				merged.set(merged.size() - 1,
						new SourceRange(previous.startMs(), Math.max(previous.endMs(), range.endMs())));
				continue;
			}
			merged.add(range);
		}
		return merged;
	}

	/**
	 * {@code removeSourceRangesFromClips}: subtract source ranges from every clip, splitting the
	 * clips that survive. The returned pieces use {@code startMs: 0} + a duration because the caller
	 * repacks the sequence. Returns the same list instance when the cut would delete everything.
	 */
	public static List<ClipRegion> removeSourceRangesFromClips(
			List<ClipRegion> clips, List<SourceRange> ranges, Supplier<String> createId) {
		List<SourceRange> cuts = mergeSourceRanges(ranges);
		if (cuts.isEmpty()) {
			return clips;
		}

		boolean changed = false;
		List<ClipRegion> next = new ArrayList<>();
		for (ClipRegion clip : Clips.sortClipRegions(clips)) {
			long sourceStartMs = Clips.getClipSourceStartMs(clip);
			long sourceEndMs = Clips.getClipSourceEndMs(clip);
			double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;

			List<long[]> pieces = new ArrayList<>();
			pieces.add(new long[] {sourceStartMs, sourceEndMs});
			for (SourceRange cut : cuts) {
				List<long[]> split = new ArrayList<>();
				for (long[] piece : pieces) {
					long start = piece[0];
					long end = piece[1];
					if (cut.endMs() <= start || cut.startMs() >= end) {
						split.add(piece);
						continue;
					}
					if (cut.startMs() > start) {
						split.add(new long[] {start, Math.min(cut.startMs(), end)});
					}
					if (cut.endMs() < end) {
						split.add(new long[] {Math.max(cut.endMs(), start), end});
					}
				}
				pieces = split;
			}

			if (pieces.size() == 1 && pieces.get(0)[0] == sourceStartMs
					&& pieces.get(0)[1] == sourceEndMs) {
				next.add(clip);
				continue;
			}
			changed = true;
			List<long[]> keptPieces = new ArrayList<>();
			for (long[] piece : pieces) {
				if (piece[1] - piece[0] >= 1) {
					keptPieces.add(piece);
				}
			}
			for (int index = 0; index < keptPieces.size(); index++) {
				long[] piece = keptPieces.get(index);
				long pieceStart = piece[0];
				long pieceEnd = piece[1];
				next.add(new ClipRegion(index == 0 ? clip.id() : createId.get(),
						0, Math.max(1, Math.round((pieceEnd - pieceStart) / speed)),
						pieceStart, clip.sourceMinMs(), clip.sourceMaxMs(), clip.speed(),
						clip.muted(), clip.showSourceAudio(), clip.cameraLayout(),
						index < keptPieces.size() - 1 ? null : clip.transitionOut()));
			}
		}

		if (!changed || next.isEmpty()) {
			return clips;
		}
		return next;
	}
}
