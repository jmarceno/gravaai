package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

/** Progress callback of the pipeline stages; {@code progress} is null when unknown (0-100). */
@FunctionalInterface
public interface MeetingStatusCallback {

	MeetingStatusCallback IGNORING = (message, progress) -> {
	};

	/**
	 * @param progress the percentage, or null when the stage cannot estimate progress
	 */
	void onStatus(String message, @Nullable Integer progress);
}
