package dev.gravaai.ui.launch;

import dev.gravaai.services.shortcuts.GlobalShortcutAction;

/**
 * The global-shortcut action logic of {@code LaunchWindow.tsx} (the {@code onGlobalShortcutAction}
 * switch), kept pure so it is unit-tested without a window (plan §6.1, §7.1 P1.7).
 *
 * <p>{@link HudOverlayWindow#deliver} routes the {@code gravaai-ctl}/COSMIC actions through here
 * with the live HUD state; the actions themselves are the recording port.
 */
public final class HudShortcuts {

	/** What the dispatcher can do to the recorder/HUD for one shortcut. */
	public interface Actions {

		void toggleRecording();

		void pauseRecording();

		void resumeRecording();

		void cancelRecording();

		void requestSourcePick();

		void toggleMeetingMode();
	}

	private HudShortcuts() {}

	/** Port of the action switch: same decisions in the same order as the React effect. */
	public static void dispatch(GlobalShortcutAction action, HudRecordingPort.State state, Actions actions) {
		if (action == null || state == null) {
			return;
		}
		switch (action) {
			case TOGGLE_RECORDING -> {
				if (state.finalizing()) {
					return;
				}
				if (state.recording()) {
					actions.toggleRecording();
				} else if (state.countdownActive()) {
					actions.cancelRecording();
				} else if (state.audioOnly() || state.hasSource()) {
					actions.toggleRecording();
				} else {
					actions.requestSourcePick();
				}
			}
			case TOGGLE_PAUSE -> {
				if (state.recording()) {
					if (state.paused()) {
						actions.resumeRecording();
					} else {
						actions.pauseRecording();
					}
				}
			}
			case CANCEL_RECORDING -> {
				if (state.recording()) {
					actions.cancelRecording();
				} else if (state.countdownActive()) {
					actions.cancelRecording();
				}
			}
			case TOGGLE_MEETING -> actions.toggleMeetingMode();
		}
	}
}
