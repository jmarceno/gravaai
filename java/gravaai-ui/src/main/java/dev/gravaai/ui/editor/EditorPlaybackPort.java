package dev.gravaai.ui.editor;

import java.util.List;
import java.util.Locale;

import dev.gravaai.core.event.UiEvents;

/**
 * Callback seam for the playback engine (plan §5.9, being built by the recording teammate).
 *
 * <p>The controls never touch a decoder: they read {@link #state()}, publish commands through the
 * methods and re-render on {@link #changes()}. The real engine will publish state changes from its
 * own thread; the shell hops to the FX thread.
 */
public interface EditorPlaybackPort {

	/** Snapshot of what the playback bar needs. */
	record PlaybackState(
			boolean isPlaying, double currentTimeSeconds, double durationSeconds, double volume,
			double rate) {

		public static final PlaybackState IDLE = new PlaybackState(false, 0, 0, 1, 1);
	}

	/**
	 * The preview rate vocabulary: quarter steps over the same 0.25&ndash;4 range the clip-speed
	 * controls use. The engine itself accepts wider rates; the editor UI offers these.
	 */
	double RATE_MIN = 0.25;
	double RATE_MAX = 4;
	double RATE_STEP = 0.25;

	/** The speed-menu presets in display order: quarter steps plus the skim speeds. */
	List<Double> RATE_PRESETS = List.of(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0);

	/** Number of preset columns in the speed menu grid. */
	int RATE_PRESET_COLUMNS = 5;

	PlaybackState state();

	/** State changes published by the engine (audio clock master, seeks, volume, rate). */
	UiEvents<PlaybackState> changes();

	void togglePlayPause();

	void seek(double seconds);

	/** Skip back/forward buttons; the engine decides the step (usually a few seconds). */
	void skip(double deltaSeconds);

	void setVolume(double volume);

	/** Preview playback speed: the timeline advances at this rate; clip speeds stay untouched. */
	void setRate(double rate);

	/** Clamps a candidate rate into the editor range; anything broken becomes normal speed. */
	static double normalizeRate(double rate) {
		if (!Double.isFinite(rate)) {
			return 1;
		}
		return Math.clamp(rate, RATE_MIN, RATE_MAX);
	}

	/** Moves {@code steps} quarter-steps from {@code current}, staying inside the range. */
	static double stepRate(double current, int steps) {
		double base = normalizeRate(current);
		double next = Math.round((base + steps * RATE_STEP) * 100.0) / 100.0;
		return Math.clamp(next, RATE_MIN, RATE_MAX);
	}

	/** {@code 1 -> "1.0×"}, {@code 1.5 -> "1.5×"}, {@code 1.25 -> "1.25×"} (Locale.ROOT). */
	static String formatRate(double rate) {
		double value = Math.round(rate * 100.0) / 100.0;
		if (value == Math.rint(value)) {
			return String.format(Locale.ROOT, "%.1f", value) + "×";
		}
		String text = String.format(Locale.ROOT, "%.2f", value);
		if (text.endsWith("0")) {
			text = text.substring(0, text.length() - 1);
		}
		return text + "×";
	}
}
