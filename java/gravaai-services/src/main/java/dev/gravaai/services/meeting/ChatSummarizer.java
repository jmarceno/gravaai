package dev.gravaai.services.meeting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.gravaai.services.meeting.OpenAiClient.ChatCompletionRequest;
import dev.gravaai.services.meeting.OpenAiClient.ChatContentPart;
import dev.gravaai.services.meeting.OpenAiClient.ChatMessage;
import dev.gravaai.services.meeting.OpenAiClient.OpenAiEndpoint;
import dev.gravaai.services.meeting.OpenAiClient.RequestOptions;

/**
 * The OpenAI-compatible summarizer, ported from electron/ipc/meeting/summarize.ts
 * ({@code ChatSummarizer}): one request for a short meeting, per-part notes plus a merge pass for
 * a long one, then the title.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class ChatSummarizer implements Summarizer {

	/**
	 * @param maxTranscriptChars transcript characters that fit one request next to the prompt and
	 *     the answer
	 * @param requestExtra extra request body fields (llama-server: turn reasoning off)
	 * @param limitAnswerTokens cap answer length; only for the local model, because hosted
	 *     reasoning models count their thinking against the cap and would return nothing
	 */
	public record Config(
			OpenAiEndpoint endpoint,
			String model,
			List<SummarizerCapability> capabilities,
			int maxTranscriptChars,
			long timeoutMs,
			String summaryPrompt,
			String titlePrompt,
			Map<String, Object> requestExtra,
			boolean limitAnswerTokens) {
	}

	private final OpenAiClient client;
	private final Config config;
	private final Set<SummarizerCapability> capabilities;

	public ChatSummarizer(OpenAiClient client, Config config) {
		this.client = client;
		this.config = config;
		this.capabilities = Set.copyOf(config.capabilities());
	}

	@Override
	public Set<SummarizerCapability> capabilities() {
		return capabilities;
	}

	private String complete(String prompt, int maxTokens, CancelSignal signal) {
		List<ChatMessage> messages = List.of(new ChatMessage("user",
				List.of(new ChatContentPart.TextPart(prompt))));
		String answer = client.chatCompletion(config.endpoint(),
				new ChatCompletionRequest(
						config.model(),
						messages,
						config.limitAnswerTokens() ? Integer.valueOf(maxTokens) : null,
						0.3,
						config.requestExtra()),
				new RequestOptions(signal, config.timeoutMs()));
		return MeetingPrompts.stripThinking(answer);
	}

	@Override
	public String summarize(MeetingContext context, MeetingStatusCallback onStatus,
			CancelSignal signal) {
		MeetingStatusCallback status = onStatus == null ? MeetingStatusCallback.IGNORING : onStatus;
		String transcript = context.transcript().strip();
		if (transcript.isEmpty()) {
			throw new IllegalStateException(
					"The transcript is empty; there is nothing to summarize.");
		}
		List<String> chunks = MeetingPrompts.chunkTranscript(transcript,
				config.maxTranscriptChars());
		String summaryTemplate = MeetingPrompts.withLanguageInstruction(
				MeetingPrompts.effectivePrompt(config.summaryPrompt(),
						MeetingPrompts.SUMMARY_PROMPT),
				context.language());
		if (chunks.size() == 1) {
			status.onStatus("Writing meeting notes…", null);
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("transcript", transcript);
			return MeetingPrompts.tidyNotes(
					complete(MeetingPrompts.renderPrompt(summaryTemplate, values), 2_000, signal));
		}

		// Long meeting: notes per part, then one merge pass.
		String partTemplate = MeetingPrompts.withLanguageInstruction(
				MeetingPrompts.PARTIAL_SUMMARY_PROMPT, context.language());
		List<String> partials = new java.util.ArrayList<>();
		for (int index = 0; index < chunks.size(); index++) {
			signal.throwIfCancelled();
			status.onStatus("Writing notes for part " + (index + 1) + " of " + chunks.size() + "…",
					(int) Math.round((index / (double) (chunks.size() + 1)) * 100));
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("transcript", chunks.get(index));
			values.put("part", index + 1);
			values.put("parts", chunks.size());
			partials.add(complete(MeetingPrompts.renderPrompt(partTemplate, values), 1_200,
					signal));
		}
		status.onStatus("Merging the notes…",
				(int) Math.round((chunks.size() / (double) (chunks.size() + 1)) * 100));
		StringBuilder merged = new StringBuilder();
		for (int index = 0; index < partials.size(); index++) {
			if (index > 0) {
				merged.append("\n\n");
			}
			merged.append("### Part ").append(index + 1).append('\n').append(partials.get(index));
		}
		String mergeTemplate = MeetingPrompts.withLanguageInstruction(
				MeetingPrompts.MERGE_SUMMARY_PROMPT, context.language());
		Map<String, Object> mergeValues = new LinkedHashMap<>();
		String mergedNotes = merged.toString();
		// The merge pass must see every partial heading: truncating it to the chunk size (as if it
		// were a raw transcript) silently drops the last parts of a long meeting.
		mergeValues.put("transcript", mergedNotes);
		return MeetingPrompts.tidyNotes(
				complete(MeetingPrompts.renderPrompt(mergeTemplate, mergeValues), 2_000, signal));
	}

	@Override
	public String title(String notes, MeetingContext context, CancelSignal signal) {
		String template = MeetingPrompts.withLanguageInstruction(
				MeetingPrompts.effectivePrompt(config.titlePrompt(), MeetingPrompts.TITLE_PROMPT),
				context.language());
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("transcript", notes.substring(0, Math.min(6_000, notes.length())));
		return MeetingPrompts.cleanTitle(
				complete(MeetingPrompts.renderPrompt(template, values), 60, signal));
	}
}
