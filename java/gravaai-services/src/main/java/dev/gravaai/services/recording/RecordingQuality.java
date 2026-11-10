package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

/**
 * The screen-recording video quality offered by Settings → Recording (card "Recording settings:
 * fps, quality and save location").
 *
 * <p>Each level maps to the encoder's constant-rate factor; the {@code ultrafast} preset stays for
 * every level so the encoder keeps up with the capture on any machine. "Lossless-ish" is
 * {@code -crf 1}, the highest quality x264 offers short of its lossless mode. It is deliberately
 * not {@code -crf 0}: that switches the stream to the High 4:4:4 Predictive profile (checked on
 * the bundled encoder, even with {@code yuv420p} frames), which no hardware decoder and few
 * players open — the recording would only ever decode in software. At {@code -crf 1} the stream
 * stays in a profile everything decodes and the difference is not visible; the pipeline's
 * {@code yuv420p} chroma subsampling already kept the old setting from being bit-exact.
 */
public enum RecordingQuality {
	STANDARD("standard", 25),
	HIGH("high", 18),
	LOSSLESS("lossless", 1);

	/** Default: High, the quality every recording had before the setting existed. */
	public static final RecordingQuality DEFAULT = HIGH;

	/** The {@code app-settings.json} key carrying the wire value. */
	public static final String VIDEO_QUALITY_SETTING = "videoQuality";

	private final String wire;
	private final int crf;

	RecordingQuality(String wire, int crf) {
		this.wire = wire;
		this.crf = crf;
	}

	/** The stored preference value. */
	public String wire() {
		return wire;
	}

	/** The x264 constant-rate factor the encoder is run with (lower is better). */
	public int crf() {
		return crf;
	}

	/** Parses a stored value; anything unknown falls back to the default. */
	public static RecordingQuality normalize(@Nullable String value) {
		for (RecordingQuality quality : values()) {
			if (quality.wire.equals(value)) {
				return quality;
			}
		}
		return DEFAULT;
	}
}
