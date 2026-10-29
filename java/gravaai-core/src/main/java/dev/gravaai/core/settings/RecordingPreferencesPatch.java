package dev.gravaai.core.settings;

import org.jspecify.annotations.Nullable;

/**
 * A partial update of {@code recordings-settings.json} (port of the
 * {@code RecordingPreferencesPatch} interface in
 * {@code electron/ipc/settings/recordingPreferencesStore.ts}).
 *
 * <p>Nullable components mirror the optional fields: a {@code null} component is an omitted key and
 * is not written.
 */
public record RecordingPreferencesPatch(
		@Nullable Boolean microphoneEnabled,
		@Nullable String microphoneDeviceId,
		@Nullable Boolean systemAudioEnabled,
		@Nullable Boolean webcamEnabled,
		@Nullable String webcamDeviceId) {}
