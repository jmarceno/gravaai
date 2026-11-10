package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

/**
 * The screen-recording video quality offered by Settings → Recording (card "Recording settings:
 * fps, quality and save location").
 *
 * <p>Each level maps to the encoder's constant-rate factor; the {@code ultrafast} preset stays for
 * every level so the encoder keeps up with the capture on any machine. "Lossless-ish" is
 * {@code -crf 0}: mathematically lossless for the frames the encoder sees, while the pipeline's
 * {@code yuv420p} chroma subsampling keeps it from being bit-exact against the screen — hence the
 * name, and the very large files.
 */
public enum RecordingQuality {
	STANDARD("standard", 25),
	HIGH("high", 18),
	LOSSLESS("lossless", 0);

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
