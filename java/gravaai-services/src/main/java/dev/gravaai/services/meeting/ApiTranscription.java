package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import dev.gravaai.services.meeting.OpenAiClient.OpenAiEndpoint;
import dev.gravaai.services.meeting.OpenAiClient.RequestOptions;
import dev.gravaai.services.meeting.OpenAiClient.TranscriptionRequest;
import dev.gravaai.services.meeting.OpenAiClient.TranscriptionResult;
import dev.gravaai.services.meeting.ports.MediaTools;
import dev.gravaai.services.meeting.ports.ProcessRunnerPort;

/**
 * API transcription, ported from electron/ipc/meeting/apiTranscription.ts. Upload size limits
 * rule out long meetings in one request, so audio is cut into speech-quality mono MP3 chunks.
 */
public final class ApiTranscription {

	/**
	 * 10 minutes at 48 kbps is ~3.6 MB, under OpenAI's 25 MB upload limit.
	 */
	public static final int CHUNK_SECONDS = 600;

	private static final Duration SPLIT_TIMEOUT = Duration.ofMinutes(30);
	private static final long TRANSCRIPTION_TIMEOUT_MS = 10 * 60 * 1000L;

	private final OpenAiClient client;
	private final ProcessRunnerPort processes;
	private final MediaTools tools;
	private final Path tempDirectory;

	public ApiTranscription(OpenAiClient client, ProcessRunnerPort processes, MediaTools tools,
			Path tempDirectory) {
		this.client = client;
		this.processes = processes;
		this.tools = tools;
		this.tempDirectory = tempDirectory;
	}

	public static List<String> chunkCommand(String input, String pattern) {
		return List.of(
				"-hide_banner",
				"-loglevel",
				"error",
				"-nostdin",
				"-y",
				"-i",
				input,
				"-map",
				"0:a:0",
				"-vn",
				"-ac",
				"1",
				"-ar",
				"16000",
				"-c:a",
				"libmp3lame",
				"-b:a",
				"48k",
				"-f",
				"segment",
				"-segment_time",
				String.valueOf(CHUNK_SECONDS),
				"-reset_timestamps",
				"1",
				pattern);
	}

	/**
	 * @param language the detected language, or null when the API did not report one
	 */
	public record Result(List<TranscriptSegment> segments, @Nullable String language) {
	}

	/**
	 * API transcription. With both speaker tracks each is transcribed on its own and merged into
	 * one Me/Remote timeline (two uploads, but no crosstalk).
	 */
	public Result transcribeWithApi(OpenAiEndpoint endpoint, String model, MeetingAudioInputs inputs,
			String language, MeetingStatusCallback onStatus, CancelSignal signal) {
		MeetingStatusCallback status = onStatus == null ? MeetingStatusCallback.IGNORING : onStatus;
		if (inputs.mic() != null && inputs.system() != null) {
			Result mic = transcribeTrack(endpoint, model, inputs.mic(), language, "your audio, ",
					status, signal);
			Result system = transcribeTrack(endpoint, model, inputs.system(), language,
					"other participants, ", status, signal);
			return new Result(
					Transcript.mergeSpeakerSegments(mic.segments(), system.segments()),
					mic.language() != null ? mic.language() : system.language());
		}
		Path input = inputs.mic() != null ? inputs.mic()
				: inputs.system() != null ? inputs.system() : inputs.mediaPath();
		return transcribeTrack(endpoint, model, input, language, "", status, signal);
	}

	private Result transcribeTrack(OpenAiEndpoint endpoint, String model, Path input,
			String language, String label, MeetingStatusCallback onStatus, CancelSignal signal) {
		Path work = createTempDirectory();
		try {
			List<Path> chunks = splitForUpload(input, work);
			List<TranscriptSegment> segments = new ArrayList<>();
			String detectedLanguage = language;
			for (int index = 0; index < chunks.size(); index++) {
				signal.throwIfCancelled();
				onStatus.onStatus("Transcribing " + label + "part " + (index + 1) + " of "
						+ chunks.size() + "…",
						(int) Math.round((index / (double) chunks.size()) * 100));
				long offsetMs = index * (long) CHUNK_SECONDS * 1000L;
				TranscriptionResult result = client.transcribeAudioFile(endpoint,
						new TranscriptionRequest(model, chunks.get(index), language, null),
						new RequestOptions(signal, TRANSCRIPTION_TIMEOUT_MS));
				if (detectedLanguage == null) {
					detectedLanguage = MeetingPrompts.languageCodeFromName(result.language());
				}
				if (result.segments() != null) {
					for (OpenAiClient.TranscriptionSegmentResult segment : result.segments()) {
						if (!segment.text().isEmpty()) {
							segments.add(new TranscriptSegment(
									offsetMs + Math.round(segment.start() * 1000.0),
									segment.text(), null));
						}
					}
				} else if (!result.text().isEmpty()) {
					// No timings from this model: one paragraph per chunk at the chunk start.
					segments.add(new TranscriptSegment(offsetMs, result.text(), null));
				}
			}
			return new Result(List.copyOf(segments), detectedLanguage);
		} finally {
			FileTrees.deleteQuietly(work);
		}
	}

	private List<Path> splitForUpload(Path input, Path directory) {
		List<String> command = new ArrayList<>();
		command.add(tools.ffmpeg().toString());
		command.addAll(chunkCommand(input.toString(),
				directory.resolve("chunk-%03d.mp3").toString()));
		ProcessRunnerPort.ProcessResult result;
		try {
			result = processes.run(command, SPLIT_TIMEOUT, Map.of());
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while cutting the audio.", interrupted);
		}
		if (result.exitCode() != 0) {
			throw new IllegalStateException(
					"ffmpeg failed: " + Texts.lastLines(result.stderr(), 3));
		}
		List<Path> chunks = new ArrayList<>();
		try (var files = Files.list(directory)) {
			for (Path file : files.sorted(Comparator.comparing(Path::toString)).toList()) {
				String name = file.getFileName().toString();
				if (name.startsWith("chunk-") && name.endsWith(".mp3")) {
					chunks.add(file);
				}
			}
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
		if (chunks.isEmpty()) {
			throw new IllegalStateException("No audio was found to transcribe.");
		}
		return chunks;
	}

	private Path createTempDirectory() {
		try {
			return Files.createTempDirectory(tempDirectory, "gravaai-meeting-api-");
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}
}
