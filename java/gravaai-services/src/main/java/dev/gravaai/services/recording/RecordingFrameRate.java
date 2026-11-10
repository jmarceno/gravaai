package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

/**
 * The screen-capture frame rate offered by Settings → Recording (card "Recording settings:
 * fps, quality and save location").
 *
 * <p>Both capture helpers ({@code gravaai-wayland-capture}, {@code gravaai-portal-capture}) take
 * the rate as their second argument, so one choice serves every desktop backend. The value is read
 * at each recording start; a change applies to the next recording.
 */
public enum RecordingFrameRate {
	FPS_30("30", 30),
	FPS_60("60", 60);

	/** Default: 60 fps, what the helper always captured before the setting existed. */
	public static final RecordingFrameRate DEFAULT = FPS_60;

	/** The {@code app-settings.json} key carrying the wire value. */
	public static final String CAPTURE_FPS_SETTING = "captureFps";

	private final String wire;
	private final int fps;

	RecordingFrameRate(String wire, int fps) {
		this.wire = wire;
		this.fps = fps;
	}

	/** The stored preference value. */
	public String wire() {
		return wire;
	}

	/** Frames per second handed to the capture helper and the encoder's keyframe interval. */
	public int fps() {
		return fps;
	}

	/** Parses a stored value ({@code "30"}, {@code "60"}); anything else falls back to the default. */
	public static RecordingFrameRate normalize(@Nullable String value) {
		for (RecordingFrameRate rate : values()) {
			if (rate.wire.equals(value)) {
				return rate;
			}
		}
		return DEFAULT;
	}
}
