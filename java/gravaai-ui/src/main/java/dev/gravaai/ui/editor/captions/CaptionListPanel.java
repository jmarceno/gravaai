package dev.gravaai.ui.editor.captions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

import dev.gravaai.core.editor.CaptionCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.event.Subscription;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.TextArea;
import dev.gravaai.ui.kit.Theme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * CaptionListPanel (port of {@code CaptionListPanel.tsx}): edits the selected caption cue.
 *
 * <p>Text commits through {@code CaptionCommands.TextEdit} (word-aware via
 * {@code CaptionEditing.updateCaptionCuesForEditedTarget}), timing through
 * {@code CaptionCommands.Retime}, and the Split/Merge/Delete buttons through the matching store
 * commands. The panel follows the store selection, so selecting a caption on the timeline or in
 * the caption list shows the same editor.
 */
public final class CaptionListPanel extends VBox {

	private final EditorStore store;
	private final DoubleSupplier playheadMs;
	private final Subscription subscription;

	private final TextArea textArea = new TextArea();
	private final Input startInput = new Input();
	private final Input endInput = new Input();
	private final Button splitButton = new Button("Split", Button.Variant.GHOST, Button.Size.SM);
	private final Button mergeButton = new Button("Merge", Button.Variant.GHOST, Button.Size.SM);
	private final Button deleteButton = new Button("Delete", Button.Variant.DESTRUCTIVE,
			Button.Size.SM);

	private boolean updating;
	private boolean cancelNextCommit;
	private String editedCueId;

