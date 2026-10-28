package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Caption word/line/page layout (port of {@code src/components/video-editor/captionLayout.ts}).
 *
 * <p>The TS code measures text through a {@code measureText} callback because it draws on a canvas;
 * here it is a {@link ToDoubleFunction} so the port stays UI-free (the compositor supplies the Skija
 * text measurement later, plan §5.8).
 */
public final class CaptionLayout {

	public enum WordState {
		SPOKEN, ACTIVE, UPCOMING
	}

	public record WordLayout(
			String cueId,
			int cueWordIndex,
			String text,
			int index,
			boolean forcedBreakBefore,
			boolean leadingSpace,
			double startMs,
			double endMs,
			boolean hasRealTiming,
			WordState state) {}

	public record LineLayout(
			List<WordLayout> words, double width, int startWordIndex, int endWordIndex) {}

	public record PageLayout(List<LineLayout> lines, double startMs, double endMs) {}

	/** A word of the flattened caption flow (all cues in timeline order). */
	public record FlatWord(
			String cueId,
			int cueWordIndex,
			String text,
			boolean forcedBreakBefore,
			boolean leadingSpace,
			double startMs,
			double endMs,
			boolean hasRealTiming) {}

	public record ActiveCaptionLayout(
			CaptionCue cue,
			String blockKey,
			List<LineLayout> visibleLines,
			boolean hasWordTimings,
			int activeWordIndex,
			double activeWordProgress,
			CaptionEditing.EditTarget editTarget,
			int visiblePageIndex,
			double opacity,
			double translateY,
			double scale) {}

	private static final double CAPTION_ENTER_MS = 180;
	private static final double CAPTION_EXIT_MS = CAPTION_ENTER_MS;
	private static final double CAPTION_BLOCK_GAP_BREAK_MS = 500;

	private CaptionLayout() {}

	private static double clamp(double value, double min, double max) {
		return Math.min(max, Math.max(min, value));
	}

	private static double clamp01(double value) {
		return clamp(value, 0, 1);
	}

	private static boolean isFiniteNumber(Double value) {
		return value != null && Double.isFinite(value);
	}

	private record SourceWord(
			String cueId,
			int cueWordIndex,
			String text,
			boolean forcedBreakBefore,
			Boolean leadingSpace,
			Double startMs,
			Double endMs) {}

	private static List<SourceWord> splitCaptionWordsFromText(String text) {
		List<SourceWord> words = new ArrayList<>();
		String[] sourceLines = text.split("\\R");
		for (int lineIndex = 0; lineIndex < sourceLines.length; lineIndex++) {
			String line = sourceLines[lineIndex].strip();
			if (line.isEmpty()) {
				continue;
			}
			String[] tokens = line.split("\\s+");
			int wordIndex = 0;
			for (String token : tokens) {
				if (token.isEmpty()) {
					continue;
				}
				words.add(new SourceWord("", words.size(), token,
						lineIndex > 0 && wordIndex == 0, null, null, null));
				wordIndex++;
			}
		}
		return words;
	}

	private static List<SourceWord> splitCaptionWords(CaptionCue cue) {
		if (cue.words() != null && !cue.words().isEmpty()) {
			List<SourceWord> words = new ArrayList<>();
			int cueWordIndex = 0;
			for (CaptionCueWord word : cue.words()) {
				if (word == null || word.text() == null) {
					continue;
				}
				String text = word.text().strip();
				if (!text.isEmpty()) {
					words.add(new SourceWord(cue.id(), cueWordIndex, text, false,
							Boolean.TRUE.equals(word.leadingSpace()), (double) word.startMs(),
							(double) word.endMs()));
				}
				cueWordIndex++;
			}
			if (!words.isEmpty()) {
				return words;
			}
		}

		List<SourceWord> fromText = new ArrayList<>();
		for (SourceWord word : splitCaptionWordsFromText(cue.text())) {
			fromText.add(new SourceWord(cue.id(), word.cueWordIndex(), word.text(),
					word.forcedBreakBefore(), word.leadingSpace(), word.startMs(), word.endMs()));
		}
		return fromText;
	}

	private static CaptionCue getActiveCaptionCue(List<CaptionCue> cues, double timeMs) {
		for (CaptionCue cue : cues) {
			if (timeMs >= cue.startMs() && timeMs <= cue.endMs()) {
				return cue;
			}
		}
		return null;
	}

