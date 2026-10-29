package dev.gravaai.ui.overlays;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.services.sources.SourceService;
import dev.gravaai.ui.kit.Button;

/**
 * The source-selector window (port of {@code src/components/launch/SourceSelector.tsx} and
 * {@code createSourceSelectorWindow} in {@code electron/windows.ts}, plan §7.1 P1.6).
 *
 * <p>620x420 frameless transparent window, rounded {@code 2xl} surface with a separator border:
 * header ("Choose recording source" + Cancel), scrollable Screens/Windows groups, error banner,
 * footer Refresh. Escape and recording start both close it. On Linux this is the source list; on
 * COSMIC the only entry is the portal/system picker because the portal screencast is unreliable and
 * the capture picker records a COSMIC output directly.
 *
 * <p>Wiring contract for {@code AppContext}:
 * <pre>{@code
 * SourceSelectorOverlay selector = new SourceSelectorOverlay(sourceService, recordingService::isRecordingActive);
 * // SourceService takes the ui: new SourceService(..., selector)
 * // wire recording-state-changed -> selector.recordingStateChanged(recording)
 * }</pre>
 */
public final class SourceSelectorOverlay implements SourceService.SourceSelectorUi {

	/** {@code createSourceSelectorWindow}: 620x420, min 350, max 500 (windows.ts). */
	public static final double WINDOW_WIDTH = 620;
	public static final double WINDOW_HEIGHT = 420;
	public static final double WINDOW_MIN_HEIGHT = 350;
	public static final double WINDOW_MAX_HEIGHT = 500;

	private static final String TITLE = "Choose recording source";
	private static final String SUBTITLE =
			"Choose a screen or window. Recording starts from the recording controls.";
	private static final String COULD_NOT_LOAD =
			"Could not load recording sources. Try refreshing the list.";

	private final SourceService sourceService;
	private final BooleanSupplier recordingActive;

	private @Nullable Stage stage;
	private @Nullable VBox list;
	private @Nullable Label statusLabel;
	private @Nullable Label errorLabel;
	private @Nullable Button refreshButton;
	private final ScrollPane scroll = new ScrollPane();
	private boolean selecting;

	public SourceSelectorOverlay(SourceService sourceService, BooleanSupplier recordingActive) {
		this.sourceService = sourceService;
		this.recordingActive = recordingActive;
	}

