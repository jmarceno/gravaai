package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Caption cue operations shared by the timeline and the editor (port of {@code captionOps.ts}). */
public final class CaptionOps {

	/** {@code CaptionRetimeSpan}. */
	public record RetimeSpan(long startMs, long endMs) {}

	private static final Comparator<CaptionCue> CUE_ORDER = Comparator
			.comparingLong(CaptionCue::startMs)
			.thenComparingLong(CaptionCue::endMs);

	private CaptionOps() {}

	private static List<CaptionCue> sortCaptionCues(List<CaptionCue> cues) {
		List<CaptionCue> sorted = new ArrayList<>(cues);
		sorted.sort(CUE_ORDER);
		return sorted;
	}

	private static String createCaptionCueId() {
		return "caption-" + UUID.randomUUID();
	}

	/** {@code createCaptionCue}: a new cue with a generated id and no word timings. */
	public static CaptionCue createCaptionCue(double startMs, double endMs, String text) {
		long normalizedStartMs = Math.round(startMs);
		return new CaptionCue(createCaptionCueId(), normalizedStartMs,
				Math.max(normalizedStartMs + 1, Math.round(endMs)), text == null ? "" : text, null);
	}

	/** {@code addCue}: insert and keep the list ordered. */
	public static List<CaptionCue> addCue(List<CaptionCue> cues, CaptionCue cue) {
		List<CaptionCue> next = new ArrayList<>(cues);
		next.add(cue);
		return sortCaptionCues(next);
	}

	private static List<CaptionCueWord> rescaleWordsIntoSpan(
			List<CaptionCueWord> words, long oldStartMs, long oldEndMs, long newStartMs, long newEndMs) {
		long oldSpan = oldEndMs - oldStartMs;
		long newSpan = newEndMs - newStartMs;
		double factor = oldSpan > 0 ? (double) newSpan / oldSpan : 0;

		long cursorMs = newStartMs;
		List<CaptionCueWord> rescaled = new ArrayList<>(words.size());
		for (int index = 0; index < words.size(); index++) {
			CaptionCueWord word = words.get(index);
			double mappedStartMs = oldSpan > 0
					? newStartMs + (word.startMs() - oldStartMs) * factor
					: newStartMs + (newSpan * (double) index) / Math.max(1, words.size());
			double mappedEndMs = oldSpan > 0
					? newStartMs + (word.endMs() - oldStartMs) * factor
					: newStartMs + (newSpan * (double) (index + 1)) / Math.max(1, words.size());

			long startMs = Math.min(newEndMs - 1, Math.max(cursorMs, Math.round(mappedStartMs)));
			long endMs = Math.min(newEndMs, Math.max(startMs + 1, Math.round(mappedEndMs)));
			cursorMs = endMs;
			rescaled.add(new CaptionCueWord(word.text(), startMs, endMs,
					Boolean.TRUE.equals(word.leadingSpace()) ? Boolean.TRUE : null));
		}
		return CaptionEditing.normalizeCaptionWordSpacing(rescaled);
	}

	/** {@code retimeCue}. */
	public static List<CaptionCue> retimeCue(List<CaptionCue> cues, String id, RetimeSpan span) {
		List<CaptionCue> sorted = sortCaptionCues(cues);
		int index = -1;
		for (int candidate = 0; candidate < sorted.size(); candidate++) {
			if (sorted.get(candidate).id().equals(id)) {
				index = candidate;
				break;
			}
		}
		if (index < 0) {
			return cues;
		}

		CaptionCue cue = sorted.get(index);
		long newStartMs = Math.max(0, Math.round((double) span.startMs()));
		long requestedEndMs = Math.max(newStartMs + 1, Math.round((double) span.endMs()));

		List<CaptionCueWord> words = cue.words() != null && !cue.words().isEmpty()
				? CaptionEditing.normalizeCaptionWords(cue)
				: List.of();
		long newEndMs = !words.isEmpty()
				? Math.max(requestedEndMs, newStartMs + words.size())
				: requestedEndMs;

		if (newStartMs == cue.startMs() && newEndMs == cue.endMs()) {
			return cues;
		}

		List<CaptionCueWord> nextWords = !words.isEmpty()
				? rescaleWordsIntoSpan(words, cue.startMs(), cue.endMs(), newStartMs, newEndMs)
				: null;
		CaptionCue nextCue = new CaptionCue(cue.id(), newStartMs, newEndMs,
				nextWords != null ? CaptionEditing.captionWordsToText(nextWords) : cue.text(),
				nextWords);

		List<CaptionCue> nextCues = new ArrayList<>(sorted);
		nextCues.set(index, nextCue);
		return sortCaptionCues(nextCues);
	}

