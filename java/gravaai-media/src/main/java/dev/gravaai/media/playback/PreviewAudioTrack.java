package dev.gravaai.media.playback;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.project.AudioCleanup;

/**
 * One source-audio track of the preview mix: the file, its gain and the edit-time cleanup applied
 * while decoding. The preview resolves these from the project's source-audio tracks + settings, so
 * the same cleanup the export applies is heard while editing (plan §5.9 preview parity).
 *
 * @param audioPath the decoded file (a sidecar or the recording itself)
 * @param gain linear gain, clamped to the exporter's {@code 0..2} track range
 * @param cleanup the cleanup stages (never {@code null}; {@link AudioCleanup#NONE} means untouched)
 * @param denoisedPath the pre-rendered denoised copy of {@code audioPath}
 *     ({@link dev.gravaai.media.denoise.DenoisedAudio}), or null while it is not rendered: the
 *     noise reduction then runs through the ffmpeg fallback chain
 */
public record PreviewAudioTrack(String audioPath, double gain, AudioCleanup cleanup,
		@Nullable String denoisedPath) {

	/** The widest track gain the exporter accepts, kept identical here so preview == export. */
	public static final double MAX_GAIN = 2;

	/** A track without a rendered denoised copy. */
	public PreviewAudioTrack(String audioPath, double gain, AudioCleanup cleanup) {
		this(audioPath, gain, cleanup, null);
	}

	public PreviewAudioTrack {
		if (audioPath == null || audioPath.isBlank()) {
			throw new IllegalArgumentException("audioPath must not be blank");
		}
		cleanup = cleanup == null ? AudioCleanup.NONE : cleanup;
		gain = Double.isFinite(gain) ? Math.max(0, Math.min(MAX_GAIN, gain)) : 1;
		denoisedPath = denoisedPath == null || denoisedPath.isBlank() ? null : denoisedPath;
	}

	/** A track without gain or cleanup: the recording's own audio. */
	public static PreviewAudioTrack plain(String audioPath) {
		return new PreviewAudioTrack(audioPath, 1, AudioCleanup.NONE);
	}

	/** True when the track decodes through gain/cleanup stages rather than plain. */
	public boolean isPlain() {
		return Math.abs(gain - 1) < 0.0005 && !cleanup.isActive();
	}

	/** True when the noise reduction of this track plays from its rendered denoised copy. */
	public boolean usesDenoisedCopy() {
		return denoisedPath != null
				&& cleanup.noiseReduction() != AudioCleanup.NoiseReduction.OFF;
	}
}
