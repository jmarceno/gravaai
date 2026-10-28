package dev.gravaai.services.sources;

/**
 * How a selected source is captured, from {@code SelectedSource.sourceType} in
 * {@code electron/ipc/types.ts}.
 */
public enum SourceType {
	SCREEN("screen"),
	WINDOW("window"),
	AREA("area");

	private final String wire;

	SourceType(String wire) {
		this.wire = wire;
	}

	/** The JSON string used by the Electron app. */
	public String wire() {
		return wire;
	}

	/** Parses a wire value, or null for an unknown one. */
	public static SourceType fromWire(String value) {
		for (SourceType type : values()) {
			if (type.wire.equals(value)) {
				return type;
			}
		}
		return null;
	}
}
