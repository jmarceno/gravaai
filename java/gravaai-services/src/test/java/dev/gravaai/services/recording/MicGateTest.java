package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;
import dev.gravaai.services.recording.PushToTalkTelemetry.MutedSpan;
import dev.gravaai.services.recording.PushToTalkTelemetry.Recording;
import dev.gravaai.services.shortcuts.GlobalShortcutBinding;

/** The push-to-talk mic gate: hold semantics on the helper's key lines, and the toggle fallback. */
class MicGateTest {

	/** The default binding: Super+Alt+V — evdev key code 47, Super 125/126, Alt 56/100. */
	private static final int KEY_V = 47;
	private static final int KEY_D = 32;
	private static final int SUPER = 125;
	private static final int ALT_LEFT = 56;
	private static final int CTRL_LEFT = 29;
	private static final int SHIFT_LEFT = 42;

	private final AtomicLong clock = new AtomicLong();
	private final MicGate gate = new MicGate(clock::get);

	private static PushToTalkKeys keys(String key) {
		return PushToTalkKeys.fromBinding(new GlobalShortcutBinding(key, true, false, true, false))
				.orElseThrow();
	}

	/** Holds Super, Alt and the key, then releases them; the mic is muted again afterwards. */
	private void pressCombo(int keyCode) {
		holdDown(keyCode);
		releaseCombo();
	}

	/** Holds Super, Alt and the key down, leaving the combo held and the mic live. */
	private void holdDown(int keyCode) {
		gate.onKeyLine("KEY:down:" + SUPER);
		gate.onKeyLine("KEY:down:" + ALT_LEFT);
		gate.onKeyLine("KEY:down:" + keyCode);
	}

	/** Releases the held combo. */
	private void releaseCombo() {
		gate.onKeyLine("KEY:up:" + KEY_V);
		gate.onKeyLine("KEY:up:" + ALT_LEFT);
		gate.onKeyLine("KEY:up:" + SUPER);
	}

	@Test
	@DisplayName("holding the shortcut unmutes and releasing mutes again")
	void holdUnmutes() {
		gate.configure(keys("v"));
		assertTrue(gate.isActive());
		assertTrue(gate.isMuted(), "the mic starts muted");
		gate.beginRecording();

		clock.set(100);
		gate.onKeyLine("KEY:down:" + SUPER);
		gate.onKeyLine("KEY:down:" + ALT_LEFT);
		gate.onKeyLine("KEY:down:" + KEY_V);
		assertFalse(gate.isMuted());

		clock.set(900);
		gate.onKeyLine("KEY:up:" + KEY_V);
		assertTrue(gate.isMuted());

		// [0..100] was muted; the talk window 100..900 is not; the span reopened at 900 closes
		// there and is dropped as zero-length.
		assertEquals(List.of(new MutedSpan(0, 100)), gate.take().spans());
	}

	@Test
	@DisplayName("the open muted span closes when the recording stops")
	void takeClosesTheOpenSpan() {
		gate.configure(keys("v"));
		gate.beginRecording();
		clock.set(5000);
		Recording spans = gate.take();
		assertEquals(List.of(new MutedSpan(0, 5000)), spans.spans());
		assertFalse(gate.isActive(), "the gate switches off with the take");
		assertTrue(gate.take().isEmpty(), "the spans do not survive a second take");
	}

	@Test
	@DisplayName("releasing a modifier of the combo mutes even with the key still held")
	void modifierReleaseMutes() {
		gate.configure(keys("v"));
		gate.beginRecording();
		gate.onKeyLine("KEY:down:" + SUPER);
		gate.onKeyLine("KEY:down:" + ALT_LEFT);
		gate.onKeyLine("KEY:down:" + KEY_V);
		assertFalse(gate.isMuted());

		gate.onKeyLine("KEY:up:" + ALT_LEFT);
		assertTrue(gate.isMuted());
		// Re-pressing the modifier alone does not unmute: the combo is no longer held.
		gate.onKeyLine("KEY:down:" + ALT_LEFT);
		assertTrue(gate.isMuted());
	}

	@Test
	@DisplayName("a different key or combo does not ungate the mic")
	void otherKeysAreIgnored() {
		gate.configure(keys("v"));
		gate.beginRecording();
		// Alt+D is not the binding.
		gate.onKeyLine("KEY:down:" + ALT_LEFT);
		gate.onKeyLine("KEY:down:" + KEY_D);
		assertTrue(gate.isMuted());
		gate.onKeyLine("KEY:up:" + KEY_D);
		gate.onKeyLine("KEY:up:" + ALT_LEFT);
		// Super+Alt+Ctrl+V carries an extra modifier, so it does not match either.
		gate.onKeyLine("KEY:down:" + SUPER);
		gate.onKeyLine("KEY:down:" + ALT_LEFT);
		gate.onKeyLine("KEY:down:" + CTRL_LEFT);
		gate.onKeyLine("KEY:down:" + KEY_V);
		assertTrue(gate.isMuted());
		gate.onKeyLine("KEY:up:" + CTRL_LEFT);
		gate.onKeyLine("KEY:up:" + KEY_V);
		gate.onKeyLine("KEY:up:" + ALT_LEFT);
		gate.onKeyLine("KEY:up:" + SUPER);
		// The exact combo still works afterwards.
		pressCombo(KEY_V);
		assertTrue(gate.isMuted(), "pressCombo releases the key, so the mic is muted again");
	}

