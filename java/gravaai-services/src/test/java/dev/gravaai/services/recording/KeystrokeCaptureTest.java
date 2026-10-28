package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;

/** Ported from {@code electron/ipc/keyboard/keystrokeCapture.test.ts}. */
class KeystrokeCaptureTest {

	private static final int KEY_A = 30;
	private static final int KEY_S = 31;
	private static final int KEY_H = 35;
	private static final int KEY_E = 18;
	private static final int KEY_L = 38;
	private static final int KEY_O = 24;
	private static final int KEY_ENTER = 28;
	private static final int KEY_LEFT_CTRL = 29;
	private static final int KEY_RIGHT_CTRL = 97;
	private static final int KEY_LEFT_SHIFT = 42;
	private static final int KEY_UP = 103;

	private static void type(KeystrokeCapture.KeystrokeRecorder recorder, int code, long timeMs) {
		recorder.handleLine("KEY:down:" + code, timeMs);
		recorder.handleLine("KEY:up:" + code, timeMs + 10);
	}

	private static void type(
			KeystrokeCapture.KeystrokeRecorder recorder, int code, long timeMs, boolean record) {
		recorder.handleLine("KEY:down:" + code, timeMs, record);
		recorder.handleLine("KEY:up:" + code, timeMs + 10, record);
	}

	@Test
	@DisplayName("falls back to off for anything unknown")
	void fallsBackToOff() {
		assertEquals(KeystrokeCaptureMode.SHORTCUTS, KeystrokeCapture.normalizeKeystrokeCaptureMode("shortcuts"));
		assertEquals(KeystrokeCaptureMode.ALL, KeystrokeCapture.normalizeKeystrokeCaptureMode("all"));
		assertEquals(KeystrokeCaptureMode.OFF, KeystrokeCapture.normalizeKeystrokeCaptureMode("everything"));
		assertEquals(KeystrokeCaptureMode.OFF, KeystrokeCapture.normalizeKeystrokeCaptureMode(null));
	}

	@Test
	@DisplayName("never records in off mode")
	void neverRecordsInOffMode() {
		assertFalse(KeystrokeCapture.shouldRecordPress(KEY_S, Set.of(KeyModifier.CTRL), KeystrokeCaptureMode.OFF));
	}

	@Test
	@DisplayName("records everything in all mode")
	void recordsEverythingInAllMode() {
		assertTrue(KeystrokeCapture.shouldRecordPress(KEY_A, Set.of(), KeystrokeCaptureMode.ALL));
	}

	@Test
	@DisplayName("records shortcuts and special keys only in shortcuts mode")
	void recordsShortcutsAndSpecialKeysOnly() {
		assertTrue(KeystrokeCapture.shouldRecordPress(KEY_S, Set.of(KeyModifier.CTRL), KeystrokeCaptureMode.SHORTCUTS));
		assertTrue(KeystrokeCapture.shouldRecordPress(KEY_S, Set.of(KeyModifier.SUPER), KeystrokeCaptureMode.SHORTCUTS));
		assertTrue(KeystrokeCapture.shouldRecordPress(KEY_ENTER, Set.of(), KeystrokeCaptureMode.SHORTCUTS));
		assertTrue(KeystrokeCapture.shouldRecordPress(KEY_UP, Set.of(), KeystrokeCaptureMode.SHORTCUTS));
	}

	@Test
	@DisplayName("treats Shift alone as plain typing")
	void treatsShiftAloneAsPlainTyping() {
		assertFalse(KeystrokeCapture.shouldRecordPress(KEY_A, Set.of(KeyModifier.SHIFT), KeystrokeCaptureMode.SHORTCUTS));
		assertFalse(KeystrokeCapture.shouldRecordPress(KEY_A, Set.of(), KeystrokeCaptureMode.SHORTCUTS));
	}

	@Test
	@DisplayName("records no plain typing in shortcuts mode")
	void recordsNoPlainTypingInShortcutsMode() {
		KeystrokeCapture.KeystrokeRecorder recorder =
				KeystrokeCapture.createKeystrokeRecorder(KeystrokeCaptureMode.SHORTCUTS);
		for (int code : new int[] {KEY_H, KEY_E, KEY_L, KEY_L, KEY_O}) {
			type(recorder, code, 1000);
		}
		assertEquals(List.of(), recorder.takeEvents());
	}

	@Test
	@DisplayName("records a Ctrl shortcut")
	void recordsACtrlShortcut() {
		KeystrokeCapture.KeystrokeRecorder recorder =
				KeystrokeCapture.createKeystrokeRecorder(KeystrokeCaptureMode.SHORTCUTS);
		recorder.handleLine("KEY:down:" + KEY_LEFT_CTRL, 1200);
		type(recorder, KEY_S, 1210);
		recorder.handleLine("KEY:up:" + KEY_LEFT_CTRL, 1300);
		assertEquals(
				List.of(new KeystrokeEvent(1210, "S", List.of(KeyModifier.CTRL))),
				recorder.takeEvents());
	}

