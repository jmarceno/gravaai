package dev.gravaai.core.project;

/** A playback-speed region ({@code PlaybackSpeed} in TS). */
public record SpeedRegion(String id, long startMs, long endMs, double speed) {

	/** {@code SPEED_OPTIONS}: speed and its UI label, in menu order. */
	public record SpeedOption(double speed, String label) {}

	public static final java.util.List<SpeedOption> SPEED_OPTIONS = java.util.List.of(
			new SpeedOption(0.25, "0.25×"),
			new SpeedOption(0.5, "0.5×"),
			new SpeedOption(0.75, "0.75×"),
			new SpeedOption(1.25, "1.25×"),
			new SpeedOption(1.5, "1.5×"),
			new SpeedOption(1.75, "1.75×"),
			new SpeedOption(2, "2×"));

	public static final double DEFAULT_PLAYBACK_SPEED = 1.5;
}
