package dev.gravaai.services.recording;

/** How key presses are captured, from {@code KeystrokeCaptureMode} in electron/ipc/keyboard/keystrokeCapture.ts. */
public enum KeystrokeCaptureMode {
	OFF("off"),
	SHORTCUTS("shortcuts"),
	ALL("all");

	private final String wire;

	KeystrokeCaptureMode(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}
}
