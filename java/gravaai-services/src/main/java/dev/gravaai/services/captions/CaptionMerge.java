package dev.gravaai.services.captions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Port of electron/ipc/captions/mergeSources.ts: keep both speakers, with microphone speech
 * taking priority during overlap.
 */
public final class CaptionMerge {

	/** Sound-event labels like "[music]" or "(laughs)" are not competing microphone speech. */
	private static final Pattern SOUND_EVENT =
			Pattern.compile("^(?:\\s*(?:\\[[^\\]]*\\]|\\([^)]*\\))\\s*)+$");

	private record Span(long startMs, long endMs) {
	}

	private CaptionMerge() {
	}

	/** {@code mergeCaptionSources}. */
	public static List<CaptionCue> mergeCaptionSources(
			List<CaptionCue> microphone, List<CaptionCue> system) {
		List<CaptionCue> mic = microphone == null ? List.of() : microphone;
		List<CaptionCue> sys = system == null ? List.of() : system;

		List<CaptionCue> micSpeech = new ArrayList<>();
		for (CaptionCue cue : mic) {
			if (!SOUND_EVENT.matcher(cue.text()).matches()) {
				micSpeech.add(cue);
			}
		}
		List<Span> micSpans = new ArrayList<>();
		for (CaptionCue cue : micSpeech) {
			if (!cue.words().isEmpty()) {
				for (CaptionWord word : cue.words()) {
					micSpans.add(new Span(word.startMs(), word.endMs()));
				}
			} else {
				micSpans.add(new Span(cue.startMs(), cue.endMs()));
			}
		}
		java.util.function.BiPredicate<Long, Long> overlapsMic = (startMs, endMs) -> {
			for (Span span : micSpans) {
				if (startMs < span.endMs && endMs > span.startMs) {
					return true;
				}
			}
			return false;
		};

		List<CaptionCue> systemCues = new ArrayList<>();
		for (CaptionCue cue : sys) {
			if (!overlapsMic.test(cue.startMs(), cue.endMs())) {
				systemCues.add(cue);
				continue;
			}
			if (cue.words().isEmpty()) {
				List<Span> spans = new ArrayList<>();
				spans.add(new Span(cue.startMs(), cue.endMs()));
				for (Span micSpan : micSpans) {
					List<Span> next = new ArrayList<>();
					for (Span span : spans) {
						if (micSpan.endMs <= span.startMs || micSpan.startMs >= span.endMs) {
							next.add(span);
							continue;
						}
						List<Span> parts = new ArrayList<>();
						parts.add(new Span(span.startMs, Math.min(span.endMs, micSpan.startMs)));
						parts.add(new Span(Math.max(span.startMs, micSpan.endMs), span.endMs));
						for (Span part : parts) {
							if (part.endMs > part.startMs) {
								next.add(part);
							}
						}
					}
					spans = next;
				}
				for (Span span : spans) {
					systemCues.add(cue.withTiming(span.startMs, span.endMs));
				}
				continue;
			}
			List<CaptionWord> run = new ArrayList<>();
			for (CaptionWord word : cue.words()) {
				if (overlapsMic.test(word.startMs(), word.endMs())) {
					if (!run.isEmpty()) {
						systemCues.add(cueForRun(cue.id(), run));
						run = new ArrayList<>();
					}
				} else {
					run.add(word);
				}
			}
			if (!run.isEmpty()) {
				systemCues.add(cueForRun(cue.id(), run));
			}
		}

		List<CaptionCue> micEvents = new ArrayList<>();
		for (CaptionCue cue : mic) {
			boolean inSpeech = false;
			for (CaptionCue speech : micSpeech) {
				if (speech == cue) {
					inSpeech = true;
					break;
				}
			}
			boolean overlapsSystem = false;
			for (CaptionCue other : sys) {
				if (cue.startMs() < other.endMs() && cue.endMs() > other.startMs()) {
					overlapsSystem = true;
					break;
				}
			}
			if (!inSpeech && !overlapsSystem) {
				micEvents.add(cue);
			}
		}

		List<CaptionCue> merged = new ArrayList<>();
		merged.addAll(micSpeech);
		merged.addAll(micEvents);
		merged.addAll(systemCues);
		merged.sort(Comparator.comparingLong(CaptionCue::startMs)
				.thenComparingLong(CaptionCue::endMs));
		List<CaptionCue> result = new ArrayList<>();
		for (int index = 0; index < merged.size(); index++) {
			result.add(merged.get(index).withId("caption-" + (index + 1)));
		}
		return List.copyOf(result);
	}

	private static CaptionCue cueForRun(String id, List<CaptionWord> run) {
		return new CaptionCue(
				id,
				run.get(0).startMs(),
				run.get(run.size() - 1).endMs(),
				CaptionParser.buildCaptionTextFromWords(run),
				run);
	}
}
