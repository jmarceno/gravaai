package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Meeting settings as stored in {@code app-settings.json} under "meetingSettings"
 * (electron/ipc/meeting/settings.ts). The API key itself never lives here; it is kept behind
 * {@code MeetingApiKeyStore}.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public record MeetingSettings(
		MeetingServiceKind transcriptionService,
		String whisperModel,
		String language,
		MeetingServiceKind summarizationService,
		String localModel,
		String apiBaseUrl,
		String apiTranscriptionModel,
		String apiChatModel,
		List<SummarizerCapability> apiChatCapabilities,
		int apiTimeoutMinutes,
		String summaryPrompt,
		String titlePrompt,
		boolean autoProcess,
		boolean autoTitle) {

	public static final MeetingSettings DEFAULT = new MeetingSettings(
			MeetingServiceKind.LOCAL,
			"large-v3-turbo-q5_0",
			"auto",
			MeetingServiceKind.LOCAL,
			"qwen3.5-2b",
			"https://api.openai.com/v1",
			"whisper-1",
			"gpt-4.1-mini",
			List.of(SummarizerCapability.TEXT),
			5,
			"",
			"",
			true,
			true);

	/** What the renderer sees: the API key itself never leaves the main process. */
	public record View(MeetingSettings settings, boolean hasApiKey) {
	}

	public MeetingSettings withTranscriptionService(MeetingServiceKind value) {
		return new MeetingSettings(value, whisperModel, language, summarizationService, localModel,
				apiBaseUrl, apiTranscriptionModel, apiChatModel, apiChatCapabilities, apiTimeoutMinutes,
				summaryPrompt, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withWhisperModel(String value) {
		return new MeetingSettings(transcriptionService, value, language, summarizationService,
				localModel, apiBaseUrl, apiTranscriptionModel, apiChatModel, apiChatCapabilities,
				apiTimeoutMinutes, summaryPrompt, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withLanguage(String value) {
		return new MeetingSettings(transcriptionService, whisperModel, value, summarizationService,
				localModel, apiBaseUrl, apiTranscriptionModel, apiChatModel, apiChatCapabilities,
				apiTimeoutMinutes, summaryPrompt, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withSummarizationService(MeetingServiceKind value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, value, localModel,
				apiBaseUrl, apiTranscriptionModel, apiChatModel, apiChatCapabilities, apiTimeoutMinutes,
				summaryPrompt, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withLocalModel(String value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, summarizationService,
				value, apiBaseUrl, apiTranscriptionModel, apiChatModel, apiChatCapabilities,
				apiTimeoutMinutes, summaryPrompt, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withApiBaseUrl(String value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, summarizationService,
				localModel, value, apiTranscriptionModel, apiChatModel, apiChatCapabilities,
				apiTimeoutMinutes, summaryPrompt, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withApiTranscriptionModel(String value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, summarizationService,
				localModel, apiBaseUrl, value, apiChatModel, apiChatCapabilities, apiTimeoutMinutes,
				summaryPrompt, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withApiChatModel(String value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, summarizationService,
				localModel, apiBaseUrl, apiTranscriptionModel, value, apiChatCapabilities,
				apiTimeoutMinutes, summaryPrompt, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withApiChatCapabilities(List<SummarizerCapability> value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, summarizationService,
				localModel, apiBaseUrl, apiTranscriptionModel, apiChatModel, List.copyOf(value),
				apiTimeoutMinutes, summaryPrompt, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withApiTimeoutMinutes(int value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, summarizationService,
				localModel, apiBaseUrl, apiTranscriptionModel, apiChatModel, apiChatCapabilities, value,
				summaryPrompt, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withSummaryPrompt(String value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, summarizationService,
				localModel, apiBaseUrl, apiTranscriptionModel, apiChatModel, apiChatCapabilities,
				apiTimeoutMinutes, value, titlePrompt, autoProcess, autoTitle);
	}

	public MeetingSettings withTitlePrompt(String value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, summarizationService,
				localModel, apiBaseUrl, apiTranscriptionModel, apiChatModel, apiChatCapabilities,
				apiTimeoutMinutes, summaryPrompt, value, autoProcess, autoTitle);
	}

	public MeetingSettings withAutoProcess(boolean value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, summarizationService,
				localModel, apiBaseUrl, apiTranscriptionModel, apiChatModel, apiChatCapabilities,
				apiTimeoutMinutes, summaryPrompt, titlePrompt, value, autoTitle);
	}

	public MeetingSettings withAutoTitle(boolean value) {
		return new MeetingSettings(transcriptionService, whisperModel, language, summarizationService,
				localModel, apiBaseUrl, apiTranscriptionModel, apiChatModel, apiChatCapabilities,
				apiTimeoutMinutes, summaryPrompt, titlePrompt, autoProcess, value);
	}

	/** Runs these settings through the same checks a stored file gets. */
	public MeetingSettings normalized(MeetingAssets assets) {
		return normalize(toRaw(), assets);
	}

	/** The JSON object stored under "meetingSettings", in the Electron field order. */
	public Map<String, Object> toRaw() {
		java.util.LinkedHashMap<String, Object> raw = new java.util.LinkedHashMap<>();
		raw.put("transcriptionService", transcriptionService.wire());
		raw.put("whisperModel", whisperModel);
		raw.put("language", language);
		raw.put("summarizationService", summarizationService.wire());
		raw.put("localModel", localModel);
		raw.put("apiBaseUrl", apiBaseUrl);
		raw.put("apiTranscriptionModel", apiTranscriptionModel);
		raw.put("apiChatModel", apiChatModel);
		java.util.List<String> capabilities = new java.util.ArrayList<>();
		for (SummarizerCapability capability : apiChatCapabilities) {
			capabilities.add(capability.wire());
		}
		raw.put("apiChatCapabilities", capabilities);
		raw.put("apiTimeoutMinutes", apiTimeoutMinutes);
		raw.put("summaryPrompt", summaryPrompt);
		raw.put("titlePrompt", titlePrompt);
		raw.put("autoProcess", autoProcess);
		raw.put("autoTitle", autoTitle);
		return raw;
	}

	/** Keeps only known keys with valid values, so a bad file never breaks a job. */
	public static MeetingSettings normalize(Object raw, MeetingAssets assets) {
		Map<?, ?> input = raw instanceof Map<?, ?> map ? map : Map.of();
		MeetingServiceKind transcriptionService = MeetingServiceKind.fromWire(
				pick(input, "transcriptionService", MeetingSettings::isService,
						DEFAULT.transcriptionService().wire()));
		String whisperModel = (String) pick(input, "whisperModel",
				value -> value instanceof String id && assets.whisperModels().containsKey(id),
				DEFAULT.whisperModel());
		String language = (String) pick(input, "language", value -> isNonEmpty(value),
				DEFAULT.language());
		MeetingServiceKind summarizationService = MeetingServiceKind.fromWire(
				pick(input, "summarizationService", MeetingSettings::isService,
						DEFAULT.summarizationService().wire()));
		String localModel = (String) pick(input, "localModel",
				value -> value instanceof String id && assets.localLlmModels().containsKey(id),
				DEFAULT.localModel());
		String apiBaseUrl = (String) pick(input, "apiBaseUrl",
				value -> isNonEmpty(value) && String.valueOf(value).matches("^https?://.*"),
				DEFAULT.apiBaseUrl());
		String apiTranscriptionModel = (String) pick(input, "apiTranscriptionModel",
				MeetingSettings::isNonEmpty, DEFAULT.apiTranscriptionModel());
		String apiChatModel = (String) pick(input, "apiChatModel",
				MeetingSettings::isNonEmpty, DEFAULT.apiChatModel());
		Object rawCapabilities = input.get("apiChatCapabilities");
		List<SummarizerCapability> apiChatCapabilities = isCapabilities(rawCapabilities)
				? capabilities(rawCapabilities)
				: DEFAULT.apiChatCapabilities();
		int apiTimeoutMinutes = ((Number) pick(input, "apiTimeoutMinutes",
				value -> value instanceof Number number && number.doubleValue() >= 1
						&& number.doubleValue() <= 60,
				DEFAULT.apiTimeoutMinutes())).intValue();
		String summaryPrompt = (String) pick(input, "summaryPrompt",
				value -> value instanceof String, DEFAULT.summaryPrompt());
		String titlePrompt = (String) pick(input, "titlePrompt",
				value -> value instanceof String, DEFAULT.titlePrompt());
		boolean autoProcess = (Boolean) pick(input, "autoProcess",
				value -> value instanceof Boolean, DEFAULT.autoProcess());
		boolean autoTitle = (Boolean) pick(input, "autoTitle",
				value -> value instanceof Boolean, DEFAULT.autoTitle());
		return new MeetingSettings(
				transcriptionService,
				whisperModel,
				language,
				summarizationService,
				localModel,
				apiBaseUrl,
				apiTranscriptionModel,
				apiChatModel,
				List.copyOf(apiChatCapabilities),
				apiTimeoutMinutes,
				summaryPrompt,
				titlePrompt,
				autoProcess,
				autoTitle);
	}

	private static @Nullable Object pick(Map<?, ?> input, String key, Predicate<Object> valid,
			Object fallback) {
		Object value = input.get(key);
		return valid.test(value) ? value : fallback;
	}

	private static boolean isService(Object value) {
		return MeetingServiceKind.fromWire(value) != null;
	}

	private static boolean isNonEmpty(Object value) {
		return value instanceof String text && !text.strip().isEmpty();
	}

	private static boolean isCapabilities(@Nullable Object value) {
		if (!(value instanceof List<?> list)) {
			return false;
		}
		if (!list.contains("text")) {
			return false;
		}
		for (Object entry : list) {
			if (SummarizerCapability.fromWire(entry) == null) {
				return false;
			}
		}
		return true;
	}

	private static List<SummarizerCapability> capabilities(@Nullable Object value) {
		List<SummarizerCapability> capabilities = new ArrayList<>();
		for (Object entry : (List<?>) value) {
			capabilities.add(SummarizerCapability.fromWire(entry));
		}
		return capabilities;
	}
}