	private static boolean isWithinCaptionCoverage(List<CaptionCue> cues, double timeMs) {
		List<CaptionCue> sorted = new ArrayList<>(cues);
		sorted.sort((left, right) -> Long.compare(left.startMs(), right.startMs()));
		for (int index = 0; index < sorted.size(); index++) {
			CaptionCue cue = sorted.get(index);
			if (timeMs < cue.startMs()) {
				return false;
			}
			CaptionCue next = index + 1 < sorted.size() ? sorted.get(index + 1) : null;
			boolean bridgesGap = next != null
					&& next.startMs() - cue.endMs() < CAPTION_BLOCK_GAP_BREAK_MS;
			double effectiveEndMs = bridgesGap ? Math.max(cue.endMs(), next.startMs()) : cue.endMs();
			if (timeMs <= effectiveEndMs) {
				return true;
			}
		}
		return false;
	}

	/** {@code flattenCaptionWords}. */
	public static List<FlatWord> flattenCaptionWords(List<CaptionCue> cues) {
		List<FlatWord> flattened = new ArrayList<>();
		for (int cueIndex = 0; cueIndex < cues.size(); cueIndex++) {
			CaptionCue cue = cues.get(cueIndex);
			List<SourceWord> sourceWords = splitCaptionWords(cue);
			if (sourceWords.isEmpty()) {
				continue;
			}

			boolean cueHasRealWordTimings = true;
			for (SourceWord word : sourceWords) {
				if (!isFiniteNumber(word.startMs()) || !isFiniteNumber(word.endMs())
						|| word.endMs() <= word.startMs()) {
					cueHasRealWordTimings = false;
					break;
				}
			}
			double cueDuration = Math.max(1, cue.endMs() - cue.startMs());
			double fallbackWordDuration = cueDuration / sourceWords.size();
			boolean shouldForceCueBreak = cueIndex > 0;

			for (int wordIndex = 0; wordIndex < sourceWords.size(); wordIndex++) {
				SourceWord word = sourceWords.get(wordIndex);
				double fallbackStartMs = cue.startMs() + fallbackWordDuration * wordIndex;
				double fallbackEndMs = wordIndex == sourceWords.size() - 1
						? cue.endMs()
						: cue.startMs() + fallbackWordDuration * (wordIndex + 1);
				boolean startsMergedCue =
						wordIndex == 0 && !word.forcedBreakBefore() && !shouldForceCueBreak;
				boolean leadingSpace = wordIndex == 0
						? !flattened.isEmpty() && startsMergedCue
						: word.leadingSpace() == null || word.leadingSpace();

				flattened.add(new FlatWord(
						cue.id(), word.cueWordIndex(), word.text(),
						word.forcedBreakBefore() || (wordIndex == 0 && shouldForceCueBreak),
						leadingSpace,
						cueHasRealWordTimings ? word.startMs() : fallbackStartMs,
						cueHasRealWordTimings
								? word.endMs()
								: Math.max(fallbackStartMs + 1, fallbackEndMs),
						cueHasRealWordTimings));
			}
		}
		return flattened;
	}

	private record AnimationState(double opacity, double translateY, double scale) {}

	private static AnimationState getCaptionAnimationState(
			AutoCaptionAnimation animationStyle, double enterProgress, double exitProgress) {
		double visibility = Math.min(enterProgress, exitProgress);
		return switch (animationStyle) {
			case NONE -> new AnimationState(1, 0, 1);
			case FADE -> new AnimationState(0.3 + visibility * 0.7, 0, 1);
			case RISE -> new AnimationState(0.25 + visibility * 0.75, (1 - visibility) * 18,
					0.985 + visibility * 0.015);
			case POP -> new AnimationState(0.35 + visibility * 0.65, (1 - visibility) * 8,
					0.94 + visibility * 0.06);
		};
	}

	private static final class MutablePage {
		private final List<LineLayout> lines;
		private double startMs;
		private double endMs;

		MutablePage(List<LineLayout> lines, double startMs, double endMs) {
			this.lines = lines;
			this.startMs = startMs;
			this.endMs = endMs;
		}
	}

