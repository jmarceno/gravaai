package dev.gravaai.ui.launch;

import java.util.function.LongSupplier;

/**
 * Port of {@code src/components/launch/hooks/useRecordingTimer.ts} (plan §5.12, §7.1 P1.7).
 *
 * <p>Elapsed recording seconds with pause accounting; the clock is injectable so the port is
 * unit-tested without waiting. Formatting is {@code mm:ss}.
 */
public final class RecordingTimer {

	private final LongSupplier clock;
	private long recordingStart = -1;
	private long pausedAt = -1;
	private long pausedTotal;
	private boolean recording;
	private boolean paused;

	public RecordingTimer() {
		this(System::currentTimeMillis);
	}

	public RecordingTimer(LongSupplier clock) {
		this.clock = clock;
	}

	/** React {@code recording} prop transition; starting a new recording resets the pause totals. */
	public void setRecording(boolean nextRecording) {
		if (nextRecording == recording) {
			return;
		}
		recording = nextRecording;
		if (recording) {
			recordingStart = clock.getAsLong();
			pausedAt = -1;
			pausedTotal = 0;
		} else {
			recordingStart = -1;
			pausedAt = -1;
			pausedTotal = 0;
		}
	}

	/** React {@code paused} prop transition; while paused the elapsed time stops advancing. */
	public void setPaused(boolean nextPaused) {
		if (!recording || nextPaused == paused) {
			return;
		}
		paused = nextPaused;
		if (paused) {
			pausedAt = clock.getAsLong();
		} else if (pausedAt >= 0) {
			pausedTotal += clock.getAsLong() - pausedAt;
			pausedAt = -1;
		}
	}

	/** Whole elapsed seconds; 0 when not recording. */
	public int elapsedSeconds() {
		if (!recording || recordingStart < 0) {
			return 0;
		}
		long now = paused && pausedAt >= 0 ? pausedAt : clock.getAsLong();
		return (int) Math.max(0, (now - recordingStart - pausedTotal) / 1000);
	}

	/** {@code mm:ss}, the format of {@code formatTime} in useRecordingTimer.ts. */
	public static String format(int seconds) {
		int minutes = Math.max(0, seconds) / 60;
		int rest = Math.max(0, seconds) % 60;
		return String.format("%02d:%02d", minutes, rest);
	}
}
