package dev.gravaai.services.meeting;

/** Which of the two recording audio tracks a file is: the microphone or the system/tab output. */
public enum SpeakerRole {
	MIC("mic"),
	SYSTEM("system");

	private final String wire;

	SpeakerRole(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}
}
