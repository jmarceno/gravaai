package dev.gravaai.media.playback;

import java.util.List;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipTransitions;

/**
 * Clip-transition fade gain for the preview audio track (port of the preview half of
 * {@code useVideoEditorAudio.ts}: {@code getFadeGain(buildFadeWindows(clipRegions), timelineMs)}
 * multiplied into the preview volume, plan P3.4).
 *
 * <p>The export path evaluates the same fade windows as an ffmpeg {@code volume=eval=frame}
 * envelope ({@code ExportAudioGraph.fadeGainExpression}); both sides derive every boundary,
 * hold span and smoothstep curve from {@link ClipTransitions}, so preview and export attenuate
 * identically. This class only adds the per-sample application to a PCM chunk.
 *
 * <p>Stateless per lookup: a seek into the middle of a fade or a gap needs no state, the gain is a
 * pure function of the timeline position.
 */
public final class PlaybackFadeGain {

	/** No fade windows (no clip has a fade-black transition): gain is always 1. */
	public static final PlaybackFadeGain NONE = new PlaybackFadeGain(List.of());

	private final List<ClipTransitions.FadeWindow> windows;
	/** Timeline ranges (ms) of muted clips: {@code clipAudio.ts isClipMuted} plays them silent. */
	private final List<long[]> mutedRanges;

	public PlaybackFadeGain(List<ClipTransitions.FadeWindow> windows) {
		this(windows, List.of());
	}

	private PlaybackFadeGain(List<ClipTransitions.FadeWindow> windows, List<long[]> mutedRanges) {
		this.windows = windows == null ? List.of() : List.copyOf(windows);
		this.mutedRanges = List.copyOf(mutedRanges);
	}

	/** Fade windows and muted clips of a clip sequence, exactly as the export graph builds them. */
	public static PlaybackFadeGain forClips(List<ClipRegion> clips) {
		if (clips == null || clips.isEmpty()) {
			return NONE;
		}
		List<long[]> muted = new java.util.ArrayList<>();
		for (ClipRegion clip : clips) {
			if (Boolean.TRUE.equals(clip.muted()) && clip.endMs() > clip.startMs()) {
				muted.add(new long[] {clip.startMs(), clip.endMs()});
			}
		}
		boolean fades = ClipTransitions.hasClipTransitions(clips);
		if (!fades && muted.isEmpty()) {
			return NONE;
		}
		return new PlaybackFadeGain(fades ? ClipTransitions.buildFadeWindows(clips) : List.of(), muted);
	}

	/** True when at least one fade window or muted clip exists. */
	public boolean active() {
		return !windows.isEmpty() || !mutedRanges.isEmpty();
	}

	/** The fade windows in use (tests/diagnostics). */
	public List<ClipTransitions.FadeWindow> windows() {
		return windows;
	}

	/** Audio gain matching the picture fade at a timeline position in seconds. */
	public double gainAt(double timelineSeconds) {
		if (!active() || !Double.isFinite(timelineSeconds)) {
			return 1;
		}
		double timelineMs = timelineSeconds * 1000.0;
		for (long[] range : mutedRanges) {
			if (timelineMs >= range[0] && timelineMs < range[1]) {
				return 0;
			}
		}
		return windows.isEmpty() ? 1 : ClipTransitions.getFadeGain(windows, timelineMs);
	}

	/**
	 * Multiplies one interleaved PCM chunk in place by the fade gain of every frame.
	 *
	 * @param pcm interleaved samples
	 * @param frames frame count in the chunk
	 * @param channels channels per frame
	 * @param chunkStartSeconds timeline position of the chunk's first frame
	 */
	public void apply(float[] pcm, int frames, int channels, double chunkStartSeconds) {
		if (!active() || pcm == null || frames <= 0 || channels <= 0) {
			return;
		}
		for (int frame = 0; frame < frames; frame++) {
			double gain = gainAt(chunkStartSeconds + frame / (double) PlaybackAudioProducer.SAMPLE_RATE);
			if (gain >= 1) {
				continue;
			}
			float value = (float) gain;
			int base = frame * channels;
			for (int channel = 0; channel < channels; channel++) {
				pcm[base + channel] *= value;
			}
		}
	}
}