	public CaptionListPanel(EditorStore store, DoubleSupplier playheadMs) {
		this.store = store;
		this.playheadMs = playheadMs == null ? () -> 0.0 : playheadMs;
		getStyleClass().add("caption-list-panel");
		setSpacing(10);
		setPadding(new Insets(12));
		setStyle("-fx-background-color: " + css(Theme.SURFACE) + "; -fx-background-radius: 12;"
				+ " -fx-border-color: " + css(Theme.SEPARATOR) + "; -fx-border-radius: 12;");
		setVisible(false);
		setManaged(false);

		textArea.setAccessibleText("Text");
		textArea.prompt("Caption text");
		textArea.setWrapText(true);
		textArea.setPrefHeight(72);
		textArea.setStyle("-fx-font-size: 13px; -fx-control-inner-background: " + css(Theme.FIELD)
				+ "; -fx-text-fill: " + css(Theme.FG) + ";");
		textArea.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (focused) {
				String cueId = store.selectedCaptionId();
				if (cueId != null) {
					store.apply(new CaptionCommands.BeginEdit(cueId));
				}
				return;
			}
			commitText();
		});
		textArea.setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
				event.consume();
				textArea.getParent().requestFocus();
			} else if (event.getCode() == KeyCode.ESCAPE) {
				event.consume();
				cancelNextCommit = true;
				CaptionCue cue = selectedCue();
				if (cue != null) {
					textArea.setText(cue.text());
				}
				textArea.getParent().requestFocus();
			}
		});

		Label textLabel = fieldLabel("Text");
		Label startLabel = fieldLabel("Start");
		Label endLabel = fieldLabel("End");

		startInput.setAccessibleText("Start");
		endInput.setAccessibleText("End");
		styleField(startInput);
		styleField(endInput);
		startInput.setOnAction(event -> commitTiming());
		endInput.setOnAction(event -> commitTiming());
		startInput.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				commitTiming();
			}
		});
		endInput.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				commitTiming();
			}
		});
		HBox.setHgrow(startInput, Priority.ALWAYS);
		HBox.setHgrow(endInput, Priority.ALWAYS);
		HBox timingRow = new HBox(8, new VBox(4, startLabel, startInput),
				new VBox(4, endLabel, endInput));
		timingRow.setAlignment(Pos.CENTER_LEFT);

		splitButton.setMaxWidth(Double.MAX_VALUE);
		mergeButton.setMaxWidth(Double.MAX_VALUE);
		deleteButton.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(splitButton, Priority.ALWAYS);
		HBox.setHgrow(mergeButton, Priority.ALWAYS);
		HBox.setHgrow(deleteButton, Priority.ALWAYS);
		splitButton.setOnAction(event -> splitSelected());
		mergeButton.setOnAction(event -> mergeSelected());
		deleteButton.setOnAction(event -> deleteSelected());
		HBox actions = new HBox(8, splitButton, mergeButton, deleteButton);
		actions.setAlignment(Pos.CENTER_LEFT);

		getChildren().addAll(textLabel, textArea, timingRow, actions);
		subscription = store.onChange(changed -> Platform.runLater(this::refresh));
		refresh();
	}

	/** Commits the text draft now (Enter/blur path without a focus change; tests use it). */
	public void commitTextDraft() {
		commitText();
	}

	/** Commits the timing drafts now (Enter/blur path without a focus change; tests use it). */
	public void commitTimingDraft() {
		commitTiming();
	}

	/** Test/app seam: the text draft editor. */
	public TextArea textEditor() {
		return textArea;
	}

	/** Test/app seam: the start timecode field. */
	public Input startEditor() {
		return startInput;
	}

	/** Test/app seam: the end timecode field. */
	public Input endEditor() {
		return endInput;
	}

	/** Test/app seam: the Split action button. */
	public Button splitAction() {
		return splitButton;
	}

	/** Test/app seam: the Merge action button. */
	public Button mergeAction() {
		return mergeButton;
	}

	/** Test/app seam: the Delete action button. */
	public Button deleteAction() {
		return deleteButton;
	}

	/** Stops following the store (the inspector panel is being disposed). */
	public void dispose() {
		subscription.close();
	}

	/** Rebuilds the editor from the store selection. */
	public void refresh() {
		CaptionCue cue = selectedCue();
		boolean visible = cue != null;
		setVisible(visible);
		setManaged(visible);
		if (!visible) {
			editedCueId = null;
			return;
		}
		if (cue.id().equals(editedCueId) && textArea.isFocused()) {
			// The user is typing: never overwrite the draft.
			return;
		}
		editedCueId = cue.id();
		updating = true;
		try {
			textArea.setText(cue.text() == null ? "" : cue.text());
			startInput.setText(CaptionListModel.formatTimecode(cue.startMs()));
			endInput.setText(CaptionListModel.formatTimecode(cue.endMs()));
			List<CaptionCue> cues = cues();
			int index = CaptionListModel.selectedIndex(cues, cue.id());
			mergeButton.setDisable(!CaptionListModel.canMerge(index, cues.size()));
		} finally {
			updating = false;
		}
	}

	/** The cue currently edited, for tests/shells that need to mirror the selection. */
	public CaptionCue selectedCue() {
		String id = store.selectedCaptionId();
		if (id == null) {
			return null;
		}
		for (CaptionCue cue : cues()) {
			if (cue.id().equals(id)) {
				return cue;
			}
		}
		return null;
	}

	private List<CaptionCue> cues() {
		return List.copyOf(store.state().autoCaptions());
	}

	private void commitText() {
		if (updating || cancelNextCommit) {
			cancelNextCommit = false;
			CaptionCue cue = selectedCue();
			if (cue != null) {
				textArea.setText(cue.text() == null ? "" : cue.text());
			}
			return;
		}
		CaptionCue cue = selectedCue();
		if (cue == null) {
			return;
		}
		String decision = CaptionListModel.decideTextCommit(textArea.getText(), cue);
		if (decision == null) {
			textArea.setText(cue.text() == null ? "" : cue.text());
			return;
		}
		store.apply(new CaptionCommands.TextEdit(cue.id(), decision));
	}

	private void commitTiming() {
		if (updating) {
			return;
		}
		CaptionCue cue = selectedCue();
		if (cue == null) {
			return;
		}
		CaptionListModel.TimingCommit commit = CaptionListModel.decideTimingCommit(
				startInput.getText(), endInput.getText(), cue);
		if (commit == null) {
			updating = true;
			try {
				startInput.setText(CaptionListModel.formatTimecode(cue.startMs()));
				endInput.setText(CaptionListModel.formatTimecode(cue.endMs()));
			} finally {
				updating = false;
			}
			return;
		}
		store.apply(new CaptionCommands.Retime(cue.id(), CaptionListModel.retimeSpan(commit)));
	}

	private void splitSelected() {
		CaptionCue cue = selectedCue();
		if (cue == null) {
			return;
		}
		double atMs = CaptionListModel.splitAt(cue, playheadMs.getAsDouble());
		store.apply(new CaptionCommands.Split(cue.id(), atMs));
	}

	private void mergeSelected() {
		List<CaptionCue> cues = cues();
		CaptionCue cue = selectedCue();
		if (cue == null) {
			return;
		}
		int index = CaptionListModel.selectedIndex(cues, cue.id());
		var target = CaptionListModel.mergeTarget(cues, index);
		target.ifPresent(next -> store.apply(new CaptionCommands.Merge(cue.id(), next.id())));
	}

	private void deleteSelected() {
		CaptionCue cue = selectedCue();
		if (cue == null) {
			return;
		}
		store.apply(new CaptionCommands.Delete(cue.id()));
	}

	private static Label fieldLabel(String text) {
		Label label = new Label(text.toUpperCase(java.util.Locale.ROOT));
		label.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: "
				+ css(Theme.MUTED) + ";");
		return label;
	}

	private static void styleField(Input field) {
		field.setPrefHeight(30);
		field.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 11px;"
				+ " -fx-background-color: " + css(Theme.FIELD) + "; -fx-background-radius: 8;"
				+ " -fx-text-fill: " + css(Theme.FG) + "; -fx-padding: 0 8 0 8;");
	}

	private static String css(javafx.scene.paint.Color color) {
		return "#" + color.toString().substring(2, 8);
	}
}
