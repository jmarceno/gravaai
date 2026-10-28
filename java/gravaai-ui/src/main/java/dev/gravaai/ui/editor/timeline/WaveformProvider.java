package dev.gravaai.ui.editor.timeline;

/**
 * Audio peak source for the timeline (port of the {@code AudioPeaksData} input).
 *
 * <p>The media layer's {@code AudioDecoder} will implement this later; the Phase A simulation and
 * TestFX tests inject fixture peaks, so the timeline never decodes real media.
 */
@FunctionalInterface
public interface WaveformProvider {

	/**
	 * @param audioPath the audio file the region points at
	 * @param peakCount requested peak count (plan: {@code WAVEFORM_DEFAULT_PEAK_COUNT} = 2048)
	 * @return normalized peaks 0..1, or {@code null} when the file cannot be read
	 */
	float[] peaks(String audioPath, int peakCount);

	/** A provider that never has waveform data (until the media layer is wired). */
	static WaveformProvider empty() {
		return (audioPath, peakCount) -> null;
	}
}
