package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal OpenAI-compatible client interface (electron/ipc/meeting/openaiClient.ts). The same code
 * talks to the local llama-server and to any API (OpenAI, OpenRouter, Ollama's /v1, LM Studio…).
 * Messages use content parts so images can be added later without a new client.
 */
public interface OpenAiClient {

	String chatCompletion(OpenAiEndpoint endpoint, ChatCompletionRequest request,
			RequestOptions options);

	TranscriptionResult transcribeAudioFile(OpenAiEndpoint endpoint,
			TranscriptionRequest request, RequestOptions options);

	/** Thrown for a non-2xx answer; carries the HTTP status. */
	final class OpenAiHttpException extends RuntimeException {

		private final int status;

		public OpenAiHttpException(int status, String message) {
			super(message);
			this.status = status;
		}

		public int status() {
			return status;
		}
	}

	record OpenAiEndpoint(String baseUrl, @Nullable String apiKey) {
	}

	record RequestOptions(CancelSignal signal, long timeoutMs) {
	}

	record ChatCompletionRequest(String model, List<ChatMessage> messages, @Nullable Integer maxTokens,
			@Nullable Double temperature, Map<String, Object> extra) {
	}

	record ChatMessage(String role, List<ChatContentPart> content) {
	}

	/** Content parts: text now, images when a later stage sends screen context. */
	sealed interface ChatContentPart
			permits ChatContentPart.TextPart, ChatContentPart.ImageUrlPart {

		record TextPart(String text) implements ChatContentPart {
		}

		record ImageUrlPart(ImageUrl imageUrl) implements ChatContentPart {
		}

		record ImageUrl(String url) {
		}
	}

	record TranscriptionRequest(String model, Path filePath, String language, @Nullable String prompt) {
	}

	record TranscriptionSegmentResult(double start, String text) {
	}

	record TranscriptionResult(String text, @Nullable List<TranscriptionSegmentResult> segments,
			@Nullable String language) {
	}

	/** Turns a failed response into a message a user can act on. */
	static String describeHttpError(int status, String body, String context) {
		String detail = body.strip();
		try {
			JsonNode parsed = JsonMapper.shared().readTree(body);
			JsonNode error = parsed.get("error");
			if (error != null) {
				if (error.isString()) {
					detail = error.asString();
				} else if (error.path("message").isString()) {
					detail = error.path("message").asString();
				}
			}
		} catch (RuntimeException notJson) {
			// Not JSON; keep the raw body.
		}
		detail = detail.length() <= 400 ? detail : detail.substring(0, 400);
		return switch (status) {
			case 401, 403 -> (context + " was rejected (HTTP " + status
					+ "). Check the API key. " + detail).strip();
			case 404 -> (context + " failed: endpoint or model not found (HTTP 404). Check the"
					+ " base URL and model name. " + detail).strip();
			case 429 -> (context + " hit a rate or quota limit (HTTP 429). " + detail).strip();
			case 413 -> (context + " failed: the request was too large (HTTP 413). "
					+ detail).strip();
			default -> (context + " failed (HTTP " + status + "). " + detail).strip();
		};
	}

	/**
	 * whisper-1 returns timed segments with verbose_json; the gpt-4o transcribe models only accept
	 * json/text.
	 */
	static String responseFormatFor(String model) {
		return model.toLowerCase(Locale.ROOT).startsWith("whisper") ? "verbose_json" : "json";
	}
}
