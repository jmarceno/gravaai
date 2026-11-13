package dev.gravaai.app.recording;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

import dev.gravaai.platform.compositor.DesktopSession;
import dev.gravaai.services.recording.cursor.CursorCaptureSession;
import dev.gravaai.services.recording.cursor.FrameBounds;
import dev.gravaai.services.sources.CaptureArea;
import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.ui.launch.HudRecordingPort.DrawState;
import dev.gravaai.ui.overlays.ink.InkOverlay;
import dev.gravaai.ui.overlays.ink.InkStacking;

/**
 * Draw mode during a recording: opens and closes the live ink overlay over the recorded monitor
 * and keeps the HUD's pen button in step.
 *
 * <p>Draw mode is offered only while a screen or an area records. A window recording has no
 * global position on Wayland, so neither the overlay nor the sidecar could be placed on it (and the
 * overlay is not part of the window's own buffer, so the ink would not be recorded anyway).
 *
 * <p>FX thread for every method except {@link #onDrawStateChanged}.
 */
final class DrawModeController {

	private static final System.Logger LOG = System.getLogger("gravaai.ink");

	private final CursorCaptureSession cursorSession;
	private final BooleanSupplier recordingActive;
	private final Supplier<@Nullable SelectedSource> selectedSource;
	private final InkStacking stacking;
	private final List<Consumer<DrawState>> listeners = new CopyOnWriteArrayList<>();

	private Optional<InkOverlay> overlay = Optional.empty();

	DrawModeController(CursorCaptureSession cursorSession, BooleanSupplier recordingActive,
			Supplier<@Nullable SelectedSource> selectedSource, DesktopSession desktop) {
		this.cursorSession = cursorSession;
		this.recordingActive = recordingActive;
		this.selectedSource = selectedSource;
		this.stacking = InkStacking.forSession(desktop);
	}

	DrawState state() {
		boolean drawing = overlay.map(InkOverlay::isActive).orElse(false);
		return new DrawState(drawing || frame().isPresent(), drawing);
	}

	AutoCloseable onDrawStateChanged(Consumer<DrawState> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Enters or leaves draw mode. */
	void toggle() {
		if (overlay.map(InkOverlay::isActive).orElse(false)) {
			close();
			return;
		}
		Optional<FrameBounds> frame = frame();
		if (frame.isEmpty()) {
			LOG.log(System.Logger.Level.INFO, "draw mode unavailable: no screen or area is recording");
			return;
		}
		Optional<Rectangle2D> monitor = monitorOf(frame.get());
		if (monitor.isEmpty()) {
			LOG.log(System.Logger.Level.WARNING, "draw mode: no monitor contains the recorded frame");
			return;
		}
		SelectedSource source = selectedSource.get();
		CaptureArea area = source == null ? null : source.area();
		Optional<Rectangle2D> areaRect = area == null
				? Optional.empty()
				: Optional.of(new Rectangle2D(frame.get().x(), frame.get().y(),
						frame.get().width(), frame.get().height()));
		overlay = Optional.of(InkOverlay.open(cursorSession.ink(), stacking, monitor.get(), areaRect,
				this::onOverlayClosed));
		LOG.log(System.Logger.Level.INFO, "draw mode on");
		publish();
	}

	/** Leaves draw mode if it is on (recording stopped, cancelled or the app closes). */
	void close() {
		overlay.ifPresent(InkOverlay::close);
		publish();
	}

	/** A recording started or stopped: the pen's availability may have changed. */
	void recordingChanged() {
		if (!recordingActive.getAsBoolean()) {
			close();
		}
		publish();
	}

	private void onOverlayClosed() {
		overlay = Optional.empty();
		LOG.log(System.Logger.Level.INFO, "draw mode off");
		publish();
	}

	private Optional<FrameBounds> frame() {
		return recordingActive.getAsBoolean() ? cursorSession.recordedFrameBounds() : Optional.empty();
	}

	/** The monitor that holds the centre of the recorded frame, in JavaFX screen coordinates. */
	private static Optional<Rectangle2D> monitorOf(FrameBounds frame) {
		double centreX = frame.x() + frame.width() / 2;
		double centreY = frame.y() + frame.height() / 2;
		for (Screen screen : Screen.getScreens()) {
			if (screen.getBounds().contains(centreX, centreY)) {
				return Optional.of(screen.getBounds());
			}
		}
		return Optional.empty();
	}

	private void publish() {
		Runnable deliver = () -> {
			DrawState next = state();
			for (Consumer<DrawState> listener : listeners) {
				try {
					listener.accept(next);
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.WARNING, "draw state listener failed: " + error);
				}
			}
		};
		if (Platform.isFxApplicationThread()) {
			deliver.run();
		} else {
			Platform.runLater(deliver);
		}
	}
}
