package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;

import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;

/**
 * evdev keycodes (linux/input-event-codes.h) mapped to display labels, ported from
 * {@code electron/ipc/keyboard/evdevKeys.ts}.
 *
 * <p>Labels follow the US layout's physical position: a shortcut such as Ctrl+S stays correct on
 * other layouts because the modifier is bound to the same physical key, but in "all keys" mode
 * letters can be wrong on non-US layouts.
 */
public final class EvdevKeys {

	/** evdev codes of the eight physical modifier keys. */
	public static final Map<Integer, KeyModifier> MODIFIER_CODES = Map.ofEntries(
			Map.entry(29, KeyModifier.CTRL),
			Map.entry(97, KeyModifier.CTRL),
			Map.entry(56, KeyModifier.ALT),
			Map.entry(100, KeyModifier.ALT),
			Map.entry(42, KeyModifier.SHIFT),
			Map.entry(54, KeyModifier.SHIFT),
			Map.entry(125, KeyModifier.SUPER),
			Map.entry(126, KeyModifier.SUPER));

	/** Keys recorded in "shortcuts" mode even without a modifier. */
	public static final Set<Integer> SPECIAL_KEY_CODES = Set.of(
			1, // Esc
			14, // Backspace
			15, // Tab
			28, // Enter
			96, // Keypad Enter
			102, // Home
			103, // Up
			104, // Page Up
			105, // Left
			106, // Right
			107, // End
			108, // Down
			109, // Page Down
			110, // Insert
			111, // Delete
			59, 60, 61, 62, 63, 64, 65, 66, 67, 68, // F1-F10
			87, // F11
			88); // F12

	private static final Map<Integer, String> EVDEV_KEY_LABELS = Map.ofEntries(
			Map.entry(1, "Esc"),
			Map.entry(2, "1"),
			Map.entry(3, "2"),
			Map.entry(4, "3"),
			Map.entry(5, "4"),
			Map.entry(6, "5"),
			Map.entry(7, "6"),
			Map.entry(8, "7"),
			Map.entry(9, "8"),
			Map.entry(10, "9"),
			Map.entry(11, "0"),
			Map.entry(12, "-"),
			Map.entry(13, "="),
			Map.entry(14, "Backspace"),
			Map.entry(15, "Tab"),
			Map.entry(16, "Q"),
			Map.entry(17, "W"),
			Map.entry(18, "E"),
			Map.entry(19, "R"),
			Map.entry(20, "T"),
			Map.entry(21, "Y"),
			Map.entry(22, "U"),
			Map.entry(23, "I"),
			Map.entry(24, "O"),
			Map.entry(25, "P"),
			Map.entry(26, "["),
			Map.entry(27, "]"),
			Map.entry(28, "Enter"),
			Map.entry(30, "A"),
			Map.entry(31, "S"),
			Map.entry(32, "D"),
			Map.entry(33, "F"),
			Map.entry(34, "G"),
			Map.entry(35, "H"),
			Map.entry(36, "J"),
			Map.entry(37, "K"),
			Map.entry(38, "L"),
			Map.entry(39, ";"),
			Map.entry(40, "'"),
			Map.entry(41, "`"),
			Map.entry(43, "\\"),
			Map.entry(44, "Z"),
			Map.entry(45, "X"),
			Map.entry(46, "C"),
			Map.entry(47, "V"),
			Map.entry(48, "B"),
			Map.entry(49, "N"),
			Map.entry(50, "M"),
			Map.entry(51, ","),
			Map.entry(52, "."),
			Map.entry(53, "/"),
			Map.entry(57, "Space"),
			Map.entry(58, "Caps Lock"),
			Map.entry(59, "F1"),
			Map.entry(60, "F2"),
			Map.entry(61, "F3"),
			Map.entry(62, "F4"),
			Map.entry(63, "F5"),
			Map.entry(64, "F6"),
			Map.entry(65, "F7"),
			Map.entry(66, "F8"),
			Map.entry(67, "F9"),
			Map.entry(68, "F10"),
			Map.entry(87, "F11"),
			Map.entry(88, "F12"),
			Map.entry(96, "Enter"),
			Map.entry(102, "Home"),
			Map.entry(103, "↑"),
			Map.entry(104, "Page Up"),
			Map.entry(105, "←"),
			Map.entry(106, "→"),
			Map.entry(107, "End"),
			Map.entry(108, "↓"),
			Map.entry(109, "Page Down"),
			Map.entry(110, "Insert"),
			Map.entry(111, "Delete"));

	private EvdevKeys() {
	}

	/** Display label for an evdev keycode, or null when the key is ignored. */
	public static @Nullable String evdevKeyLabel(int code) {
		KeyModifier modifier = MODIFIER_CODES.get(code);
		if (modifier != null) {
			return capitalizeModifier(modifier);
		}
		return EVDEV_KEY_LABELS.get(code);
	}

	public static boolean isModifierCode(int code) {
		return MODIFIER_CODES.containsKey(code);
	}

	private static String capitalizeModifier(KeyModifier modifier) {
		String wire = modifier.wire();
		return wire.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + wire.substring(1);
	}
}
