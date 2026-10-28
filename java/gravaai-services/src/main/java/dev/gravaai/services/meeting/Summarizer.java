package dev.gravaai.services.meeting;

import java.util.Set;

/**
 * Turns a {@link MeetingContext} into notes. Implementations declare what they can take in;
 * today only text is sent, and a vision-capable one can later use {@code context.visuals} /
 * {@code context.videoPath} without changing callers (electron/ipc/meeting/summarize.ts).
 */
public interface Summarizer {

	/** Room left for the transcript in a context window, at ~3 characters per token. */
	static int transcriptCharBudget(int contextTokens) {
		return transcriptCharBudget(contextTokens, 3_500);
	}

	static int transcriptCharBudget(int contextTokens, int reservedTokens) {
		return Math.max(4_000, (contextTokens - reservedTokens) * 3);
	}

	Set<SummarizerCapability> capabilities();

	String summarize(MeetingContext context, MeetingStatusCallback onStatus, CancelSignal signal);

	String title(String notes, MeetingContext context, CancelSignal signal);
}