	/** {@code splitCue}: split at the nearest word boundary. */
	public static List<CaptionCue> splitCue(List<CaptionCue> cues, String id, double atMs) {
		List<CaptionCue> sorted = sortCaptionCues(cues);
		int index = -1;
		for (int candidate = 0; candidate < sorted.size(); candidate++) {
			if (sorted.get(candidate).id().equals(id)) {
				index = candidate;
				break;
			}
		}
		if (index < 0) {
			return cues;
		}

		CaptionCue cue = sorted.get(index);
		List<CaptionCueWord> words = CaptionEditing.normalizeCaptionWords(cue);
		if (words.size() < 2) {
			return cues;
		}

		int splitIndex = 1;
		double nearestDistance = Double.POSITIVE_INFINITY;
		for (int candidate = 1; candidate < words.size(); candidate++) {
			double boundaryMs = (words.get(candidate - 1).endMs() + (double) words.get(candidate).startMs()) / 2;
			double distance = Math.abs(boundaryMs - atMs);
			if (distance < nearestDistance) {
				nearestDistance = distance;
				splitIndex = candidate;
			}
		}

		List<CaptionCueWord> leftWords = CaptionEditing.normalizeCaptionWordSpacing(
				new ArrayList<>(words.subList(0, splitIndex)));
		List<CaptionCueWord> rightWords = CaptionEditing.normalizeCaptionWordSpacing(
				new ArrayList<>(words.subList(splitIndex, words.size())));

		CaptionCue leftCue = new CaptionCue(cue.id(), cue.startMs(),
				leftWords.get(leftWords.size() - 1).endMs(),
				CaptionEditing.captionWordsToText(leftWords), leftWords);
		CaptionCue rightCue = new CaptionCue(createCaptionCueId(), rightWords.get(0).startMs(),
				cue.endMs(), CaptionEditing.captionWordsToText(rightWords), rightWords);

		List<CaptionCue> nextCues = new ArrayList<>();
		for (int valueIndex = 0; valueIndex < sorted.size(); valueIndex++) {
			if (valueIndex == index) {
				nextCues.add(leftCue);
				nextCues.add(rightCue);
			} else {
				nextCues.add(sorted.get(valueIndex));
			}
		}
		return sortCaptionCues(nextCues);
	}

	/** {@code mergeCues}: merge two adjacent cues. */
	public static List<CaptionCue> mergeCues(List<CaptionCue> cues, String idA, String idB) {
		List<CaptionCue> sorted = sortCaptionCues(cues);
		int indexA = -1;
		int indexB = -1;
		for (int index = 0; index < sorted.size(); index++) {
			if (sorted.get(index).id().equals(idA)) {
				indexA = index;
			}
			if (sorted.get(index).id().equals(idB)) {
				indexB = index;
			}
		}
		if (indexA < 0 || indexB < 0 || Math.abs(indexA - indexB) != 1) {
			return cues;
		}

		int leftIndex = Math.min(indexA, indexB);
		CaptionCue left = sorted.get(leftIndex);
		CaptionCue right = sorted.get(leftIndex + 1);
		List<CaptionCueWord> mergedWords = new ArrayList<>();
		mergedWords.addAll(CaptionEditing.normalizeCaptionWords(left));
		mergedWords.addAll(CaptionEditing.normalizeCaptionWords(right));
		mergedWords = CaptionEditing.normalizeCaptionWordSpacing(mergedWords);

		CaptionCue mergedCue = new CaptionCue(left.id(), left.startMs(), right.endMs(),
				CaptionEditing.captionWordsToText(mergedWords), mergedWords);

		List<CaptionCue> nextCues = new ArrayList<>();
		for (int valueIndex = 0; valueIndex < sorted.size(); valueIndex++) {
			if (valueIndex == leftIndex) {
				nextCues.add(mergedCue);
			} else if (valueIndex != leftIndex + 1) {
				nextCues.add(sorted.get(valueIndex));
			}
		}
		return sortCaptionCues(nextCues);
	}

	/** {@code deleteCue}. */
	public static List<CaptionCue> deleteCue(List<CaptionCue> cues, String id) {
		List<CaptionCue> next = new ArrayList<>();
		for (CaptionCue cue : cues) {
			if (!cue.id().equals(id)) {
				next.add(cue);
			}
		}
		return next.size() == cues.size() ? cues : sortCaptionCues(next);
	}
}
