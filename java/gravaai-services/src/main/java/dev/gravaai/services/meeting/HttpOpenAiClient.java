package dev.gravaai.services.meeting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI-compatible client on {@code java.net.http.HttpClient} (plan §5.14). No streaming is used
 * today, so there is no SSE parser to port; requests are plain JSON POSTs and one multipart
 * upload.
 */
public final class HttpOpenAiClient implements OpenAiClient {

	private static final ObjectMapper MAPPER = JsonMapper.shared();
	private static final long POLL_MILLIS = 200L;

	private final HttpClient client;

	public HttpOpenAiClient() {
		this(HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build());
	}

	HttpOpenAiClient(HttpClient client) {
		this.client = client;
	}

	@Override
	public String chatCompletion(OpenAiEndpoint endpoint, ChatCompletionRequest request,
			RequestOptions options) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", request.model());
		body.put("messages", messagesJson(request.messages()));
		if (request.maxTokens() != null && request.maxTokens() > 0) {
			body.put("max_tokens", request.maxTokens());
		}
		if (request.temperature() != null) {
			body.put("temperature", request.temperature());
		}
		if (request.extra() != null) {
			body.putAll(request.extra());
		}

		HttpResponse<String> response = sendJson(endpoint, "chat/completions", body, options,
				"Summarization");
		String text = response.body();
		int status = response.statusCode();
		if (status < 200 || status >= 300) {
			throw new OpenAiHttpException(status,
					OpenAiClient.describeHttpError(status, text, "Summarization"));
		}
		JsonNode data = MAPPER.readTree(text);
		JsonNode choices = data.path("choices");
		JsonNode choice = choices.isArray() && !choices.isEmpty() ? choices.get(0) : null;
		JsonNode contentNode = choice == null ? null : choice.path("message").path("content");
		String content = contentNode != null && contentNode.isTextual()
				? contentNode.asText()
				: null;
		if (content == null || content.isBlank()) {
			String finishReason = choice == null ? null
					: choice.path("finish_reason").asText(null);
			throw new IllegalStateException("length".equals(finishReason)
					? "The model ran out of answer space before writing anything (reasoning"
							+ " models can spend it all on thinking)."
					: "The model returned an empty answer.");
		}
		return content;
	}

	@Override
	public TranscriptionResult transcribeAudioFile(OpenAiEndpoint endpoint,
			TranscriptionRequest request, RequestOptions options) {
		byte[] audio;
		try {
			audio = Files.readAllBytes(request.filePath());
		} catch (IOException error) {
			throw new IllegalStateException(
					"Could not read " + request.filePath() + ": " + error.getMessage(), error);
		}
		String boundary = "----gravaai" + UUID.randomUUID().toString().replace("-", "");
		byte[] form = multipartBody(boundary, request, audio);
		HttpRequest.Builder builder = HttpRequest.newBuilder(
				URI.create(endpointUrl(endpoint.baseUrl(), "audio/transcriptions")))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(form));
		authHeaders(endpoint).forEach(builder::header);

		HttpResponse<String> response = send(builder, options, "Transcription");
		int status = response.statusCode();
		String text = response.body();
		if (status < 200 || status >= 300) {
			throw new OpenAiHttpException(status,
					OpenAiClient.describeHttpError(status, text, "Transcription"));
		}
		JsonNode data = MAPPER.readTree(text);
		String transcribed = data.path("text").asText("").strip();
		JsonNode languageNode = data.get("language");
		String language = languageNode == null || languageNode.isNull()
				? null
				: languageNode.asText();
		List<TranscriptionSegmentResult> segments = null;
		JsonNode segmentsNode = data.path("segments");
		if (segmentsNode.isArray()) {
			segments = new ArrayList<>();
			for (JsonNode segment : segmentsNode) {
				segments.add(new TranscriptionSegmentResult(
						segment.path("start").asDouble(0.0),
						segment.path("text").asText("").strip()));
			}
		}
		return new TranscriptionResult(transcribed,
				segments == null ? null : List.copyOf(segments), language);
	}

	private HttpResponse<String> sendJson(OpenAiEndpoint endpoint, String route,
			Map<String, Object> body, RequestOptions options, String context) {
		byte[] json;
		try {
			json = MAPPER.writeValueAsBytes(body);
		} catch (RuntimeException error) {
			throw new IllegalStateException("Could not encode the request body.", error);
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(
				URI.create(endpointUrl(endpoint.baseUrl(), route)))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(json));
		authHeaders(endpoint).forEach(builder::header);
		return send(builder, options, context);
	}

	private HttpResponse<String> send(HttpRequest.Builder builder, RequestOptions options,
			String context) {
		CancelSignal signal = options.signal() == null ? CancelSignal.NONE : options.signal();
		long timeoutMs = options.timeoutMs();
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		HttpRequest request = builder.timeout(Duration.ofMillis(timeoutMs)).build();
		Future<HttpResponse<String>> future = client.sendAsync(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		while (true) {
			signal.throwIfCancelled();
			try {
				return future.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException stillRunning) {
				if (System.nanoTime() > deadline) {
					future.cancel(true);
					throw new IllegalStateException(timeoutMessage(context, timeoutMs));
				}
			} catch (ExecutionException error) {
				Throwable cause = error.getCause();
				if (cause instanceof HttpTimeoutException) {
					throw new IllegalStateException(timeoutMessage(context, timeoutMs));
				}
				throw new IllegalStateException(
						cause == null ? error.toString() : cause.toString(), cause);
			} catch (InterruptedException interrupted) {
				future.cancel(true);
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for "
						+ context.toLowerCase() + ".", interrupted);
			}
		}
	}

	private static String timeoutMessage(String context, long timeoutMs) {
		return context + " timed out after " + Math.round(timeoutMs / 60_000.0) + " min.";
	}

	private static String endpointUrl(String baseUrl, String route) {
		return baseUrl.replaceAll("/+\\z", "") + "/" + route.replaceAll("^/+", "");
	}

	private static Map<String, String> authHeaders(OpenAiEndpoint endpoint) {
		if (endpoint.apiKey() == null || endpoint.apiKey().isEmpty()) {
			return Map.of();
		}
		return Map.of("Authorization", "Bearer " + endpoint.apiKey());
	}

	private static List<Map<String, Object>> messagesJson(List<ChatMessage> messages) {
		List<Map<String, Object>> json = new ArrayList<>();
		for (ChatMessage message : messages) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("role", message.role());
			List<Map<String, Object>> parts = new ArrayList<>();
			for (ChatContentPart part : message.content()) {
				if (part instanceof ChatContentPart.TextPart text) {
					Map<String, Object> partJson = new LinkedHashMap<>();
					partJson.put("type", "text");
					partJson.put("text", text.text());
					parts.add(partJson);
				} else if (part instanceof ChatContentPart.ImageUrlPart image) {
					Map<String, Object> imageJson = new LinkedHashMap<>();
					imageJson.put("url", image.imageUrl().url());
					Map<String, Object> partJson = new LinkedHashMap<>();
					partJson.put("type", "image_url");
					partJson.put("image_url", imageJson);
					parts.add(partJson);
				}
			}
			entry.put("content", parts);
			json.add(entry);
		}
		return json;
	}

	private static byte[] multipartBody(String boundary, TranscriptionRequest request,
			byte[] audio) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeField(out, boundary, "model", request.model());
		writeField(out, boundary, "response_format",
				OpenAiClient.responseFormatFor(request.model()));
		if (request.language() != null && !"auto".equals(request.language())) {
			writeField(out, boundary, "language", request.language());
		}
		if (request.prompt() != null) {
			writeField(out, boundary, "prompt", request.prompt());
		}
		String fileName = request.filePath().getFileName().toString();
		write(out, "--" + boundary + "\r\n");
		write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName
				+ "\"\r\n");
		write(out, "Content-Type: audio/mpeg\r\n\r\n");
		out.writeBytes(audio);
		write(out, "\r\n--" + boundary + "--\r\n");
		return out.toByteArray();
	}

	private static void writeField(ByteArrayOutputStream out, String boundary, String name,
			String value) {
		write(out, "--" + boundary + "\r\n");
		write(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
		write(out, value + "\r\n");
	}

	private static void write(ByteArrayOutputStream out, String text) {
		out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
	}
}