	private static List<PageLayout> buildCaptionPages(
			List<LineLayout> lines, List<WordLayout> words, int maxRows,
			boolean hasWordTimings, CaptionCue cue) {
		List<MutablePage> pages = new ArrayList<>();
		int totalPages = Math.max(1, (int) Math.ceil((double) lines.size() / maxRows));
		int lineIndex = 0;
		int remainingLines = lines.size();

		for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
			int remainingPages = totalPages - pageIndex;
			int pageSize = Math.min(maxRows,
					Math.max(1, (int) Math.ceil((double) remainingLines / remainingPages)));
			List<LineLayout> pageLines = lines.subList(lineIndex,
					Math.min(lines.size(), lineIndex + pageSize));
			if (pageLines.isEmpty()) {
				continue;
			}

			lineIndex += pageLines.size();
			remainingLines -= pageLines.size();

			if (hasWordTimings) {
				WordLayout firstWord = words.get(pageLines.get(0).startWordIndex());
				WordLayout lastWord = words.get(pageLines.get(pageLines.size() - 1).endWordIndex());
				pages.add(new MutablePage(List.copyOf(pageLines),
						firstWord != null ? firstWord.startMs() : cue.startMs(),
						lastWord != null ? lastWord.endMs() : cue.endMs()));
				continue;
			}
			pages.add(new MutablePage(List.copyOf(pageLines), 0, 0));
		}

