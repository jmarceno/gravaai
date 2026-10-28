package dev.gravaai.services.meeting;

/** Progress callback of the pipeline stages; {@code progress} is null when unknown (0-100). */
@FunctionalInterface
public interface MeetingStatusCallback {

	MeetingStatusCallback IGNORING = (message, progress) -> {
	};

	void onStatus(String message, Integer progress);
}
