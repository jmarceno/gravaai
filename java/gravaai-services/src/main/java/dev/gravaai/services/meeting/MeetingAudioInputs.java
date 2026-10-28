package dev.gravaai.services.meeting;

import java.nio.file.Path;

/**
 * The audio sources for a meeting transcription: the recording itself, plus the per-speaker
 * sidecars when they exist.
 *
 * @param mediaPath the recorded media file
 * @param mic the microphone sidecar, or {@code null}
 * @param system the system-audio sidecar, or {@code null}
 */
public record MeetingAudioInputs(Path mediaPath, Path mic, Path system) {
}
