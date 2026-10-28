package dev.gravaai.ui.editor;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.core.editor.CaptionCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.event.Subscription;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionOps;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.TextArea;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Caption editor for the selected cue (port of {@code CaptionListPanel.tsx}): text, start/end
 * timecodes and split/merge/delete, all as {@link CaptionCommands} on the shared
 * {@link EditorStore}.
 */
public final class CaptionListPanel extends VBox {

	private static final Pattern TIMECODE =
			Pattern.compile("^(?:(\\d+):)?(\\d{1,2})(?:\\.(\\d{1,3}))?$");

	private final EditorStore store;
	private final EditorPlaybackPort playback;
	private final Subscription subscription;

	public CaptionListPanel(EditorStore store, EditorPlaybackPort playback) {
		this.store = store;
		this.playback = playback;
		setSpacing(12);
		setPadding(new Insets(10));
		setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 8px;");
		refresh();
		subscription = store.onChange(changed -> Platform.runLater(this::refresh));
	}

	/** Rebuilds the editor for the current selection (or hides when no caption is selected). */
	public void refresh() {
		getChildren().clear();
		List<CaptionCue> cues = store.state().autoCaptions();
		String selectedId = store.selectedCaptionId();
		int index = -1;
		for (int candidate = 0; candidate < cues.size(); candidate++) {
			if (cues.get(candidate).id().equals(selectedId)) {
				index = candidate;
				break;
			}
		}
		final int cueIndex = index;
		boolean visible = index >= 0;
		setVisible(visible);
		setManaged(visible);
		if (!visible) {
			return;
		}

		CaptionCue cue = cues.get(index);
		Label textLabel = fieldLabel("TEXT");
		TextArea text = new TextArea();
		text.setText(cue.text());
		text.setPrefRowCount(3);
		text.setStyle("-fx-font-size: 13px;");
		text.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				commitText(cue, text.getText());
			}
		});
		VBox textBox = new VBox(4, textLabel, text);

		Input start = new Input().text(formatTimecode(cue.startMs()));
		Input end = new Input().text(formatTimecode(cue.endMs()));
		start.setStyle("-fx-font-size: 11px;");
		end.setStyle("-fx-font-size: 11px;");
		start.setOnAction(event -> commitTiming(cue, start.getText(), end.getText(),
				() -> {
					start.setText(formatTimecode(cue.startMs()));
					end.setText(formatTimecode(cue.endMs()));
				}));
		end.setOnAction(event -> commitTiming(cue, start.getText(), end.getText(),
				() -> {
					start.setText(formatTimecode(cue.startMs()));
					end.setText(formatTimecode(cue.endMs()));
				}));
		GridPane timing = new GridPane();
		timing.setHgap(8);
		ColumnConstraints half = new ColumnConstraints();
		half.setPercentWidth(50);
		timing.getColumnConstraints().addAll(half, half);
		timing.add(new VBox(4, fieldLabel("START"), start), 0, 0);
		timing.add(new VBox(4, fieldLabel("END"), end), 1, 0);

		Button split = actionButton("Split", Icons.Scissors, Button.Variant.GHOST);
		split.setOnAction(event -> {
			long atMs = Math.round(playback.state().currentTimeSeconds() * 1000);
			long clamped = Math.min(cue.endMs(), Math.max(cue.startMs(), atMs));
			store.apply(new CaptionCommands.Split(cue.id(), clamped));
		});
		boolean canMerge = cueIndex < cues.size() - 1;
		Button merge = actionButton("Merge", Icons.ArrowsMerge, Button.Variant.GHOST);
		merge.setDisable(!canMerge);
		merge.setOnAction(event -> {
			if (canMerge) {
				store.apply(new CaptionCommands.Merge(cue.id(), cues.get(cueIndex + 1).id()));
			}
		});
		Button delete = actionButton("Delete", Icons.Trash, Button.Variant.DESTRUCTIVE);
		delete.setOnAction(event -> store.apply(new CaptionCommands.Delete(cue.id())));
		HBox actions = new HBox(8, split, merge, delete);
		for (var child : actions.getChildren()) {
			HBox.setHgrow(child, Priority.ALWAYS);
			((Button) child).setMaxWidth(Double.MAX_VALUE);
		}

		getChildren().addAll(textBox, timing, actions);
	}

	private void commitText(CaptionCue cue, String rawText) {
		String normalized = rawText == null ? "" : rawText.strip();
		if (!normalized.isEmpty() && !normalized.equals(cue.text())) {
			store.apply(new CaptionCommands.TextEdit(cue.id(), normalized));
		}
	}

	private void commitTiming(CaptionCue cue, String startText, String endText, Runnable reset) {
		Long startMs = parseTimecode(startText);
		Long endMs = parseTimecode(endText);
		if (startMs == null || endMs == null || endMs <= startMs) {
			reset.run();
			return;
		}
		if (startMs != cue.startMs() || endMs != cue.endMs()) {
			store.apply(new CaptionCommands.Retime(cue.id(),
					new CaptionOps.RetimeSpan(startMs, endMs)));
		}
	}

	/** Stops listening to the store. */
	public void dispose() {
		subscription.close();
	}

	private static Label fieldLabel(String text) {
		Label label = new Label(text);
		label.setStyle("-fx-font-size: 9px; -fx-font-weight: 700; -fx-text-fill: -g-muted; "
				+ "-fx-letter-spacing: 1px;");
		return label;
	}

	private static Button actionButton(String text, String icon, Button.Variant variant) {
		Button button = new Button(text, variant, Button.Size.SM);
		button.icon(icon);
		button.setGraphicTextGap(6);
		button.setContentDisplay(ContentDisplay.LEFT);
		button.setAlignment(Pos.CENTER);
		return button;
	}

	/** {@code formatTimecode}: {@code m:ss.mmm} with a 0 floor. */
	public static String formatTimecode(long ms) {
		long safeMs = Math.max(0, Math.round(ms));
		long minutes = safeMs / 60_000;
		long seconds = (safeMs % 60_000) / 1_000;
		long millis = safeMs % 1_000;
		return minutes + ":" + (seconds < 10 ? "0" + seconds : Long.toString(seconds)) + "."
				+ String.format(java.util.Locale.ROOT, "%03d", millis);
	}

	/** {@code parseTimecode}: null for anything the React parser rejects. */
	public static Long parseTimecode(String value) {
		if (value == null) {
			return null;
		}
		Matcher matcher = TIMECODE.matcher(value.strip());
		if (!matcher.matches()) {
			return null;
		}
		long minutes = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
		long seconds = Long.parseLong(matcher.group(2));
		long millis = 0;
		if (matcher.group(3) != null) {
			String digits = matcher.group(3);
			while (digits.length() < 3) {
				digits += "0";
			}
			millis = Long.parseLong(digits);
		}
		return (minutes * 60 + seconds) * 1_000 + millis;
	}
}