		if (!hasWordTimings) {
			int totalWords = Math.max(1, words.size());
			long cueDuration = Math.max(1, cue.endMs() - cue.startMs());
			double elapsedMs = cue.startMs();
			for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
				MutablePage page = pages.get(pageIndex);
				int pageWordCount = page.lines.stream().mapToInt(line -> line.words().size()).sum();
				long proportionalDuration = Math.round((cueDuration * (double) pageWordCount) / totalWords);
				double nextElapsed = pageIndex == pages.size() - 1
						? cue.endMs()
						: Math.min(cue.endMs(), elapsedMs + Math.max(1, proportionalDuration));
				page.startMs = elapsedMs;
				page.endMs = Math.max(elapsedMs + 1, nextElapsed);
				elapsedMs = page.endMs;
			}
		}

		for (int index = 0; index < pages.size() - 1; index++) {
			pages.get(index).endMs = Math.max(pages.get(index).startMs + 1,
					pages.get(index + 1).startMs);
		}
		if (!pages.isEmpty()) {
			MutablePage last = pages.get(pages.size() - 1);
			last.endMs = Math.max(last.startMs + 1, cue.endMs());
		}

		List<PageLayout> result = new ArrayList<>(pages.size());
		for (MutablePage page : pages) {
			result.add(new PageLayout(page.lines, page.startMs, page.endMs));
		}
		return result;
	}

	private static List<LineLayout> buildCaptionLines(
			List<WordLayout> words, double maxWidthPx, ToDoubleFunction<String> measureText) {
		int wordCount = words.size();
		double[] segmentWidths = new double[wordCount];
		double[] plainWordWidths = new double[wordCount];
		for (int index = 0; index < wordCount; index++) {
			WordLayout word = words.get(index);
			segmentWidths[index] = index == 0
					? measureText.applyAsDouble(word.text())
					: measureText.applyAsDouble((word.leadingSpace() ? " " : "") + word.text());
			plainWordWidths[index] = measureText.applyAsDouble(word.text());
		}

		double[] bestCost = new double[wordCount + 1];
		java.util.Arrays.fill(bestCost, Double.POSITIVE_INFINITY);
		int[] nextBreak = new int[wordCount];
		java.util.Arrays.fill(nextBreak, wordCount);
		bestCost[wordCount] = 0;

		for (int startIndex = wordCount - 1; startIndex >= 0; startIndex--) {
			double lineWidth = plainWordWidths[startIndex];
			for (int endIndex = startIndex; endIndex < wordCount; endIndex++) {
				if (endIndex > startIndex) {
					if (words.get(endIndex).forcedBreakBefore()) {
						break;
					}
					lineWidth += segmentWidths[endIndex];
				}
				if (lineWidth > maxWidthPx && endIndex > startIndex) {
					break;
				}
				double slack = Math.max(0, maxWidthPx - lineWidth);
				double fullness = maxWidthPx <= 0 ? 1 : lineWidth / maxWidthPx;
				boolean isLastLine = endIndex == wordCount - 1;
				double linePenalty = isLastLine
						? slack * slack * 0.18
						: slack * slack + (fullness < 0.72 ? (0.72 - fullness) * 26000 : 0);
				double candidateCost = linePenalty + bestCost[endIndex + 1];
				if (candidateCost < bestCost[startIndex]) {
					bestCost[startIndex] = candidateCost;
					nextBreak[startIndex] = endIndex + 1;
				}
			}
		}

		List<LineLayout> lines = new ArrayList<>();
		int startIndex = 0;
		while (startIndex < wordCount) {
			int endIndexExclusive = Math.max(startIndex + 1, nextBreak[startIndex]);
			List<WordLayout> lineWords = new ArrayList<>();
			for (int index = startIndex; index < endIndexExclusive; index++) {
				WordLayout word = words.get(index);
				lineWords.add(new WordLayout(word.cueId(), word.cueWordIndex(), word.text(),
						word.index(), word.forcedBreakBefore(),
						index == startIndex ? false : word.leadingSpace(), word.startMs(),
						word.endMs(), word.hasRealTiming(), word.state()));
			}
			double width = 0;
			for (int index = 0; index < lineWords.size(); index++) {
				WordLayout word = lineWords.get(index);
				String measured = index == 0 ? word.text()
						: (word.leadingSpace() ? " " : "") + word.text();
				width += measureText.applyAsDouble(measured);
			}
			lines.add(new LineLayout(List.copyOf(lineWords), width,
					lineWords.get(0).index(), lineWords.get(lineWords.size() - 1).index()));
			startIndex = endIndexExclusive;
		}
		return lines;
	}

	private static int getVisibleCaptionPageIndex(List<PageLayout> pages, double timeMs) {
		for (int index = 0; index < pages.size(); index++) {
			if (timeMs >= pages.get(index).startMs() && timeMs <= pages.get(index).endMs()) {
				return index;
			}
		}
		return -1;
	}

	private static String getVisibleCaptionText(List<LineLayout> lines) {
		List<String> lineTexts = new ArrayList<>();
		for (LineLayout line : lines) {
			StringBuilder text = new StringBuilder();
			for (int index = 0; index < line.words().size(); index++) {
				WordLayout word = line.words().get(index);
				if (index > 0 && word.leadingSpace()) {
					text.append(' ');
				}
				text.append(word.text());
			}
			String trimmed = text.toString().strip();
			if (!trimmed.isEmpty()) {
				lineTexts.add(trimmed);
			}
		}
		return String.join(" ", lineTexts);
	}

	/** {@code buildActiveCaptionLayout}. */
	public static ActiveCaptionLayout buildActiveCaptionLayout(
			List<CaptionCue> cues,
			double timeMs,
			AutoCaptionSettings settings,
			double maxWidthPx,
			ToDoubleFunction<String> measureText) {
		List<FlatWord> sourceWords = flattenCaptionWords(cues);
		if (sourceWords.isEmpty() || !isWithinCaptionCoverage(cues, timeMs)) {
			return null;
		}

		int activeWordIndex = -1;
		for (int index = 0; index < sourceWords.size(); index++) {
			FlatWord word = sourceWords.get(index);
			if (timeMs >= word.startMs() && timeMs < word.endMs()) {
				activeWordIndex = index;
				break;
			}
		}
		if (activeWordIndex < 0) {
			for (int index = 0; index < sourceWords.size(); index++) {
				if (timeMs < sourceWords.get(index).startMs()) {
					activeWordIndex = index;
					break;
				}
			}
			activeWordIndex = activeWordIndex < 0
					? sourceWords.size() - 1
					: (int) clamp(activeWordIndex - 1, 0, sourceWords.size() - 1);
		}
		int maxRows = (int) clamp(Math.round(settings.maxRows() == 0 ? 1 : settings.maxRows()), 1, 4);

		List<WordLayout> words = new ArrayList<>(sourceWords.size());
		for (int index = 0; index < sourceWords.size(); index++) {
			FlatWord word = sourceWords.get(index);
			words.add(new WordLayout(word.cueId(), word.cueWordIndex(), word.text(), index,
					word.forcedBreakBefore(), word.leadingSpace(), word.startMs(), word.endMs(),
					word.hasRealTiming(),
					index < activeWordIndex ? WordState.SPOKEN
							: index == activeWordIndex ? WordState.ACTIVE : WordState.UPCOMING));
		}

		List<LineLayout> lines = buildCaptionLines(words, maxWidthPx, measureText);
		CaptionCue pseudoCue = new CaptionCue(sourceWords.get(0).cueId(),
				Math.round(sourceWords.get(0).startMs()),
				Math.round(sourceWords.get(sourceWords.size() - 1).endMs()), "", null);
		List<PageLayout> pages = buildCaptionPages(lines, words, maxRows, true, pseudoCue);
		int visiblePageIndex = getVisibleCaptionPageIndex(pages, timeMs);
		if (visiblePageIndex < 0) {
			return null;
		}
		PageLayout visiblePage = pages.get(visiblePageIndex);
		List<LineLayout> visibleLines = visiblePage.lines();
		WordLayout activeWord = activeWordIndex >= 0 ? words.get(activeWordIndex) : null;
		double activeWordProgress = activeWord != null
				? clamp01((timeMs - activeWord.startMs())
						/ Math.max(1, activeWord.endMs() - activeWord.startMs()))
				: 0;
		double animationStartMs = visiblePage.startMs();
		double animationEndMs = visiblePage.endMs();
		int pageStartWordIndex = visibleLines.isEmpty() ? 0 : visibleLines.get(0).startWordIndex();
		List<WordLayout> visibleWords = new ArrayList<>();
		for (LineLayout line : visibleLines) {
			visibleWords.addAll(line.words());
		}
		boolean visibleHasWordTimings = true;
		for (WordLayout word : visibleWords) {
			if (!word.hasRealTiming()) {
				visibleHasWordTimings = false;
				break;
			}
		}
		String pageCueId = pageStartWordIndex < sourceWords.size()
				? sourceWords.get(pageStartWordIndex).cueId()
				: sourceWords.get(0).cueId();
		CaptionCue activeCue = getActiveCaptionCue(cues, timeMs);
		if (activeCue == null) {
			for (CaptionCue candidate : cues) {
				if (candidate.id().equals(pageCueId)) {
					activeCue = candidate;
					break;
				}
			}
		}
		if (activeCue == null) {
			activeCue = cues.get(0);
		}

		double visibleContentEndMs = animationStartMs;
		for (WordLayout word : visibleWords) {
			visibleContentEndMs = Math.max(visibleContentEndMs, word.endMs());
		}
		boolean willDisappear = settings.animationStyle() != AutoCaptionAnimation.NONE
				&& !isWithinCaptionCoverage(cues, visibleContentEndMs + 1);
		double exitAnchorMs = willDisappear ? visibleContentEndMs : animationEndMs;
		double enterProgress = clamp01((timeMs - animationStartMs) / CAPTION_ENTER_MS);
		double exitProgress = clamp01((exitAnchorMs - timeMs) / CAPTION_EXIT_MS);
		AnimationState animation = getCaptionAnimationState(settings.animationStyle(),
				enterProgress, exitProgress);
		double opacity = willDisappear ? animation.opacity() * exitProgress : animation.opacity();

		String blockKey = Math.round(animationStartMs) + "-" + Math.round(animationEndMs);
		StringBuilder editTargetId = new StringBuilder(blockKey).append(':');
		List<CaptionEditing.WordRef> editWords = new ArrayList<>(visibleWords.size());
		for (int index = 0; index < visibleWords.size(); index++) {
			WordLayout word = visibleWords.get(index);
			if (index > 0) {
				editTargetId.append('|');
			}
			editTargetId.append(word.cueId()).append(':').append(word.cueWordIndex());
			editWords.add(new CaptionEditing.WordRef(word.cueId(), word.cueWordIndex(),
					Math.round(word.startMs()), Math.round(word.endMs()), word.text(),
					word.leadingSpace()));
		}

		return new ActiveCaptionLayout(activeCue, blockKey, visibleLines, visibleHasWordTimings,
				activeWordIndex, activeWordProgress,
				new CaptionEditing.EditTarget(editTargetId.toString(),
						Math.round(animationStartMs), Math.round(animationEndMs),
						getVisibleCaptionText(visibleLines), List.copyOf(editWords)),
				visiblePageIndex, opacity, animation.translateY(), animation.scale());
	}
}
