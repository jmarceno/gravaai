package dev.gravaai.ui.overlays;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

import dev.gravaai.services.sources.CaptureArea;
import dev.gravaai.services.sources.CapturePickRequest;
import dev.gravaai.services.sources.CapturePickerGeometry;
import dev.gravaai.services.sources.CapturePickerGeometry.AreaRect;
import dev.gravaai.services.sources.CapturePickerWindow;
import dev.gravaai.services.sources.CapturePickerService;

/**
 * The per-display transparent capture picker (port of
 * {@code src/components/capture-picker/CapturePicker.tsx} and
 * {@code createCapturePickerWindows} in {@code electron/windows.ts}, plan §7.1 P1.6).
 *
 * <p>One transparent overlay per display; hovering highlights the window under the pointer, a click
 * picks that window or the whole screen, and (where the platform allows areas, i.e. macOS today)
 * a drag draws an area. Escape cancels and Enter confirms the current highlight. The finished pick
 * goes through {@link CapturePickerService#completeCapturePick}, which forces selection-only and
 * validates the geometry.
 *
 * <p>Platform note (plan §2.3, R3): the Java app targets COSMIC Wayland, where the capture picker
 * records a COSMIC output on click ({@code CapturePickerService} click path) and the Electron
 * overlay picker has no counterpart; this class is the faithful port for the platforms that use
 * overlay windows and is not opened by the COSMIC flow.
 */
public final class CapturePickerOverlay implements AutoCloseable {

	/** One display the picker covers. The id is the display id the source service uses. */
	public record DisplayOverlay(int displayId, Screen screen) {
	}

	private record Hit(CapturePickerWindow window, AreaRect rect, Region node)
			implements CapturePickerGeometry.RectEntry {
	}

	private static final double DRAG_THRESHOLD = 4;

	private final CapturePickerService picker;
	private final List<DisplayOverlay> displays;
	private final List<Stage> stages = new ArrayList<>();
	private boolean completed;

	public CapturePickerOverlay(CapturePickerService picker, List<DisplayOverlay> displays) {
		this.picker = picker;
		this.displays = List.copyOf(displays);
	}

	/** Opens one overlay per display; call on the JavaFX application thread. */
	public void open() {
		OverlayStage.requireFxThread();
		if (!stages.isEmpty()) {
			return;
		}
		if (displays.isEmpty()) {
			finish(null);
			return;
		}
		for (DisplayOverlay display : displays) {
			createStage(display);
		}
		if (!stages.isEmpty()) {
			Stage first = stages.get(0);
			first.toFront();
			first.requestFocus();
		}
	}

