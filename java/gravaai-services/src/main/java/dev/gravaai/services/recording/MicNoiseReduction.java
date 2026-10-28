package dev.gravaai.services.recording;

/** Microphone noise-reduction level, from {@code MicNoiseReduction} in electron/ipc/recording/micNoiseReduction.ts. */
public enum MicNoiseReduction {
	OFF("off"),
	LIGHT("light"),
	BALANCED("balanced"),
	STRONG("strong");

	/** Default: no noise reduction. */
	public static final MicNoiseReduction DEFAULT = OFF;

	private final String wire;

	MicNoiseReduction(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}
}
