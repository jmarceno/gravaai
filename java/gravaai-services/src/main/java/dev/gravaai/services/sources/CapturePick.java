package dev.gravaai.services.sources;

/**
 * What the capture picker reports when the user confirms, from {@code CapturePick} in
 * {@code electron/ipc/types.ts}: an area they drew, a window they clicked, or a whole screen.
 * {@code record} starts recording right away.
 */
public sealed interface CapturePick
		permits CapturePick.Area, CapturePick.Window, CapturePick.Screen {

	int displayId();

	boolean record();

	/** An area the user drew on a display. */
	record Area(double x, double y, double width, double height, int displayId, boolean record)
			implements CapturePick {
	}

	/** A window the user clicked. */
	record Window(String windowId, int displayId, boolean record) implements CapturePick {
	}

	/** A whole screen the user clicked. */
	record Screen(int displayId, boolean record) implements CapturePick {
	}
}
