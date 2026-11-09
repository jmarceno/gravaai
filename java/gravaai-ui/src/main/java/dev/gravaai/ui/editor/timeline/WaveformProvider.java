package dev.gravaai.ui.editor.timeline;

import org.jspecify.annotations.Nullable;

/**
 * Audio peak source for the timeline (port of the {@code AudioPeaksData} input).
 *
 * <p>Peaks are requested for a sub-range of the audio file in <em>audio time</em> — the caller
 * maps the block's visible pixels onto the file's time axis (clip source spans for the recording's
 * tracks, region offsets for imported audio), so trims, clip speed and zoom are all honoured and
 * the wave stays aligned to the playhead at every zoom level. The range-scoped request also keeps
 * hour-long recordings responsive: only the visible window is ever resampled.
 *
 * <p>{@code peaks} runs on the FX thread during a paint, so it must never block: an implementation
 * that computes peaks in the background returns {@code null} until they are ready and calls the
 * {@link #setOnPeaksReady} listener (any thread) once new data landed, which the view turns into a
 * repaint. The Phase A simulation and TestFX tests inject fixture peaks, so the timeline never
 * decodes real media itself.
 */
@FunctionalInterface
public interface WaveformProvider {

	/**
	 * @param audioPath the audio file the region points at
	 * @param sourceStartMs first audio-file millisecond the block shows
	 * @param sourceEndMs last audio-file millisecond the block shows (exclusive)
	 * @param peakCount requested peak count, one per bar across the visible width
	 * @return {@code peakCount} normalized peaks 0..1 for the range, or {@code null} when the
	 *     peaks are not (yet) readable
	 */
	float @Nullable [] peaks(String audioPath, double sourceStartMs, double sourceEndMs,
			int peakCount);

	/**
	 * Installs the repaint callback for a background provider: called once peaks became available
	 * for at least one file, on any thread. {@code null} clears it. The default provider never
	 * fires it.
	 */
	default void setOnPeaksReady(@Nullable Runnable listener) {
		// Synchronous providers have nothing to announce.
	}

	/** A provider that never has waveform data (until a decoding provider is wired). */
	static WaveformProvider empty() {
		return new WaveformProvider() {
			@Override
			public float @Nullable [] peaks(String audioPath, double sourceStartMs,
					double sourceEndMs, int peakCount) {
				return null;
			}
		};
	}
}
