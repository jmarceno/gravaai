package dev.gravaai.core.settings;

/**
 * A partial update of {@code recordings-settings.json} (port of the
 * {@code RecordingPreferencesPatch} interface in
 * {@code electron/ipc/settings/recordingPreferencesStore.ts}).
 *
 * <p>Nullable components mirror the optional fields: a {@code null} component is an omitted key and
 * is not written.
 */
public record RecordingPreferencesPatch(
		Boolean microphoneEnabled,
		String microphoneDeviceId,
		Boolean systemAudioEnabled,
		Boolean webcamEnabled,
		String webcamDeviceId) {}
