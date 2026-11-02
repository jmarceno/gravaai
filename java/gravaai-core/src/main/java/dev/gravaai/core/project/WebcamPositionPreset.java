package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Webcam overlay anchor preset. The {@code split-*} values lay the camera out as a full-bleed
 *  region beside or above/below the screen content instead of a floating bubble. */
public enum WebcamPositionPreset {
	TOP_LEFT("top-left"), TOP_CENTER("top-center"), TOP_RIGHT("top-right"),
	CENTER_LEFT("center-left"), CENTER("center"), CENTER_RIGHT("center-right"),
	BOTTOM_LEFT("bottom-left"), BOTTOM_CENTER("bottom-center"), BOTTOM_RIGHT("bottom-right"),
	CUSTOM("custom"),
	SPLIT_RIGHT("split-right"), SPLIT_LEFT("split-left"),
	SPLIT_TOP("split-top"), SPLIT_BOTTOM("split-bottom");

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

	/** True for the split screen presets (camera region beside/above/below the content). */
	public boolean isSplit() {
		return this == SPLIT_RIGHT || this == SPLIT_LEFT || this == SPLIT_TOP
				|| this == SPLIT_BOTTOM;
	}
}
