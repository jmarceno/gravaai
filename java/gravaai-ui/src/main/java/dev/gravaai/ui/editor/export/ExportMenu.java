package dev.gravaai.ui.editor.export;

import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.media.export.ExportProgress;
import dev.gravaai.media.export.ExportStatusModel;
import dev.gravaai.ui.editor.export.ExportSettingsMenu.OutputDimensions;
import dev.gravaai.ui.kit.Anchors;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Popover;
import dev.gravaai.ui.kit.Toast;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The editor export popover (port of {@code EditorExportMenu.tsx}, plan §5.10/§5.12): a 360 px
 * popover anchored to the header Export button with four states — settings form, progress (with
 * cancel), error (Copy error / Save Again / Close) and complete (Show In Folder / Done). The
 * popover stays open through the whole flow; state changes come from {@link ExportSessionModel} and
 * {@link ExportSettingsState}.
 */
public final class ExportMenu {

	/** Actions the app-side export controller provides (port of {@code useExportDialogActions}). */
	public interface Callbacks {
		/** Starts the export with the settings currently shown in the form. */
		void startExport(ExportPreferences preferences);

		/** Cancels the running export. */
		void cancelExport();

		/** Saves the pending staged export again (Save Again). */
		void retrySaveExport();

		/** Reveals the exported file in the file manager. */
		void revealExportedFile();

		/**
		 * {@code discardExportedTemp}: deletes the staged file of a dropped pending save;
		 * {@code pending} is null when there was nothing to discard.
		 */
		void discardPendingSave(ExportSessionModel.@Nullable PendingExportSave pending);
	}

	private static final double WIDTH = 360;
	private static final double NULL_PROGRESS_FRACTION = 0.08;

	private final Popover popover;
	private final ExportSettingsState settings;
	private final ExportSessionModel session;
	private final Callbacks callbacks;
	private final Supplier<OutputDimensions> dimensions;
	private final BooleanSupplier hasCaptionsForSidecar;
	private final VBox content = new VBox();
	private final VBox stateHost = new VBox();
	private final VBox progressPane = new VBox();
	private final VBox errorPane = new VBox();
	private final VBox completePane = new VBox();
	private final ExportProgressViewModel viewModel = new ExportProgressViewModel();

	// progress pane nodes
	private final Label tipLabel = new Label();
	private final Pane progressTrack = new Pane();
	private final Region progressFill = new Region();
	private final Label percentLabel = new Label();
	private final Label speedLabel = new Label();
	private final Label pathLabel = new Label();
	private final Label skipLabel = new Label();
	private final PauseTransition tipTimer = new PauseTransition();

	// error pane nodes
	private final Label errorPathLabel = new Label();
	private final Label errorTextLabel = new Label();
	private final Button retrySaveButton;

	// complete pane nodes
	private final Label completePathLabel = new Label();
	private final Label completeFileLabel = new Label();

	private int tipIndex;
	private javafx.animation.@Nullable Transition indeterminate;

	public ExportMenu(Node trigger, ExportSettingsState settings, ExportSessionModel session,
			Callbacks callbacks, Supplier<OutputDimensions> dimensions,
			BooleanSupplier hasCaptionsForSidecar) {
		this.settings = settings;
		this.session = session;
		this.callbacks = callbacks;
		this.dimensions = dimensions;
		this.hasCaptionsForSidecar = hasCaptionsForSidecar == null ? () -> false
				: hasCaptionsForSidecar;
		popover = new Popover(trigger);
		popover.side(Anchors.Side.BOTTOM).align(Anchors.Align.END).offset(10);
		content.getStyleClass().add("export-menu");
		content.setPrefWidth(WIDTH);
		content.setMaxWidth(WIDTH);
		Label heading = new Label("Export");
		heading.getStyleClass().add("export-menu__heading");
		VBox header = new VBox(heading);
		header.getStyleClass().add("export-menu__header");
		stateHost.getStyleClass().add("export-menu__state");
		content.getChildren().addAll(header, stateHost);
		popover.content(content);
		popover.onOpenChange(open -> {
			if (!open) {
				session.setDropdownOpen(false);
			}
		});
		buildProgressPane();
		retrySaveButton = buildErrorPane();
		buildCompletePane();
		tipTimer.setOnFinished(event -> advanceTip());
		session.onChange(this::render);
		settings.onChange(this::render);
		render();
	}

