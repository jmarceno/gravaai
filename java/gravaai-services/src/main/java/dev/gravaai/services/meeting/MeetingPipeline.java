package dev.gravaai.services.meeting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.gravaai.services.meeting.MeetingAssets.MeetingAsset;
import dev.gravaai.services.meeting.OpenAiClient.OpenAiEndpoint;

/**
 * Transcribe → summarize → title, as separate stages that each write their own file next to the
 * recording, so a stage can be re-run alone and new ones (screen context) can slot in between.
 * Ported from electron/ipc/meeting/pipeline.ts.
 */
public final class MeetingPipeline {

	private static final System.Logger LOG = System.getLogger("gravaai.meeting");

	private final MeetingSettingsService settingsService;
	private final MeetingAssets assets;
	private final AssetInstaller installer;
	private final WhisperTranscriber whisper;
	private final ApiTranscription apiTranscription;
	private final LlamaServer llama;
	private final OpenAiClient client;
	private final MeetingMetaStore metaStore;
	private final Clock clock;

	public MeetingPipeline(MeetingSettingsService settingsService, MeetingAssets assets,
			AssetInstaller installer, WhisperTranscriber whisper,
			ApiTranscription apiTranscription, LlamaServer llama, OpenAiClient client,
			MeetingMetaStore metaStore, Clock clock) {
		this.settingsService = settingsService;
		this.assets = assets;
		this.installer = installer;
		this.whisper = whisper;
		this.apiTranscription = apiTranscription;
		this.llama = llama;
		this.client = client;
		this.metaStore = metaStore;
		this.clock = clock;
	}

	public void run(Path mediaPath, MeetingJobMode mode, MeetingSettings settings,
			CancelSignal signal, MeetingJobs.StageListener onStatus) {
		MeetingJobs.StageListener status = onStatus == null ? (stage, message, progress) -> {
		} : onStatus;
		MeetingStatusCallback transcribeStatus = (message, progress) ->
				status.onStatus(MeetingJob.Stage.TRANSCRIBE, message, progress);
		MeetingFiles files = MeetingFiles.meetingFilesFor(mediaPath);

		String transcript;
		String language;
		if (mode == MeetingJobMode.SUMMARIZE) {
			try {
				transcript = Files.readString(files.transcript());
			} catch (IOException error) {
				throw new IllegalStateException(
						"There is no transcript yet. Transcribe this recording first.");
			}
			language = metaStore.read(mediaPath).language();
		} else {
			Transcription result = runTranscription(mediaPath, settings, transcribeStatus, signal);
			transcript = result.transcript();
			language = result.language();
		}
		if (mode == MeetingJobMode.TRANSCRIBE) {
			return;
		}
		signal.throwIfCancelled();

		MeetingStatusCallback summarizeStatus = (message, progress) ->
				status.onStatus(MeetingJob.Stage.SUMMARIZE, message, progress);
		try {
			SummarizerHolder holder = createSummarizer(settings, summarizeStatus);
			MeetingMeta meta = metaStore.read(mediaPath);
			MeetingContext context = new MeetingContext(transcript, language, meta.durationMs(),
					null, List.of());
			String notes = holder.summarizer().summarize(context, summarizeStatus, signal);
			writeTextAtomic(files.notes(), notes);
			metaStore.update(mediaPath, Map.of(
					"summarizedAt", Times.isoNow(clock),
					"summarizationSource", holder.source()));

			if (settings.autoTitle() && !"user".equals(meta.titleSource())) {
				signal.throwIfCancelled();
				status.onStatus(MeetingJob.Stage.TITLE, "Generating a title…", null);
				try {
					String title = holder.summarizer().title(notes, context, signal);
					if (title != null && !title.isEmpty()) {
						metaStore.update(mediaPath,
								Map.of("title", title, "titleSource", "auto"));
					}
				} catch (RuntimeException error) {
					// A missing title must not fail notes that were already written.
					LOG.log(System.Logger.Level.WARNING, "Title generation failed", error);
				}
			}
		} finally {
			llama.stop();
		}
	}

	private record Transcription(String transcript, String language) {
	}

