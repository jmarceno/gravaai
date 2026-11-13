package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;
import dev.gravaai.services.shortcuts.GlobalShortcutBinding;

/**
 * The push-to-talk key as the interaction helper sees it: one evdev key code plus the modifiers
 * that must be held with it, from {@link EvdevKeys}.
 *
 * <p>The binding comes from the global-shortcuts config (an xkb keysym name plus modifier flags);
 * this record is what {@link MicGate} matches the helper's {@code KEY:down}/{@code KEY:up} lines
 * against, so holding the shortcut gates the microphone and releasing it mutes.
 *
 * @param keyCode the evdev code of the key itself
 * @param required the modifiers that must be held (each as its {@link KeyModifier} family)
 */
public record PushToTalkKeys(int keyCode, Set<KeyModifier> required) {

	/**
	 * Keysym names the global-shortcut settings accept, mapped to evdev codes. Letters and digits
	 * follow the US layout's physical position, like {@link EvdevKeys}.
	 */
	private static final Map<String, Integer> KEYSYM_TO_CODE = Map.ofEntries(
			Map.entry("escape", 1),
			Map.entry("1", 2),
			Map.entry("2", 3),
			Map.entry("3", 4),
			Map.entry("4", 5),
			Map.entry("5", 6),
			Map.entry("6", 7),
			Map.entry("7", 8),
			Map.entry("8", 9),
			Map.entry("9", 10),
			Map.entry("0", 11),
			Map.entry("a", 30),
			Map.entry("b", 48),
			Map.entry("c", 46),
			Map.entry("d", 32),
			Map.entry("e", 18),
			Map.entry("f", 33),
			Map.entry("g", 34),
			Map.entry("h", 35),
			Map.entry("i", 23),
			Map.entry("j", 36),
			Map.entry("k", 37),
			Map.entry("l", 38),
			Map.entry("m", 50),
			Map.entry("n", 49),
			Map.entry("o", 24),
			Map.entry("p", 25),
			Map.entry("q", 16),
			Map.entry("r", 19),
			Map.entry("s", 31),
			Map.entry("t", 20),
			Map.entry("u", 22),
			Map.entry("v", 47),
			Map.entry("w", 17),
			Map.entry("x", 45),
			Map.entry("y", 21),
			Map.entry("z", 44),
			Map.entry("space", 57),
			Map.entry("return", 28),
			Map.entry("home", 102),
			Map.entry("end", 107),
			Map.entry("prior", 104),
			Map.entry("next", 109),
			Map.entry("insert", 110),
			Map.entry("delete", 111),
			Map.entry("up", 103),
			Map.entry("down", 108),
			Map.entry("left", 105),
			Map.entry("right", 106),
			Map.entry("f1", 59),
			Map.entry("f2", 60),
			Map.entry("f3", 61),
			Map.entry("f4", 62),
			Map.entry("f5", 63),
			Map.entry("f6", 64),
			Map.entry("f7", 65),
			Map.entry("f8", 66),
			Map.entry("f9", 67),
			Map.entry("f10", 68),
			Map.entry("f11", 87),
			Map.entry("f12", 88));

	/**
	 * The keys a push-to-talk binding may use: those the shortcut settings accept that evdev can
	 * also report. A binding without a modifier is rejected (it could not be grabbed globally).
	 */
	public static Optional<PushToTalkKeys> fromBinding(@Nullable GlobalShortcutBinding binding) {
		if (binding == null || !(binding.sup() || binding.ctrl() || binding.alt())) {
			return Optional.empty();
		}
		Integer code = KEYSYM_TO_CODE.get(binding.key().toLowerCase(Locale.ROOT));
		if (code == null) {
			return Optional.empty();
		}
		var required = new java.util.LinkedHashSet<KeyModifier>();
		if (binding.sup()) {
			required.add(KeyModifier.SUPER);
		}
		if (binding.ctrl()) {
			required.add(KeyModifier.CTRL);
		}
		if (binding.alt()) {
			required.add(KeyModifier.ALT);
		}
		if (binding.shift()) {
			required.add(KeyModifier.SHIFT);
		}
		return Optional.of(new PushToTalkKeys(code, Set.copyOf(required)));
	}
}