	@Override
	public void close() {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(this::close);
			return;
		}
		for (Stage stage : List.copyOf(stages)) {
			if (stage.isShowing()) {
				stage.close();
			}
		}
		stages.clear();
	}

	private void finish(CapturePickRequest request) {
		if (completed) {
			return;
		}
		completed = true;
		close();
		picker.completeCapturePick(request);
	}

	private void createStage(DisplayOverlay display) {
		CapturePickerService.CapturePickerContext context =
				picker.getCapturePickerContext(display.displayId());
		Rectangle2D screenBounds = display.screen().getBounds();
		CaptureArea displayBounds = context.displayBounds() != null
				? context.displayBounds()
				: new CaptureArea(
						screenBounds.getMinX(), screenBounds.getMinY(),
						screenBounds.getWidth(), screenBounds.getHeight());
		double offsetX = displayBounds.x();
		double offsetY = displayBounds.y();
		double width = Math.max(1, displayBounds.width());
		double height = Math.max(1, displayBounds.height());

		Pane dim = new Pane();
		dim.setStyle("-fx-background-color: rgba(0,0,0,0.38);");
		dim.setPrefSize(width, height);

		Region screenBorder = new Region();
		screenBorder.setStyle(
				"-fx-background-color: transparent;"
						+ "-fx-border-color: -g-accent;"
						+ "-fx-border-width: 2;");
		screenBorder.setPrefSize(width, height);

		Region areaPreview = new Region();
		areaPreview.setStyle(
				"-fx-background-color: rgba(47,179,163,0.13);"
						+ "-fx-border-color: -g-accent;"
						+ "-fx-border-width: 2;");
		areaPreview.setManaged(false);
		areaPreview.setVisible(false);

		List<Hit> hits = new ArrayList<>();
		Pane windowLayer = new Pane();
		windowLayer.setPrefSize(width, height);
		windowLayer.getChildren().add(areaPreview);
		for (CapturePickerWindow window : context.windows()) {
			AreaRect rect = new AreaRect(
					window.x() - offsetX, window.y() - offsetY, window.width(), window.height());
			Region highlight = new Region();
			highlight.setLayoutX(rect.x());
			highlight.setLayoutY(rect.y());
			highlight.setPrefSize(rect.width(), rect.height());
			highlight.setStyle(windowStyle(false));
			windowLayer.getChildren().add(highlight);
			hits.add(new Hit(window, rect, highlight));
		}

		Label hint = new Label(context.allowArea()
				? "Click a window or the desktop, or drag an area · Esc to cancel"
				: "Click a window or the desktop · Esc to cancel");
		hint.setStyle(
				"-fx-background-color: rgba(18,20,24,0.85);"
						+ "-fx-text-fill: -g-fg;"
						+ "-fx-font-size: 12px;"
						+ "-fx-padding: 6 12;"
						+ "-fx-background-radius: 12;");
		StackPane.setAlignment(hint, Pos.BOTTOM_CENTER);
		StackPane.setMargin(hint, new Insets(0, 0, 24, 0));

		StackPane root = new StackPane(dim, screenBorder, windowLayer, hint);
		root.setStyle("-fx-background-color: transparent;");
		root.setAlignment(Pos.TOP_LEFT);

		Scene scene = OverlayStage.scene(root, width, height);
		Stage stage = OverlayStage.create("gravaai-capture-picker");
		stage.setScene(scene);
		stage.setX(offsetX);
		stage.setY(offsetY);
		stage.setWidth(width);
		stage.setHeight(height);

		PickState state = new PickState(
				display.displayId(), hits, offsetX, offsetY, width, height, context.allowArea(),
				dim, screenBorder, areaPreview);
		root.setOnMouseMoved(event -> state.pointerMoved(event.getX(), event.getY()));
		root.setOnMousePressed(event -> {
			if (event.getButton() != MouseButton.PRIMARY) {
				return;
			}
			state.pressed(event.getX(), event.getY());
		});
		root.setOnMouseDragged(event -> {
			if (event.getButton() == MouseButton.PRIMARY) {
				state.pointerMoved(event.getX(), event.getY());
			}
		});
		root.setOnMouseReleased(event -> {
			if (event.getButton() != MouseButton.PRIMARY) {
				return;
			}
			CapturePickRequest request = state.released(event.getX(), event.getY());
			if (request != null) {
				finish(request);
			}
		});
		scene.setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.ESCAPE) {
				event.consume();
				finish(null);
			} else if (event.getCode() == KeyCode.ENTER) {
				event.consume();
				CapturePickRequest request = state.currentPick();
				if (request != null) {
					finish(request);
				}
			}
		});
		stages.add(stage);
		stage.show();
	}

	private static String windowStyle(boolean hovered) {
		return hovered
				? "-fx-background-color: rgba(47,179,163,0.13);"
						+ "-fx-border-color: -g-accent;"
						+ "-fx-border-width: 2;"
				: "-fx-background-color: transparent;"
						+ "-fx-border-color: rgba(255,255,255,0.25);"
						+ "-fx-border-width: 1;";
	}

	/** Mutable per-display interaction state; mirrors the refs in CapturePicker.tsx. */
	private static final class PickState {

		private final int displayId;
		private final List<Hit> hits;
		private final double offsetX;
		private final double offsetY;
		private final double width;
		private final double height;
		private final boolean allowArea;
		private final Pane dim;
		private final Region screenBorder;
		private final Region areaPreview;

		private CapturePickerWindow hoveredWindow;
		private boolean screenTarget;
		private double pressX;
		private double pressY;
		private boolean pressed;
		private boolean drawing;
		private AreaRect area;

		PickState(
				int displayId,
				List<Hit> hits,
				double offsetX,
				double offsetY,
				double width,
				double height,
				boolean allowArea,
				Pane dim,
				Region screenBorder,
				Region areaPreview) {
			this.displayId = displayId;
			this.hits = hits;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
			this.width = width;
			this.height = height;
			this.allowArea = allowArea;
			this.dim = dim;
			this.screenBorder = screenBorder;
			this.areaPreview = areaPreview;
		}

		void pressed(double x, double y) {
			pressed = true;
			drawing = false;
			pressX = x;
			pressY = y;
			area = null;
		}

		void pointerMoved(double x, double y) {
			if (pressed && allowArea
					&& (drawing || Math.hypot(x - pressX, y - pressY) >= DRAG_THRESHOLD)) {
				drawing = true;
				area = CapturePickerGeometry.normalizeAreaRect(
						new AreaRect(pressX, pressY, x - pressX, y - pressY),
						new CapturePickerGeometry.Bounds(width, height));
				showArea(area);
				return;
			}
			if (!pressed) {
				hoverAt(x, y);
			}
		}

		CapturePickRequest released(double x, double y) {
			boolean wasDrawing = drawing;
			AreaRect drawnArea = area;
			pressed = false;
			drawing = false;
			area = null;
			if (wasDrawing && drawnArea != null
					&& drawnArea.width() >= CapturePickerGeometry.MIN_AREA_SIZE
					&& drawnArea.height() >= CapturePickerGeometry.MIN_AREA_SIZE) {
				return new CapturePickRequest(
						"area",
						offsetX + drawnArea.x(),
						offsetY + drawnArea.y(),
						drawnArea.width(),
						drawnArea.height(),
						null,
						displayId,
						false);
			}
			hoverAt(x, y);
			return currentPick();
		}

		CapturePickRequest currentPick() {
			if (hoveredWindow != null) {
				return new CapturePickRequest(
						"window", null, null, null, null, hoveredWindow.id(), displayId, false);
			}
			if (screenTarget) {
				return new CapturePickRequest("screen", null, null, null, null, null, displayId, false);
			}
			return null;
		}

		private void hoverAt(double x, double y) {
			Hit hit = CapturePickerGeometry.frontmostAt(hits, x, y);
			hoveredWindow = hit == null ? null : hit.window();
			screenTarget = hit == null;
			for (Hit candidate : hits) {
				candidate.node().setStyle(windowStyle(candidate == hit));
			}
			screenBorder.setVisible(hit == null);
			dim.setVisible(hit == null);
			areaPreview.setVisible(false);
		}

		private void showArea(AreaRect rect) {
			hoveredWindow = null;
			screenTarget = false;
			for (Hit candidate : hits) {
				candidate.node().setStyle(windowStyle(false));
			}
			screenBorder.setVisible(false);
			dim.setVisible(true);
			areaPreview.setManaged(true);
			areaPreview.setVisible(true);
			areaPreview.setLayoutX(rect.x());
			areaPreview.setLayoutY(rect.y());
			areaPreview.setPrefSize(rect.width(), rect.height());
		}
	}
}
