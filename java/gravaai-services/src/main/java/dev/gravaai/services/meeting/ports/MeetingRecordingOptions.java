package dev.gravaai.services.meeting.ports;

/**
 * The subset of Electron's NativeMacRecordingOptions the meeting UI passes when it starts an
 * audio-only meeting recording.
 */
public record MeetingRecordingOptions(
		Boolean capturesSystemAudio,
		Boolean capturesMicrophone,
		String microphoneDeviceId,
		String microphoneLabel) {
}
