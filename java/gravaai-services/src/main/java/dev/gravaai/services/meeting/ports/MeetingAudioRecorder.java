package dev.gravaai.services.meeting.ports;

/**
 * Starts the audio-only meeting recording that keeps the per-speaker tracks. The Lead wires this
 * to the recording service's {@code startCosmicAudioRecording({...options, keepSpeakerTracks:
 * true})} once it lands (electron/ipc/register/meeting.ts).
 */
public interface MeetingAudioRecorder {

	/** @throws IllegalStateException with the user-facing message when recording cannot start */
	void start(MeetingRecordingOptions options);
}
