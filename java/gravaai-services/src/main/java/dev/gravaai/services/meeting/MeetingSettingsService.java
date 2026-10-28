package dev.gravaai.services.meeting;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.gravaai.services.meeting.ports.MeetingApiKeyStore;
import dev.gravaai.services.meeting.ports.SettingsStore;

/** Meeting settings and the API key, ported from electron/ipc/meeting/settings.ts. */
public final class MeetingSettingsService {

	public static final String SETTINGS_KEY = "meetingSettings";
	public static final String API_KEY_KEY = "meetingApiKey";

	private final SettingsStore store;
	private final MeetingApiKeyStore apiKeys;
	private final MeetingAssets assets;

	public MeetingSettingsService(SettingsStore store, MeetingApiKeyStore apiKeys,
			MeetingAssets assets) {
		this.store = store;
		this.apiKeys = apiKeys;
		this.assets = assets;
	}

	public MeetingSettings read() {
		return MeetingSettings.normalize(store.get(SETTINGS_KEY), assets);
	}

	public MeetingSettings.View readView() {
		return new MeetingSettings.View(read(), apiKeys.read().isPresent());
	}

	/**
	 * Merges a partial settings object (and an optional plain "apiKey" entry) into the stored
	 * settings and returns the normalised result.
	 */
	public MeetingSettings write(Map<String, Object> patch) {
		Map<String, Object> settingsPatch = new LinkedHashMap<>();
		if (patch != null) {
			settingsPatch.putAll(patch);
		}
		if (settingsPatch.containsKey("apiKey")) {
			Object apiKey = settingsPatch.remove("apiKey");
			writeApiKey(apiKey);
		}
		Map<String, Object> merged = new LinkedHashMap<>();
		Object raw = store.get(SETTINGS_KEY);
		if (raw instanceof Map<?, ?> rawMap) {
			for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
				merged.put(String.valueOf(entry.getKey()), entry.getValue());
			}
		}
		merged.putAll(settingsPatch);
		MeetingSettings next = MeetingSettings.normalize(merged, assets);
		store.set(SETTINGS_KEY, next.toRaw());
		store.save();
		return next;
	}

	/** The API key never leaves the main process; null when none is stored. */
	public String readApiKey() {
		return apiKeys.read().orElse(null);
	}

	public void writeApiKey(Object apiKey) {
		String trimmed = apiKey instanceof String text ? text.strip() : null;
		if (trimmed == null || trimmed.isEmpty()) {
			apiKeys.clear();
			return;
		}
		apiKeys.write(trimmed);
	}
}