	@Override
	public void open() {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(this::open);
			return;
		}
		if (stage != null && stage.isShowing()) {
			focus();
			return;
		}
		buildWindow();
		Stage window = Objects.requireNonNull(stage, "source selector window not built");
		window.show();
		window.requestFocus();
		loadSources();
	}

	@Override
	public void focus() {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(this::focus);
			return;
		}
		if (stage != null && stage.isShowing()) {
			stage.toFront();
			stage.requestFocus();
		}
	}

	@Override
	public void close() {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(this::close);
			return;
		}
		if (stage != null) {
			stage.close();
		}
	}

	/** The renderer closes the selector when a recording starts. */
	public void recordingStateChanged(boolean recording) {
		if (recording && isOpen()) {
			close();
		}
	}

	public boolean isOpen() {
		return stage != null && stage.isShowing();
	}

	private void buildWindow() {
		VBox root = new VBox();
		root.setStyle(
				"-fx-background-color: -g-surface;"
						+ "-fx-background-radius: 16;"
						+ "-fx-border-color: -g-separator;"
						+ "-fx-border-width: 1;"
						+ "-fx-border-radius: 16;");

		Label title = new Label(TITLE);
		title.setStyle("-fx-text-fill: -g-fg; -fx-font-size: 18px; -fx-font-weight: 600;");
		Label subtitle = new Label(SUBTITLE);
		subtitle.setStyle("-fx-text-fill: -g-muted; -fx-font-size: 12px;");
		subtitle.setWrapText(true);
		VBox heading = new VBox(4, title, subtitle);
		Button cancel = new Button("Cancel", Button.Variant.GHOST);
		cancel.setOnAction(event -> close());
		Region headerSpacer = new Region();
		HBox.setHgrow(headerSpacer, Priority.ALWAYS);
		HBox header = new HBox(8, heading, headerSpacer, cancel);
		header.setAlignment(Pos.CENTER_LEFT);
		header.setPadding(new Insets(16));
		header.setStyle("-fx-border-color: transparent transparent -g-separator transparent; -fx-border-width: 0 0 1 0;");

		errorLabel = new Label();
		errorLabel.setWrapText(true);
		errorLabel.setStyle(
				"-fx-background-color: rgba(229,101,110,0.10);"
						+ "-fx-text-fill: -g-danger;"
						+ "-fx-padding: 12;"
						+ "-fx-background-radius: 8;");
		errorLabel.setManaged(false);
		errorLabel.setVisible(false);

		list = new VBox(4);
		list.setPadding(new Insets(4));
		statusLabel = new Label();
		statusLabel.setStyle("-fx-text-fill: -g-muted; -fx-font-size: 14px;");
		statusLabel.setMaxWidth(Double.MAX_VALUE);
		statusLabel.setAlignment(Pos.CENTER);
		statusLabel.setPadding(new Insets(24));
		VBox bodyBox = new VBox(8, errorLabel, statusLabel, list);
		bodyBox.setPadding(new Insets(12));
		scroll.setContent(bodyBox);
		scroll.setFitToWidth(true);
		scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		scroll.setMinHeight(0);

		refreshButton = new Button("Refresh", Button.Variant.GHOST);
		refreshButton.setOnAction(event -> loadSources());
		Region footerSpacer = new Region();
		HBox.setHgrow(footerSpacer, Priority.ALWAYS);
		HBox footer = new HBox(8, footerSpacer, refreshButton);
		footer.setAlignment(Pos.CENTER_RIGHT);
		footer.setPadding(new Insets(12));
		footer.setStyle("-fx-border-color: -g-separator transparent transparent transparent; -fx-border-width: 1 0 0 0;");

		root.getChildren().addAll(header, scroll, footer);

		stage = OverlayStage.create("Gravaai source selector");
		stage.setMinHeight(WINDOW_MIN_HEIGHT);
		stage.setMaxHeight(WINDOW_MAX_HEIGHT);
		Scene scene = OverlayStage.scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
		scene.setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.ESCAPE) {
				event.consume();
				close();
			}
		});
		stage.setScene(scene);
		OverlayStage.centerOnPrimary(stage, WINDOW_WIDTH, WINDOW_HEIGHT);
		stage.setOnHidden(event -> stage = null);
	}

	private void loadSources() {
		showError(null);
		setStatus("Loading sources…", true);
		List<SelectedSource> sources;
		try {
			sources = sourceService.getDesktopSources(List.of("screen", "window"));
		} catch (RuntimeException error) {
			setStatus(null, false);
			showError(COULD_NOT_LOAD);
			return;
		}
		rebuildList(sources);
	}

	private void rebuildList(List<SelectedSource> sources) {
		VBox sourceList = Objects.requireNonNull(list, "source selector list not built");
		sourceList.getChildren().clear();
		if (sources.isEmpty()) {
			setStatus("No recording sources found.", false);
			return;
		}
		setStatus(null, false);
		for (String kind : List.of("screen", "window")) {
			List<SelectedSource> entries = sources.stream()
					.filter(source -> source.id() != null && source.id().startsWith(kind + ":"))
					.toList();
			if (entries.isEmpty()) {
				continue;
			}
			Label heading = new Label("screen".equals(kind) ? "Screens" : "Windows");
			heading.setStyle("-fx-text-fill: -g-muted; -fx-font-size: 12px; -fx-font-weight: 600;");
			heading.setPadding(new Insets(8));
			sourceList.getChildren().add(heading);
			for (SelectedSource source : entries) {
				Button row = new Button(source.name(), Button.Variant.GHOST);
				row.setMaxWidth(Double.MAX_VALUE);
				row.setAlignment(Pos.CENTER_LEFT);
				row.setStyle("-fx-padding: 8 12; -fx-alignment: center-left;");
				row.setOnAction(event -> select(source));
				sourceList.getChildren().add(row);
			}
		}
	}

	private void setStatus(@Nullable String text, boolean visible) {
		Label label = statusLabel;
		if (label == null) {
			return;
		}
		label.setText(text == null ? "" : text);
		label.setManaged(visible);
		label.setVisible(visible);
	}

	private void showError(@Nullable String message) {
		Label label = errorLabel;
		if (label == null) {
			return;
		}
		boolean visible = message != null && !message.isBlank();
		label.setText(visible ? message : "");
		label.setManaged(visible);
		label.setVisible(visible);
	}

	/**
	 * {@code select}: posts the selection to the service and reports the failure cases the React
	 * component showed; success closes the window, exactly like the select-source handler.
	 */
	private void select(SelectedSource source) {
		if (selecting) {
			return;
		}
		selecting = true;
		setRowsDisabled(true);
		showError(null);
		Thread.ofVirtual().start(() -> {
			String error = null;
			try {
				SelectedSource selected = sourceService.selectSource(source);
				if (selected == null || !Objects.equals(selected.id(), source.id())) {
					error = "Source selection is unavailable during recording.";
				}
			} catch (IOException failure) {
				error = failure.getMessage() != null ? failure.getMessage() : "Could not select that source.";
			} catch (RuntimeException failure) {
				error = "Could not select that source.";
			}
			String message = error;
			Platform.runLater(() -> {
				selecting = false;
				setRowsDisabled(false);
				if (message != null) {
					showError(message);
				} else {
					close();
				}
			});
		});
	}

	private void setRowsDisabled(boolean disabled) {
		VBox sourceList = list;
		if (sourceList != null) {
			for (javafx.scene.Node node : sourceList.getChildren()) {
				node.setDisable(disabled);
			}
		}
		Button refresh = refreshButton;
		if (refresh != null) {
			refresh.setDisable(disabled);
		}
	}
}
