package dev.gravaai.services.recording;

import java.util.List;

/**
 * Key-modifier and key-press shapes from {@code src/types/keystrokes.ts}, shared by the recorder and
 * the telemetry sidecar writer.
 */
public final class KeystrokeTypes {

	private KeystrokeTypes() {
	}

	/** A held modifier. */
	public enum KeyModifier {
		CTRL("ctrl"),
		ALT("alt"),
		SHIFT("shift"),
		SUPER("super");

		private final String wire;

		KeyModifier(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		public static KeyModifier fromWire(String value) {
			for (KeyModifier modifier : values()) {
				if (modifier.wire.equals(value)) {
					return modifier;
				}
			}
			return null;
		}
	}

	/** Draw order and label of each modifier chip: ctrl, alt, shift, super. */
	public static final List<KeyModifier> KEYSTROKE_MODIFIER_ORDER =
			List.of(KeyModifier.CTRL, KeyModifier.ALT, KeyModifier.SHIFT, KeyModifier.SUPER);

	/** One stored key press. */
	public record KeystrokeEvent(long timeMs, String key, List<KeyModifier> modifiers) {
	}

	/** Sorts modifiers into the shared chip order. */
	public static List<KeyModifier> orderedModifiers(java.util.Set<KeyModifier> held) {
		return KEYSTROKE_MODIFIER_ORDER.stream().filter(held::contains).toList();
	}
}