	private Transcription runTranscription(Path mediaPath, MeetingSettings settings,
			MeetingStatusCallback onStatus, CancelSignal signal) {
		MeetingAudioInputs inputs = Transcript.resolveMeetingAudioInputs(mediaPath);
		List<TranscriptSegment> segments;
		String language;
		String source;
		if (settings.transcriptionService() == MeetingServiceKind.API) {
			ApiTranscription.Result result = apiTranscription.transcribeWithApi(
					apiEndpoint(settings),
					settings.apiTranscriptionModel(),
					inputs,
					settings.language(),
					onStatus,
					signal);
			segments = result.segments();
			language = result.language();
			source = "api:" + settings.apiTranscriptionModel();
		} else {
			MeetingAsset model = assets.whisperModels().get(settings.whisperModel());
			if (model == null) {
				throw new IllegalStateException(
						"Unknown whisper model " + settings.whisperModel() + ".");
			}
			Path modelPath = installer.ensure(model,
					percent -> onStatus.onStatus("Downloading " + model.label() + "…", percent));
			MeetingAsset vadModel = assets.whisperVadModel();
			Path vadModelPath = installer.ensure(vadModel,
					percent -> onStatus.onStatus("Downloading " + vadModel.label() + "…",
							percent));
			Transcript.ParsedTranscript result = whisper.transcribe(inputs, modelPath.toString(),
					vadModelPath.toString(), settings.language(), onStatus, signal);
			segments = result.segments();
			language = result.language();
			source = "whisper:" + settings.whisperModel();
		}

		MeetingFiles files = MeetingFiles.meetingFilesFor(mediaPath);
		if (segments.isEmpty()) {
			// A transcript or notes left from an earlier run of this audio were invented.
			try {
				Files.deleteIfExists(files.transcript());
				Files.deleteIfExists(files.notes());
			} catch (IOException error) {
				throw new java.io.UncheckedIOException(error);
			}
			MeetingMeta meta = metaStore.read(mediaPath);
			Map<String, Object> patch = new LinkedHashMap<>();
			patch.put("language", MeetingMetaStore.REMOVE);
			if ("auto".equals(meta.titleSource())) {
				patch.put("title", MeetingMetaStore.REMOVE);
				patch.put("titleSource", MeetingMetaStore.REMOVE);
			}
			metaStore.update(mediaPath, patch);
			throw new IllegalStateException("No speech was found in this recording.");
		}

		String transcript = Transcript.formatTranscriptMarkdown(segments);
		writeTextAtomic(files.transcript(), transcript);
		Map<String, Object> patch = new LinkedHashMap<>();
		patch.put("language", language);
		patch.put("durationMs", whisper.probeDurationMs(mediaPath));
		patch.put("transcribedAt", Times.isoNow(clock));
		patch.put("transcriptionSource", source);
		metaStore.update(mediaPath, patch);
		return new Transcription(transcript, language);
	}

	private record SummarizerHolder(Summarizer summarizer, String source) {
	}

	private SummarizerHolder createSummarizer(MeetingSettings settings,
			MeetingStatusCallback onStatus) {
		if (settings.summarizationService() == MeetingServiceKind.API) {
			return new SummarizerHolder(new ChatSummarizer(client, new ChatSummarizer.Config(
					apiEndpoint(settings),
					settings.apiChatModel(),
					settings.apiChatCapabilities(),
					// Hosted models have large windows; chunking only kicks in for very long
					// meetings.
					300_000,
					settings.apiTimeoutMinutes() * 60_000L,
					settings.summaryPrompt(),
					settings.titlePrompt(),
					null,
					false)),
					"api:" + settings.apiChatModel());
		}
		String baseUrl = llama.start(settings.localModel(), onStatus);
		return new SummarizerHolder(new ChatSummarizer(client, new ChatSummarizer.Config(
				new OpenAiEndpoint(baseUrl, null),
				settings.localModel(),
				List.of(SummarizerCapability.TEXT),
				Summarizer.transcriptCharBudget(LlamaServer.LOCAL_CONTEXT_TOKENS),
				// CPU prompt processing of a long meeting can take many minutes.
				30 * 60_000L,
				settings.summaryPrompt(),
				settings.titlePrompt(),
				Map.<String, Object>of("chat_template_kwargs",
						Map.of("enable_thinking", false)),
				true)),
				"local:" + settings.localModel());
	}

	private OpenAiEndpoint apiEndpoint(MeetingSettings settings) {
		return new OpenAiEndpoint(settings.apiBaseUrl(), settingsService.readApiKey());
	}

	/** Writes transcript/notes with the trailing newline Electron's writeAtomic adds. */
	private static void writeTextAtomic(Path file, String content) {
		JsonFiles.writeAtomic(file, content.endsWith("\n") ? content : content + "\n");
	}
}
