package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.util.function.UnaryOperator;

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
	 * Applies an edit to the stored settings and returns the normalised result. The edit runs on
	 * the current stored value, so two quick edits from the UI never undo each other.
	 */
	public synchronized MeetingSettings update(UnaryOperator<MeetingSettings> edit) {
		MeetingSettings next = edit.apply(read()).normalized(assets);
		store.set(SETTINGS_KEY, next.toRaw());
		store.save();
		return next;
	}

	/** The API key never leaves the main process; null when none is stored. */
	public @Nullable String readApiKey() {
		return apiKeys.read().orElse(null);
	}

	/** Stores the key; a blank key clears it. */
	public void writeApiKey(String apiKey) {
		String trimmed = apiKey == null ? "" : apiKey.strip();
		if (trimmed.isEmpty()) {
			apiKeys.clear();
			return;
		}
		apiKeys.write(trimmed);
	}

	public void clearApiKey() {
		apiKeys.clear();
	}
}
