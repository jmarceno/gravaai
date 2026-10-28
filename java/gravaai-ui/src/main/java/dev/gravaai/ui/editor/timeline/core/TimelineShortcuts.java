package dev.gravaai.ui.editor.timeline.core;

import java.util.Locale;

import javafx.scene.input.KeyCode;

/**
 * Timeline shortcut bindings (port of {@code TimelineShortcutBindings} + {@code matchesShortcut}
 * from {@code lib/shortcuts.ts}, scoped to the timeline actions).
 */
public final class TimelineShortcuts {

	/** Port of {@code ShortcutBinding}; {@code ctrl} means Cmd on macOS and Ctrl elsewhere. */
	public record Binding(String key, boolean ctrl, boolean shift, boolean alt) {
		public Binding(String key) {
			this(key, false, false, false);
		}

		public static Binding of(String key, boolean ctrl, boolean shift, boolean alt) {
			return new Binding(key, ctrl, shift, alt);
		}
	}

	/** Port of {@code TimelineShortcutBindings}. */
	public record Bindings(Binding addKeyframe, Binding addZoom, Binding splitClip,
			Binding addAnnotation, Binding deleteSelected) {
	}

	private TimelineShortcuts() {
	}

	/** Port of {@code DEFAULT_SHORTCUTS} for the timeline actions. */
	public static Bindings defaults() {
		return new Bindings(new Binding("f"), new Binding("z"), new Binding("c"),
				new Binding("a"), new Binding("d", true, false, false));
	}

	/**
	 * Port of {@code matchesShortcut}: the configured {@code ctrl} flag maps to Cmd on macOS and
	 * Ctrl elsewhere; shift and alt must match exactly.
	 */
	public static boolean matches(KeyCode code, boolean ctrl, boolean meta, boolean shift,
			boolean alt, Binding binding, boolean isMac) {
		if (binding == null || code == null) {
			return false;
		}
		if (!keyOf(code).equals(binding.key().toLowerCase(Locale.ROOT))) {
			return false;
		}
		boolean primaryMod = isMac ? meta : ctrl;
		if (primaryMod != binding.ctrl()) {
			return false;
		}
		if (shift != binding.shift()) {
			return false;
		}
		return alt == binding.alt();
	}

	/** Port of the DOM {@code event.key} values used by the TypeScript bindings. */
	public static String keyOf(KeyCode code) {
		return switch (code) {
			case SPACE -> " ";
			case DELETE -> "delete";
			case BACK_SPACE -> "backspace";
			case ESCAPE -> "escape";
			case TAB -> "tab";
			case UP -> "arrowup";
			case DOWN -> "arrowdown";
			case LEFT -> "arrowleft";
			case RIGHT -> "arrowright";
			default -> {
				String character = code.getChar();
				if (character != null && !character.isBlank()) {
					yield character.toLowerCase(Locale.ROOT);
				}
				yield code.getName().toLowerCase(Locale.ROOT);
			}
		};
	}
}