	@Test
	@DisplayName("without key telemetry a shortcut press toggles, until evdev proves it owns the key")
	void shortcutPressTogglesUntilEvdevAnswers() {
		gate.configure(keys("v"));
		gate.beginRecording();
		gate.onShortcutPress();
		assertFalse(gate.isMuted());
		gate.onShortcutPress();
		assertTrue(gate.isMuted());

		// One evdev hold later, presses are ignored: the release events own the state.
		holdDown(KEY_V);
		assertFalse(gate.isMuted());
		gate.onShortcutPress();
		assertFalse(gate.isMuted());
		releaseCombo();
		assertTrue(gate.isMuted());

		clock.set(300);
		Recording spans = gate.take();
		assertEquals(List.of(new MutedSpan(0, 300)), spans.spans());
	}

	@Test
	@DisplayName("the HUD's flip still works after evdev took over the shortcut")
	void hudFlipWorksAfterEvdev() {
		gate.configure(keys("v"));
		gate.beginRecording();
		pressCombo(KEY_V);
		assertTrue(gate.isMuted());

		clock.set(200);
		gate.flip();
		assertFalse(gate.isMuted());
		clock.set(500);
		gate.flip();
		assertTrue(gate.isMuted());

		// A later hold and release of the shortcut takes over again.
		holdDown(KEY_V);
		assertFalse(gate.isMuted());
		releaseCombo();
		assertTrue(gate.isMuted());

		clock.set(900);
		assertEquals(List.of(new MutedSpan(0, 200), new MutedSpan(500, 900)), gate.take().spans());
	}

	@Test
	@DisplayName("a pause does not stick the mute state and frozen spans collapse")
	void pauseAware() {
		gate.configure(keys("v"));
		gate.beginRecording();
		clock.set(100);
		holdDown(KEY_V);
		assertFalse(gate.isMuted());
		// The recording pauses with the clock frozen at 100; the key is released and pressed
		// again while paused. The mute state follows the key; the re-opened span collapses.
		releaseCombo();
		assertTrue(gate.isMuted());
		holdDown(KEY_V);
		assertFalse(gate.isMuted());
		clock.set(4000);
		Recording spans = gate.take();
		assertEquals(List.of(new MutedSpan(0, 100)), spans.spans());
	}

	@Test
	@DisplayName("an unconfigured gate ignores everything")
	void unconfiguredGateIgnoresEverything() {
		gate.beginRecording();
		pressCombo(KEY_V);
		gate.onShortcutPress();
		assertFalse(gate.isActive());
		assertTrue(gate.take().isEmpty());
	}

	@Test
	@DisplayName("configure replaces the previous recording's state and key")
	void reconfigureResets() {
		gate.configure(keys("v"));
		gate.beginRecording();
		clock.set(200);
		gate.take();

		gate.configure(keys("d"));
		gate.beginRecording();
		assertTrue(gate.isMuted(), "a new recording starts muted regardless of the last one");
		// The new key gates; the old one does not.
		holdDown(KEY_D);
		assertFalse(gate.isMuted());
		releaseCombo();
		assertTrue(gate.isMuted());
		holdDown(KEY_V);
		assertTrue(gate.isMuted(), "the previous recording's key is no longer the binding");
	}

	@Test
	@DisplayName("mute flips reach the listeners")
	void listenersSeeFlips() {
		List<Boolean> flips = new ArrayList<>();
		gate.onMutedChanged(flips::add);
		gate.configure(keys("v"));
		gate.beginRecording();
		assertEquals(List.of(), flips, "starting muted is not a flip");
		pressCombo(KEY_V);
		assertEquals(List.of(Boolean.FALSE, Boolean.TRUE), flips,
				"the hold made the mic live, the release muted it again");
	}

	@Test
	@DisplayName("the binding maps to the evdev codes the helper reports")
	void bindingToKeys() {
		PushToTalkKeys v = keys("v");
		assertEquals(KEY_V, v.keyCode());
		assertEquals(java.util.Set.of(KeyModifier.SUPER, KeyModifier.ALT), v.required());
		// Shift is a binding component too, and unbindable keys are rejected.
		assertTrue(PushToTalkKeys.fromBinding(new GlobalShortcutBinding("F12", true, false, false, false))
				.isPresent());
		assertTrue(PushToTalkKeys.fromBinding(new GlobalShortcutBinding("semicolon", true, false, false, false))
				.isEmpty());
		assertTrue(PushToTalkKeys.fromBinding(new GlobalShortcutBinding("v", false, false, false, false))
				.isEmpty());
	}
}
