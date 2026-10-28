package dev.gravaai.services.recording;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;

/**
 * Turns the helper's {@code KEY:down}/{@code KEY:up} lines into key press events, ported from
 * {@code electron/ipc/keyboard/keystrokeCapture.ts}.
 *
 * <p>Privacy: presses are kept in memory until the recording is persisted, and only the sidecar
 * next to the video is ever written. Nothing is logged beyond event counts, and "shortcuts" mode
 * never stores plain typing.
 */
public final class KeystrokeCapture {

	/** App setting that stores the capture mode. */
	public static final String KEYSTROKE_CAPTURE_MODE_SETTING = "keystrokeCaptureMode";

	/** One hour of presses at 30 presses per second. */
	public static final int MAX_KEYSTROKE_EVENTS = 60 * 60 * 30;

	private static final Pattern KEY_LINE = Pattern.compile("^KEY:(down|up):(\\d+)$");

	private KeystrokeCapture() {
	}

	public static KeystrokeCaptureMode normalizeKeystrokeCaptureMode(String value) {
		if ("shortcuts".equals(value)) {
			return KeystrokeCaptureMode.SHORTCUTS;
		}
		if ("all".equals(value)) {
			return KeystrokeCaptureMode.ALL;
		}
		return KeystrokeCaptureMode.OFF;
	}

	/**
	 * "shortcuts" keeps a press only when Ctrl, Alt or Super is held, or the key is a special key;
	 * Shift alone is treated as plain typing.
	 */
	public static boolean shouldRecordPress(
			int code, Set<KeyModifier> modifiers, KeystrokeCaptureMode mode) {
		if (mode == KeystrokeCaptureMode.OFF) {
			return false;
		}
		if (mode == KeystrokeCaptureMode.ALL) {
			return true;
		}
		for (KeyModifier modifier : modifiers) {
			if (modifier != KeyModifier.SHIFT) {
				return true;
			}
		}
		return EvdevKeys.SPECIAL_KEY_CODES.contains(code);
	}

	/** The recorder contract from the TS source. */
	public interface KeystrokeRecorder {

		/**
		 * Feeds one helper line. {@code record} is false while the recording is paused: modifier
		 * state keeps tracking (so a Ctrl released during a pause does not stick) but no press is
		 * stored.
		 */
		void handleLine(String line, long timeMs, boolean record);

		default void handleLine(String line, long timeMs) {
			handleLine(line, timeMs, true);
		}

		/** Returns and clears the stored events. */
		List<KeystrokeEvent> takeEvents();
	}

	public static KeystrokeRecorder createKeystrokeRecorder(KeystrokeCaptureMode mode) {
		return new KeystrokeRecorderImpl(mode);
	}

	private static final class KeystrokeRecorderImpl implements KeystrokeRecorder {

		private final KeystrokeCaptureMode mode;
		private final Set<Integer> heldCodes = new LinkedHashSet<>();
		private final List<KeystrokeEvent> events = new ArrayList<>();

		KeystrokeRecorderImpl(KeystrokeCaptureMode mode) {
			this.mode = mode;
		}

		@Override
		public void handleLine(String line, long timeMs, boolean record) {
			if (line == null) {
				return;
			}
			Matcher match = KEY_LINE.matcher(line);
			if (!match.matches()) {
				return;
			}
			int code = Integer.parseInt(match.group(2));
			if ("up".equals(match.group(1))) {
				heldCodes.remove(code);
				return;
			}
			// Two physical Ctrl/Alt/Shift keys are tracked per code.
			if (EvdevKeys.isModifierCode(code)) {
				heldCodes.add(code);
				return;
			}
			if (!record || events.size() >= MAX_KEYSTROKE_EVENTS) {
				return;
			}
			String label = EvdevKeys.evdevKeyLabel(code);
			if (label == null) {
				return;
			}
			Set<KeyModifier> held = new LinkedHashSet<>();
			for (int heldCode : heldCodes) {
				KeyModifier modifier = EvdevKeys.MODIFIER_CODES.get(heldCode);
				if (modifier != null) {
					held.add(modifier);
				}
			}
			List<KeyModifier> modifiers = KeystrokeTypes.orderedModifiers(held);
			if (!shouldRecordPress(code, new LinkedHashSet<>(modifiers), mode)) {
				return;
			}
			events.add(new KeystrokeEvent(Math.max(0, timeMs), label, modifiers));
		}

		@Override
		public List<KeystrokeEvent> takeEvents() {
			List<KeystrokeEvent> taken = List.copyOf(events);
			events.clear();
			return taken;
		}
	}

	// The recorder lives until the recording is persisted, which happens after the interaction
	// capture cleanup has already stopped the helper.
	private static volatile KeystrokeRecorder activeRecorder;

	public static void setActiveKeystrokeRecorder(KeystrokeRecorder recorder) {
		activeRecorder = recorder;
	}

	public static List<KeystrokeEvent> takeRecordedKeystrokes() {
		KeystrokeRecorder recorder = activeRecorder;
		return recorder == null ? List.of() : recorder.takeEvents();
	}
}
