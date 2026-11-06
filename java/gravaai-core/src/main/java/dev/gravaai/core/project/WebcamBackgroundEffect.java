package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What the webcam does with the room behind the person: nothing, blur it (the card stays, the room
 * becomes a blur), or remove it (the card is gone entirely and the cut-out person floats over the
 * scene behind the webcam).
 *
 * <p>One enum rather than two booleans, so "blur and remove both on" is unrepresentable. Projects
 * saved before this existed carry the legacy {@code backgroundBlur} boolean, which the persistence
 * layer maps to {@link #BLUR} / {@link #NONE}.</p>
 */
public enum WebcamBackgroundEffect {
	NONE("none"), BLUR("blur"), REMOVE("remove");

	private final String wire;

	WebcamBackgroundEffect(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static WebcamBackgroundEffect fromWire(String value) {
		WebcamBackgroundEffect effect = fromWireOrNull(value);
		return effect == null ? NONE : effect;
	}

	/** The effect for a wire value, or {@code null} when the value is unknown/absent. */
	public static @Nullable WebcamBackgroundEffect fromWireOrNull(String value) {
		for (WebcamBackgroundEffect effect : values()) {
			if (effect.wire.equals(value)) {
				return effect;
			}
		}
		return null;
	}

	/** True for both blur and remove: the segmenter has to produce a mask. */
	public boolean segmentsPerson() {
		return this != NONE;
	}
}
