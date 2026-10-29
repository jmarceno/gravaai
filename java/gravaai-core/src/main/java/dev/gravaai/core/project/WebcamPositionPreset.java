package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Webcam overlay anchor preset. */
public enum WebcamPositionPreset {
	TOP_LEFT("top-left"), TOP_CENTER("top-center"), TOP_RIGHT("top-right"),
	CENTER_LEFT("center-left"), CENTER("center"), CENTER_RIGHT("center-right"),
	BOTTOM_LEFT("bottom-left"), BOTTOM_CENTER("bottom-center"), BOTTOM_RIGHT("bottom-right"),
	CUSTOM("custom");

	private final String wire;

	WebcamPositionPreset(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static WebcamPositionPreset fromWire(String value) {
		for (WebcamPositionPreset preset : values()) {
			if (preset.wire.equals(value)) {
				return preset;
			}
		}
		return BOTTOM_RIGHT;
	}

	public static @Nullable WebcamPositionPreset fromWireOrNull(String value) {
		for (WebcamPositionPreset preset : values()) {
			if (preset.wire.equals(value)) {
				return preset;
			}
		}
		return null;
	}

	/** True for the four corner presets (which double as legacy corners). */
	public boolean isCorner() {
		return this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
	}
}