	/** Opens the popover with the React {@code handleOpenExportDropdown} semantics. */
	public void openFromTrigger() {
		if (session.hasPendingExportSave()) {
			session.setDropdownOpen(true);
			session.setError("Save dialog canceled. Click Save Again to save without re-rendering.");
			return;
		}
		session.setDropdownOpen(true);
		session.setProgress(null);
		session.setError(null);
		session.setExportedFilePath(null);
	}

	public void close() {
		popover.hide();
	}

	/** The session model the app-side export controller drives (progress, errors, pending saves). */
	public ExportSessionModel session() {
		return session;
	}

	private void buildProgressPane() {
		progressPane.getStyleClass().add("export-menu__card");
		Label title = new Label("Exporting");
		title.getStyleClass().add("export-menu__title");
		Label subtitle = new Label("Rendering your file.");
		subtitle.getStyleClass().add("export-menu__subtitle");
		tipLabel.getStyleClass().add("export-menu__note");
		tipLabel.setWrapText(true);
		VBox left = new VBox(2, title, subtitle, tipLabel);
		Button cancel = new Button("Cancel", Button.Variant.OUTLINE, Button.Size.SM);
		cancel.getStyleClass().add("export-menu__small-button");
		// The rotating tip line changes the left column height as tips swap. Without a fixed
		// size the Cancel button stretches/shifts with it; pin it top-right instead.
		cancel.setMinWidth(84);
		cancel.setMaxWidth(84);
		cancel.setMaxHeight(Region.USE_PREF_SIZE);
		cancel.setOnAction(event -> callbacks.cancelExport());
		HBox top = new HBox(12, left, cancel);
		top.setAlignment(javafx.geometry.Pos.TOP_LEFT);
		top.getStyleClass().add("export-menu__top");
		HBox.setHgrow(left, Priority.ALWAYS);

		progressTrack.getStyleClass().add("export-progress-track");
		progressFill.getStyleClass().add("export-progress-fill");
		progressTrack.setPrefHeight(8);
		progressTrack.setMinHeight(8);
		progressTrack.setMaxHeight(8);
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
		clip.widthProperty().bind(progressTrack.widthProperty());
		clip.heightProperty().bind(progressTrack.heightProperty());
		progressTrack.setClip(clip);
		progressTrack.getChildren().add(progressFill);
		progressFill.setPrefHeight(8);
		progressFill.setMinHeight(8);
		progressFill.setMaxHeight(8);

		percentLabel.getStyleClass().add("export-menu__subtitle");
		speedLabel.getStyleClass().add("export-menu__note");
		speedLabel.setWrapText(true);
		pathLabel.getStyleClass().add("export-menu__note");
		pathLabel.setWrapText(true);
		skipLabel.getStyleClass().add("export-menu__warning");
		skipLabel.setWrapText(true);
		progressPane.getChildren().addAll(top, progressTrack, percentLabel, speedLabel, pathLabel,
				skipLabel);
	}

	private Button buildErrorPane() {
		errorPane.getStyleClass().add("export-menu__card");
		Label title = new Label("Export issue");
		title.getStyleClass().add("export-menu__title");
		errorPathLabel.getStyleClass().add("export-menu__note");
		errorPathLabel.setWrapText(true);
		errorTextLabel.getStyleClass().add("export-menu__subtitle");
		errorTextLabel.getStyleClass().add("export-menu__error-text");
		errorTextLabel.setWrapText(true);
		Button copy = new Button("Copy error", Button.Variant.OUTLINE, Button.Size.SM);
		copy.getStyleClass().add("export-menu__small-button");
		copy.setOnAction(event -> copyError());
		Button retrySave = new Button("Save Again", Button.Variant.DEFAULT, Button.Size.SM);
		retrySave.getStyleClass().add("export-menu__small-button");
		retrySave.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(retrySave, Priority.ALWAYS);
		retrySave.setOnAction(event -> callbacks.retrySaveExport());
		Button close = new Button("Close", Button.Variant.OUTLINE, Button.Size.SM);
		close.getStyleClass().add("export-menu__small-button");
		close.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(close, Priority.ALWAYS);
		close.setOnAction(event -> closeDialog());
		HBox actions = new HBox(8, copy, retrySave, close);
		actions.getStyleClass().add("export-menu__actions");
		errorPane.getChildren().addAll(title, errorPathLabel, errorTextLabel, actions);
		return retrySave;
	}

