package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Camera layout while a clip plays (TS {@code "default" | "hide-camera" | "camera-only"}). */
public enum ClipCameraLayout {
	DEFAULT("default"), HIDE_CAMERA("hide-camera"), CAMERA_ONLY("camera-only");

	/** {@code CLIP_CAMERA_LAYOUTS}. */
	public static final List<ClipCameraLayout> ALL = List.of(DEFAULT, HIDE_CAMERA, CAMERA_ONLY);

	private final String wire;

	ClipCameraLayout(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static ClipCameraLayout fromWire(String value) {
		for (ClipCameraLayout layout : values()) {
			if (layout.wire.equals(value)) {
				return layout;
			}
		}
		throw new IllegalArgumentException("Unknown clip camera layout: " + value);
	}

	/** {@code fromWire} without a fallback: null for unknown or missing values. */
	public static @Nullable ClipCameraLayout fromWireOrNull(String value) {
		for (ClipCameraLayout layout : values()) {
			if (layout.wire.equals(value)) {
				return layout;
			}
		}
		return null;
	}
}
