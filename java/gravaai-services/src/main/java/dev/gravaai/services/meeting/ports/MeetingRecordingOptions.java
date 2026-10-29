package dev.gravaai.services.meeting.ports;

import org.jspecify.annotations.Nullable;

/**
 * The subset of Electron's NativeMacRecordingOptions the meeting UI passes when it starts an
 * audio-only meeting recording.
 */
public record MeetingRecordingOptions(
		@Nullable Boolean capturesSystemAudio,
		@Nullable Boolean capturesMicrophone,
		String microphoneDeviceId,
		String microphoneLabel) {
}