	private void buildCompletePane() {
		completePane.getStyleClass().add("export-menu__card");
		Label title = new Label("Export complete");
		title.getStyleClass().add("export-menu__title");
		Label subtitle = new Label("Your file was saved successfully.");
		subtitle.getStyleClass().add("export-menu__subtitle");
		completePathLabel.getStyleClass().add("export-menu__note");
		completePathLabel.setWrapText(true);
		completeFileLabel.getStyleClass().add("export-menu__file-name");
		completeFileLabel.setWrapText(true);
		Button reveal = new Button("Show In Folder", Button.Variant.DEFAULT, Button.Size.SM);
		reveal.getStyleClass().add("export-menu__small-button");
		reveal.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(reveal, Priority.ALWAYS);
		reveal.setOnAction(event -> callbacks.revealExportedFile());
		Button done = new Button("Done", Button.Variant.OUTLINE, Button.Size.SM);
		done.getStyleClass().add("export-menu__small-button");
		done.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(done, Priority.ALWAYS);
		done.setOnAction(event -> closeDialog());
		HBox actions = new HBox(8, reveal, done);
		actions.getStyleClass().add("export-menu__actions");
		completePane.getChildren().addAll(title, subtitle, completePathLabel, completeFileLabel,
				actions);
	}

	/** Re-renders the popover for the current model/settings state (call on the FX thread). */
	public void render() {
		if (!session.isDropdownOpen()) {
			if (popover.isShowing()) {
				popover.hide();
			}
			return;
		}
		if (!popover.isShowing()) {
			popover.show();
		}
		switch (session.screen()) {
			case PROGRESS -> showProgress();
			case ERROR -> showError();
			case COMPLETE -> showComplete();
			case SETTINGS -> showSettings();
		}
		popover.relayout();
	}

	private void showSettings() {
		stopTips();
		stateHost.getChildren().setAll(new ExportSettingsMenu(settings, hasCaptionsForSidecar,
				dimensions.get(), () -> callbacks.startExport(settings.preferences())));
	}

	private void showProgress() {
		ExportProgress progress = session.progress();
		ExportFormat format = settings.exportFormat();
		viewModel.update(true, progress, format, settings.preferences().exportPipelineModel());
		ExportStatusModel status = viewModel.status();
		stateHost.getChildren().setAll(progressPane);
		// Rotating tip line (lightning/modern pipeline; the legacy hint is unreachable in Java).
		String tip = status.isLightningExportInProgress() ? tipText() : null;
		tipLabel.setText(tip == null ? "" : tip);
		tipLabel.setVisible(tip != null);
		tipLabel.setManaged(tip != null);
		// React: isRenderingAudio ? audioProgress*100 : (finalizingProgress ?? percentage ?? 8).
		double fraction = progress == null ? NULL_PROGRESS_FRACTION : viewModel.progressFraction();
		boolean indeterminate = status.isExportPreparing() || status.isExportSaving()
				|| status.isExportFinalSaveIndeterminate();
		progressFill.getStyleClass().remove("export-progress-fill--indeterminate");
		progressFill.prefWidthProperty().unbind();
		stopIndeterminate();
		if (indeterminate) {
			// React: w-2/5 fill sliding -100% → 350% of its width, 1.5 s, infinite.
			progressFill.getStyleClass().add("export-progress-fill--indeterminate");
			progressFill.prefWidthProperty().bind(progressTrack.widthProperty().multiply(0.4));
			startIndeterminate();
		} else {
			progressFill.prefWidthProperty().bind(progressTrack.widthProperty().multiply(fraction));
			progressFill.setTranslateX(0);
		}
		percentLabel.setText(viewModel.exportPercentLabel());
		String speed = viewModel.exportRenderSpeedLabel();
		speedLabel.setText(speed == null ? "" : speed);
		speedLabel.setVisible(speed != null);
		speedLabel.setManaged(speed != null);
		String runtime = status.runtimeLabel();
		pathLabel.setText(runtime == null ? "" : "Engine: " + runtime);
		pathLabel.setVisible(runtime != null);
		pathLabel.setManaged(runtime != null);
		String skip = status.nativeSkipLabel();
		skipLabel.setText(skip == null ? "" : skip);
		skipLabel.setVisible(skip != null);
		skipLabel.setManaged(skip != null);
		startTips();
	}