	@Test
	@DisplayName("records multiple modifiers in order")
	void recordsMultipleModifiersInOrder() {
		KeystrokeCapture.KeystrokeRecorder recorder =
				KeystrokeCapture.createKeystrokeRecorder(KeystrokeCaptureMode.SHORTCUTS);
		recorder.handleLine("KEY:down:" + KEY_LEFT_SHIFT, 10);
		recorder.handleLine("KEY:down:" + KEY_RIGHT_CTRL, 20);
		type(recorder, KEY_S, 30);
		assertEquals(
				List.of(new KeystrokeEvent(30, "S", List.of(KeyModifier.CTRL, KeyModifier.SHIFT))),
				recorder.takeEvents());
	}

	@Test
	@DisplayName("records special keys without a modifier")
	void recordsSpecialKeysWithoutAModifier() {
		KeystrokeCapture.KeystrokeRecorder recorder =
				KeystrokeCapture.createKeystrokeRecorder(KeystrokeCaptureMode.SHORTCUTS);
		type(recorder, KEY_ENTER, 500);
		type(recorder, KEY_UP, 600);
		assertEquals(List.of("Enter", "↑"), recorder.takeEvents().stream().map(KeystrokeEvent::key).toList());
	}

	@Test
	@DisplayName("records letters in all mode")
	void recordsLettersInAllMode() {
		KeystrokeCapture.KeystrokeRecorder recorder =
				KeystrokeCapture.createKeystrokeRecorder(KeystrokeCaptureMode.ALL);
		type(recorder, KEY_A, 40);
		assertEquals(
				List.of(new KeystrokeEvent(40, "A", List.of())),
				recorder.takeEvents());
	}

	@Test
	@DisplayName("clears modifier state when the key is released")
	void clearsModifierStateWhenTheKeyIsReleased() {
		KeystrokeCapture.KeystrokeRecorder recorder =
				KeystrokeCapture.createKeystrokeRecorder(KeystrokeCaptureMode.SHORTCUTS);
		recorder.handleLine("KEY:down:" + KEY_LEFT_CTRL, 10);
		type(recorder, KEY_S, 20);
		recorder.handleLine("KEY:up:" + KEY_LEFT_CTRL, 30);
		type(recorder, KEY_S, 40);
		List<KeystrokeEvent> events = recorder.takeEvents();
		assertEquals(1, events.size());
		assertEquals(List.of(KeyModifier.CTRL), events.get(0).modifiers());
	}

	@Test
	@DisplayName("keeps modifier state correct while paused and stores nothing")
	void keepsModifierStateCorrectWhilePaused() {
		KeystrokeCapture.KeystrokeRecorder recorder =
				KeystrokeCapture.createKeystrokeRecorder(KeystrokeCaptureMode.SHORTCUTS);
		// Ctrl is held and released during the pause: nothing is stored, and the
		// modifier must not stick to the presses after resuming.
		recorder.handleLine("KEY:down:" + KEY_LEFT_CTRL, 100, false);
		type(recorder, KEY_S, 120, false);
		recorder.handleLine("KEY:up:" + KEY_LEFT_CTRL, 150, false);
		assertEquals(List.of(), recorder.takeEvents());
		recorder.handleLine("KEY:down:" + KEY_LEFT_CTRL, 200);
		type(recorder, KEY_S, 210);
		assertEquals(
				List.of(new KeystrokeEvent(210, "S", List.of(KeyModifier.CTRL))),
				recorder.takeEvents());
	}

	@Test
	@DisplayName("ignores lines that are not key events")
	void ignoresLinesThatAreNotKeyEvents() {
		KeystrokeCapture.KeystrokeRecorder recorder =
				KeystrokeCapture.createKeystrokeRecorder(KeystrokeCaptureMode.ALL);
		recorder.handleLine("POS:10:20", 0);
		recorder.handleLine("INTERACTION:mousedown:1", 0);
		recorder.handleLine("KEY:down:700", 0); // no label
		assertEquals(List.of(), recorder.takeEvents());
	}

	@Test
	@DisplayName("returns and clears the stored events")
	void returnsAndClearsTheStoredEvents() {
		KeystrokeCapture.KeystrokeRecorder recorder =
				KeystrokeCapture.createKeystrokeRecorder(KeystrokeCaptureMode.ALL);
		type(recorder, KEY_A, 10);
		assertEquals(1, recorder.takeEvents().size());
		assertEquals(List.of(), recorder.takeEvents());
	}
}
