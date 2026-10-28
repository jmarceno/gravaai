package dev.gravaai.services.meeting;

/**
 * Inputs for {@link Transcript#whisperMeetingArgs(WhisperArgsOptions)}.
 *
 * @param modelPath path to the ggml whisper model
 * @param wavPath 16 kHz mono PCM WAV extracted from the recording
 * @param outputBase base path for whisper's {@code -of} outputs
 * @param language requested language code, empty/blank meaning auto-detect
 * @param threads whisper thread count
 * @param diarize enable whisper.cpp diarisation ({@code -di})
 * @param vadModelPath Silero VAD model, or null/empty to leave VAD off
 */
public record WhisperArgsOptions(
		String modelPath,
		String wavPath,
		String outputBase,
		String language,
		int threads,
		boolean diarize,
		String vadModelPath) {
}