	private void showError() {
		stopTips();
		stateHost.getChildren().setAll(errorPane);
		String runtime = viewModel.status().runtimeLabel();
		errorPathLabel.setText(runtime == null ? "" : "Engine: " + runtime);
		errorPathLabel.setVisible(runtime != null);
		errorPathLabel.setManaged(runtime != null);
		errorTextLabel.setText(session.error() == null ? "" : session.error());
		retrySaveButton.setVisible(session.hasPendingExportSave());
		retrySaveButton.setManaged(session.hasPendingExportSave());
	}

	private void showComplete() {
		stopTips();
		stateHost.getChildren().setAll(completePane);
		String runtime = viewModel.status().runtimeLabel();
		completePathLabel.setText(runtime == null ? "" : "Engine: " + runtime);
		completePathLabel.setVisible(runtime != null);
		completePathLabel.setManaged(runtime != null);
		String file = session.exportedFilePath();
		completeFileLabel.setText(fileNameOf(file));
	}

	/** React {@code handleExportDropdownClose}: drop the pending save and reset the state. */
	public void closeDialog() {
		callbacks.discardPendingSave(session.clearPendingExportSave());
		session.setProgress(null);
		session.setError(null);
		session.setExportedFilePath(null);
		session.setDropdownOpen(false);
	}

	private void copyError() {
		ClipboardContent clipboard = new ClipboardContent();
		clipboard.putString(session.error() == null ? "" : session.error());
		Clipboard.getSystemClipboard().setContent(clipboard);
		Toast.show(content, "Error copied");
	}

	private static String fileNameOf(@Nullable String path) {
		if (path == null) {
			return "";
		}
		String[] parts = path.split("[\\\\/]");
		return parts.length == 0 ? path : parts[parts.length - 1];
	}

	// ---- rotating export tips (useExportMessages; the announcement stream stays empty) ----

	private @Nullable String tipText() {
		var tips = ExportMessages.defaultTips();
		if (tips.isEmpty()) {
			return null;
		}
		return tips.get(tipIndex % tips.size()).text();
	}

	private void startTips() {
		var tips = ExportMessages.defaultTips();
		if (tips.size() < 2 || tipTimer.getStatus() == Animation.Status.RUNNING) {
			return;
		}
		tipIndex = 0;
		tipTimer.setDuration(Duration.seconds(tips.get(0).durationSeconds()));
		tipTimer.playFromStart();
	}

	private void advanceTip() {
		var tips = ExportMessages.defaultTips();
		if (tips.isEmpty()) {
			return;
		}
		tipIndex = (tipIndex + 1) % tips.size();
		tipLabel.setText(tips.get(tipIndex).text());
		tipTimer.setDuration(Duration.seconds(tips.get(tipIndex).durationSeconds()));
		tipTimer.playFromStart();
	}

	private void stopTips() {
		tipTimer.stop();
	}

	// ---- indeterminate progress slide (progress-bar.css keyframes) ----

	private void startIndeterminate() {
		if (indeterminate == null) {
			indeterminate = new javafx.animation.Transition() {
				{
					setCycleDuration(Duration.seconds(1.5));
					setCycleCount(Animation.INDEFINITE);
					setInterpolator(javafx.animation.Interpolator.ofSpline(0.65, 0, 0.35, 1));
				}

				@Override
				protected void interpolate(double fraction) {
					double width = progressFill.getWidth();
					progressFill.setTranslateX(-width + fraction * 4.5 * width);
				}
			};
		}
		indeterminate.play();
	}

	private void stopIndeterminate() {
		if (indeterminate != null) {
			indeterminate.stop();
			indeterminate = null;
		}
	}
}
