package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Caption animation style (TS {@code AutoCaptionAnimation}). */
public enum AutoCaptionAnimation {
	NONE("none"), FADE("fade"), RISE("rise"), POP("pop");

	private final String wire;

	AutoCaptionAnimation(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static AutoCaptionAnimation fromWire(String value) {
		for (AutoCaptionAnimation animation : values()) {
			if (animation.wire.equals(value)) {
				return animation;
			}
		}
		return FADE;
	}
}
