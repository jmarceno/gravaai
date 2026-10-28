package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.meeting.MeetingTestSupport.FakeOpenAiClient;
import dev.gravaai.services.meeting.OpenAiClient.ChatCompletionRequest;
import dev.gravaai.services.meeting.OpenAiClient.ChatContentPart;
import dev.gravaai.services.meeting.OpenAiClient.OpenAiEndpoint;

/** Prompt assembly of the summarizer (electron/ipc/meeting/summarize.ts). */
class ChatSummarizerTest {

	private final FakeOpenAiClient client = new FakeOpenAiClient();

	private static ChatSummarizer summarizer(FakeOpenAiClient client, int maxTranscriptChars,
			boolean limitAnswerTokens, String summaryPrompt, String titlePrompt) {
		return new ChatSummarizer(client, new ChatSummarizer.Config(
				new OpenAiEndpoint("http://127.0.0.1:9/v1", "secret"),
				"qwen3.5-2b",
				List.of(SummarizerCapability.TEXT),
				maxTranscriptChars,
				60_000,
				summaryPrompt,
				titlePrompt,
				Map.of("chat_template_kwargs", Map.of("enable_thinking", false)),
				limitAnswerTokens));
	}

	private static String promptOf(ChatCompletionRequest request) {
		ChatContentPart part = request.messages().get(0).content().get(0);
		return ((ChatContentPart.TextPart) part).text();
	}

	private static MeetingContext context(String transcript, String language) {
		return MeetingContext.text(transcript, language, 60_000L);
	}

	@Test
	@DisplayName("builds the summary prompt with the language instruction and the transcript")
	void summaryPromptAssembly() {
		client.chatAnswers.add("## Summary\nDone.");
		ChatSummarizer summarizer = summarizer(client, 1_000, true, "", "");
		String notes = summarizer.summarize(context(" Hello ", "pt"),
				MeetingStatusCallback.IGNORING, CancelSignal.NONE);
		assertEquals("## Summary\nDone.", notes);
		ChatCompletionRequest request = client.chatRequests.get(0);
		String prompt = promptOf(request);
		assertTrue(prompt.startsWith("You are an experienced executive assistant taking minutes."));
		assertTrue(prompt.contains("Write your answer in Portuguese."));
		int instruction = prompt.indexOf("Write your answer in Portuguese.");
		assertTrue(instruction > prompt.indexOf("## Open questions"));
		assertTrue(instruction < prompt.indexOf("TRANSCRIPT:"));
		assertTrue(prompt.endsWith("TRANSCRIPT:\nHello\n"));
		assertEquals(Integer.valueOf(2_000), request.maxTokens());
		assertEquals(Double.valueOf(0.3), request.temperature());
		assertEquals("qwen3.5-2b", request.model());
		assertTrue(request.extra().containsKey("chat_template_kwargs"));
	}

	@Test
	@DisplayName("does not cap answer tokens for hosted models")
	void hostedModelsHaveNoAnswerCap() {
		client.chatAnswers.add("ok");
		ChatSummarizer summarizer = summarizer(client, 1_000, false, "", "");
		summarizer.summarize(context("hello", null), MeetingStatusCallback.IGNORING,
				CancelSignal.NONE);
		assertNull(client.chatRequests.get(0).maxTokens());
	}

	@Test
	@DisplayName("writes partial notes then merges them for a long meeting")
	void longMeetingChunksAndMerges() {
		List<String> lines = new ArrayList<>();
		for (int index = 0; index < 40; index++) {
			lines.add("[00:00:" + index + "] a line of the transcript");
		}
		String transcript = String.join("\n", lines);
		int chunks = MeetingPrompts.chunkTranscript(transcript, 150).size();
		assertTrue(chunks > 1);
		for (int index = 0; index < chunks; index++) {
			client.chatAnswers.add("partial " + (index + 1));
		}
		client.chatAnswers.add("merged notes");
		ChatSummarizer summarizer = summarizer(client, 150, true, "", "");
		String notes = summarizer.summarize(context(transcript, "en"),
				MeetingStatusCallback.IGNORING, CancelSignal.NONE);
		assertEquals("merged notes", notes);
		assertEquals(chunks + 1, client.chatRequests.size());
		String first = promptOf(client.chatRequests.get(0));
		assertTrue(first.contains("This is part 1 of " + chunks + "."));
		assertTrue(first.contains("TRANSCRIPT PART:"));
		String merge = promptOf(client.chatRequests.get(chunks));
		assertTrue(merge.contains("PARTIAL NOTES:"));
		assertTrue(merge.contains("### Part 1\npartial 1"));
		assertTrue(merge.contains("### Part " + chunks + "\npartial " + chunks));
	}

	@Test
	@DisplayName("generates and cleans the title from the notes")
	void titleFromNotes() {
		client.chatAnswers.add("Title: \"Q3 planning\".");
		ChatSummarizer summarizer = summarizer(client, 1_000, true, "", "");
		String title = summarizer.title("notes here", context("meeting", "en"), CancelSignal.NONE);
		assertEquals("Q3 planning", title);
		String prompt = promptOf(client.chatRequests.get(0));
		assertTrue(prompt.startsWith("Generate a concise 3-6 word title"));
		assertTrue(prompt.contains("notes here"));
		assertEquals(Integer.valueOf(60), client.chatRequests.get(0).maxTokens());
	}

	@Test
	@DisplayName("caps the notes sent to the title request")
	void titleUsesAtMostSixThousandCharacters() {
		client.chatAnswers.add("A title");
		ChatSummarizer summarizer = summarizer(client, 1_000, true, "", "");
		String notes = "a".repeat(7_000) + "TAIL";
		summarizer.title(notes, context("meeting", null), CancelSignal.NONE);
		String prompt = promptOf(client.chatRequests.get(0));
		assertTrue(prompt.endsWith("a".repeat(6_000)));
	}

	@Test
	@DisplayName("falls back to the built-in prompt when the custom one is blank")
	void customPromptFallback() {
		client.chatAnswers.add("notes");
		ChatSummarizer summarizer = summarizer(client, 1_000, true, "   ", "  ");
		summarizer.summarize(context("hello", null), MeetingStatusCallback.IGNORING,
				CancelSignal.NONE);
		assertTrue(promptOf(client.chatRequests.get(0))
				.startsWith("You are an experienced executive assistant taking minutes."));
	}

	@Test
	@DisplayName("rejects an empty transcript")
	void emptyTranscriptRejected() {
		ChatSummarizer summarizer = summarizer(client, 1_000, true, "", "");
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> summarizer.summarize(context("   ", null), MeetingStatusCallback.IGNORING,
						CancelSignal.NONE));
		assertEquals("The transcript is empty; there is nothing to summarize.",
				error.getMessage());
	}
}
