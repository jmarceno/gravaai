package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

/** Port of {@code AutomationExportFormat}. */
public enum AutomationExportFormat {
	MP4("mp4"),
	GIF("gif");

	private final String wire;

	AutomationExportFormat(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	public static @Nullable AutomationExportFormat fromWire(@Nullable String value) {
		if (value == null) {
			return null;
		}
		for (AutomationExportFormat format : values()) {
			if (format.wire.equals(value)) {
				return format;
			}
		}
		return null;
	}

	public static boolean isFormat(String value) {
		return fromWire(value) != null;
	}
}
