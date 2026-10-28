package dev.gravaai.services.recording;

import java.util.List;

/**
 * Screen geometry and pointer position for cursor telemetry, mirroring the parts of Electron's
 * {@code screen} module that {@code electron/ipc/cursor/telemetry.ts} uses (plan §5.5: JavaFX
 * {@code Screen} for geometry). The UI layer implements it; {@code CursorCaptureSession} never
 * touches JavaFX.
 */
public interface CursorScreen {

	/** A display in global logical coordinates. */
	record Display(int id, double x, double y, double width, double height, double scaleFactor) {
	}

	/** A point in global logical coordinates. */
	record Point(double x, double y) {
	}

	Point cursorScreenPoint();

	List<Display> allDisplays();

	Display primaryDisplay();

	Display displayNearestPoint(double x, double y);
}
