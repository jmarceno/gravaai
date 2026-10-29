package dev.gravaai.ui.editor.timeline;

import org.jspecify.annotations.Nullable;

/**
 * Audio peak source for the timeline (port of the {@code AudioPeaksData} input).
 *
 * <p>The media layer's {@code AudioDecoder} will implement this later; the Phase A simulation and
 * TestFX tests inject fixture peaks, so the timeline never decodes real media.
 */
@FunctionalInterface
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public interface WaveformProvider {

	/**
	 * @param audioPath the audio file the region points at
	 * @param peakCount requested peak count (plan: {@code WAVEFORM_DEFAULT_PEAK_COUNT} = 2048)
	 * @return normalized peaks 0..1, or {@code null} when the file cannot be read
	 */
	@Nullable float[] peaks(String audioPath, int peakCount);

	/** A provider that never has waveform data (until the media layer is wired). */
	static @Nullable WaveformProvider empty() {
		return (audioPath, peakCount) -> null;
	}
}
