package dev.gravaai.ui.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gravaai.services.shortcuts.GlobalShortcutAction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The global-shortcut decision table from LaunchWindow.tsx (plan §7.1 P1.7). */
class HudShortcutsTest {

	private final List<String> calls = new ArrayList<>();
	private final HudShortcuts.Actions actions = new HudShortcuts.Actions() {
		@Override
		public void toggleRecording() {
			calls.add("toggle");
		}

		@Override
		public void pauseRecording() {
			calls.add("pause");
		}

		@Override
		public void resumeRecording() {
			calls.add("resume");
		}

		@Override
		public void cancelRecording() {
			calls.add("cancel");
		}

		@Override
		public void requestSourcePick() {
			calls.add("pick");
		}

		@Override
		public void toggleMeetingMode() {
			calls.add("meeting");
		}
	};

	@DisplayName("toggle-recording records, cancels a countdown, or asks for a source")
	@Test
	void toggleRecording() {
		dispatch(GlobalShortcutAction.TOGGLE_RECORDING, state(HudRecordingPort.Phase.IDLE, null, false, false));
		assertEquals(List.of("pick"), calls);
		calls.clear();
		dispatch(GlobalShortcutAction.TOGGLE_RECORDING,
				state(HudRecordingPort.Phase.IDLE, "Built-in Display", false, false));
		assertEquals(List.of("toggle"), calls);
		calls.clear();
		dispatch(GlobalShortcutAction.TOGGLE_RECORDING, state(HudRecordingPort.Phase.IDLE, null, true, false));
		assertEquals(List.of("cancel"), calls);
		calls.clear();
		dispatch(GlobalShortcutAction.TOGGLE_RECORDING,
				state(HudRecordingPort.Phase.RECORDING, "Built-in Display", false, false));
		assertEquals(List.of("toggle"), calls);
	}

	@DisplayName("toggle-recording ignores the shortcut while finalizing")
	@Test
	void finalizingIgnored() {
		dispatch(GlobalShortcutAction.TOGGLE_RECORDING,
				state(HudRecordingPort.Phase.FINALIZING, "Built-in Display", false, false));
		assertEquals(List.of(), calls);
	}

	@DisplayName("toggle-pause pauses a running recording and resumes a paused one")
	@Test
	void togglePause() {
		dispatch(GlobalShortcutAction.TOGGLE_PAUSE,
				state(HudRecordingPort.Phase.RECORDING, "x", false, false));
		assertEquals(List.of("pause"), calls);
		calls.clear();
		dispatch(GlobalShortcutAction.TOGGLE_PAUSE, state(HudRecordingPort.Phase.PAUSED, "x", false, false));
		assertEquals(List.of("resume"), calls);
		calls.clear();
		dispatch(GlobalShortcutAction.TOGGLE_PAUSE, state(HudRecordingPort.Phase.IDLE, "x", false, false));
		assertEquals(List.of(), calls);
	}

	@DisplayName("cancel-recording cancels either a recording or a countdown")
	@Test
	void cancel() {
		dispatch(GlobalShortcutAction.CANCEL_RECORDING,
				state(HudRecordingPort.Phase.RECORDING, "x", false, false));
		assertEquals(List.of("cancel"), calls);
		calls.clear();
		dispatch(GlobalShortcutAction.CANCEL_RECORDING, state(HudRecordingPort.Phase.IDLE, null, true, false));
		assertEquals(List.of("cancel"), calls);
		calls.clear();
		dispatch(GlobalShortcutAction.CANCEL_RECORDING, state(HudRecordingPort.Phase.IDLE, null, false, false));
		assertEquals(List.of(), calls);
	}

	@DisplayName("toggle-meeting flips meeting mode; a meeting without the screen records audio only")
	@Test
	void meeting() {
		dispatch(GlobalShortcutAction.TOGGLE_MEETING, state(HudRecordingPort.Phase.IDLE, null, false, true));
		assertEquals(List.of("meeting"), calls);
		calls.clear();
		HudRecordingPort.State audioOnly = new HudRecordingPort.State(HudRecordingPort.Phase.IDLE, false, null,
				false, false, false, null, null, 3, true, false, false, false);
		dispatch(GlobalShortcutAction.TOGGLE_RECORDING, audioOnly);
		assertEquals(List.of("toggle"), calls);
	}

	private void dispatch(GlobalShortcutAction action, HudRecordingPort.State state) {
		HudShortcuts.dispatch(action, state, actions);
	}

	private static HudRecordingPort.State state(HudRecordingPort.Phase phase, String source,
			boolean countdownActive, boolean meeting) {
		return new HudRecordingPort.State(phase, countdownActive, source, false, false, false, null, null, 3,
				meeting, true, false, false);
	}
}
