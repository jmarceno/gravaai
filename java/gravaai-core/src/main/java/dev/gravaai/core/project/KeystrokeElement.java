package dev.gravaai.core.project;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * One recorded key press as a timeline element (the editable counterpart of the
 * {@code .keys.json} sidecar): the user can move, edit and delete individual presses on the
 * Keystrokes track. {@code modifiers} holds the canonical wires ({@code ctrl}, {@code alt},
 * {@code shift}, {@code super}) in chip order; {@code startMs}..{@code endMs} is the display
 * window (the overlay's linger time when seeded from the sidecar).
 */
public record KeystrokeElement(String id, long startMs, long endMs, String key,
		List<String> modifiers) {

	/** Modifier wires in draw order (mirrors the sidecar writer's canonical order). */
	public static final List<String> MODIFIER_ORDER = List.of("ctrl", "alt", "shift", "super");

	public KeystrokeElement {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("keystroke id must not be blank");
		}
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("keystroke key must not be blank");
		}
		modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
		if (endMs < startMs) {
			endMs = startMs;
		}
	}

	/** The chip labels the overlay and the timeline block draw: modifiers first, then the key. */
	public List<String> chips() {
		List<String> labels = new java.util.ArrayList<>(modifiers.size() + 1);
		for (String modifier : MODIFIER_ORDER) {
			if (modifiers.contains(modifier)) {
				labels.add(modifierLabel(modifier));
			}
		}
		labels.add(key);
		return labels;
	}

	/** The wire form of the modifiers, canonical order regardless of the stored order. */
	public List<String> orderedModifiers() {
		return MODIFIER_ORDER.stream().filter(modifiers::contains).toList();
	}

	/** Display label of a modifier wire ({@code ctrl} → {@code Ctrl}), or null when unknown. */
	public static @Nullable String modifierLabel(String wire) {
		return switch (wire == null ? "" : wire.toLowerCase(Locale.ROOT)) {
			case "ctrl" -> "Ctrl";
			case "alt" -> "Alt";
			case "shift" -> "Shift";
			case "super" -> "Super";
			default -> null;
		};
	}

	/** The canonical wire of a modifier label ({@code Ctrl} → {@code ctrl}), or null. */
	public static @Nullable String modifierWire(String label) {
		return switch (label == null ? "" : label.trim().toLowerCase(Locale.ROOT)) {
			case "ctrl" -> "ctrl";
			case "alt" -> "alt";
			case "shift" -> "shift";
			case "super" -> "super";
			default -> null;
		};
	}
}
