package dev.gravaai.ui.editor;

import dev.gravaai.core.event.Subscription;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.DropdownMenu;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Popover;
import dev.gravaai.ui.kit.Slider;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Playback bar (port of the transport block in {@code layout/EditorPreviewPanel.tsx} and
 * {@code PlaybackControls.tsx}): layer tools on the left, transport in the centre, preview volume
 * on the right, all driven through {@link EditorPlaybackPort} (plan §5.9, P2.5).
 *
 * <p>The engine wrapper ({@code dev.gravaai.ui.editor.preview.PlaybackControllerPort}) implements
 * that port, so this bar works with the real playback engine and with the idle fakes used by
 * snapshots/tests. Playing state swaps the white play disc for the subtle translucent style,
 * exactly like the React class switch.
 */
public final class PlaybackControls extends GridPane {

	private final EditorPlaybackPort port;
	private final Label currentTime = new Label("0:00");
	private final Label duration = new Label("0:00");
	private final Button playButton;
	private final Button volumeButton;
	private final Slider volumeSlider;
	private final Label volumePercent = new Label("100%");
	private final Icon playIcon;
	private final Icon pauseIcon;
	private final Subscription subscription;

	public PlaybackControls(EditorPlaybackPort port, EditorViewActions actions) {
		this.port = port;
		getStyleClass().add("editor-playback");
		ColumnConstraints left = new ColumnConstraints();
		left.setPercentWidth(34);
		ColumnConstraints center = new ColumnConstraints();
		center.setPercentWidth(32);
		ColumnConstraints right = new ColumnConstraints();
		right.setPercentWidth(34);
		getColumnConstraints().addAll(left, center, right);
		setAlignment(Pos.CENTER);
		setPadding(new Insets(0, 0, 0, 0));

		// ---- left: add layer / zoom / suggest / split -------------------------
		HBox tools = new HBox(4);
		tools.getStyleClass().add("editor-playback__tools");
		tools.setAlignment(Pos.CENTER_LEFT);

		Button addLayer = new Button("", Button.Variant.GHOST, Button.Size.SM);
		HBox addLayerGraphic = new HBox(4);
		addLayerGraphic.setAlignment(Pos.CENTER_LEFT);
		Icon plus = Icon.of(Icons.Plus, 14);
		Label addLabel = new Label("Add Layer");
		addLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 500;");
		Icon caret = Icon.of(Icons.CaretDown, 10);
		addLayerGraphic.getChildren().addAll(plus, addLabel, caret);
		addLayer.setGraphic(addLayerGraphic);
		DropdownMenu addMenu = new DropdownMenu(addLayer);
		addMenu.item("Text", actions::addTextAnnotation);
		addMenu.item("Figure", actions::addFigureAnnotation);
		addMenu.item("Image", actions::addImageAnnotation);
		addMenu.item("Blur", actions::addBlurAnnotation);
		addMenu.item("Audio", actions::addAudioLayer);

		Button zoom = iconButton(Icons.MagnifyingGlassPlus, "Add zoom", actions::addZoom);
		Button suggest = iconButton(Icons.MagicWand, "Suggest zooms", actions::suggestZooms);
		Button split = iconButton(Icons.Scissors, "Split clip", actions::splitClip);
		tools.getChildren().addAll(addLayer, zoom, suggest, split);

		// ---- centre: time / transport / duration ------------------------------
		HBox centerBox = new HBox(6);
		centerBox.getStyleClass().add("editor-playback__center");
		centerBox.setAlignment(Pos.CENTER);
		currentTime.getStyleClass().add("editor-playback__time");
		duration.getStyleClass().add("editor-playback__duration");
		Button skipBack = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		skipBack.icon(Icons.SkipBack, "fill");
		skipBack.setOnAction(event -> port.skip(-5));
		dev.gravaai.ui.kit.Tooltip.install(skipBack, "Skip back");

		playIcon = Icon.of(Icons.Play, 14, "fill");
		playIcon.setIconColor(Color.web("#0b0d10"));
		pauseIcon = Icon.of(Icons.Pause, 14, "fill");
		pauseIcon.setIconColor(Color.web("#0b0d10"));
		playButton = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		playButton.getStyleClass().add("editor-playback__play");
		playButton.setGraphic(playIcon);
		playButton.setOnAction(event -> {
			port.togglePlayPause();
			refresh();
		});

		Button skipForward = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		skipForward.icon(Icons.SkipForward, "fill");
		skipForward.setOnAction(event -> port.skip(5));
		dev.gravaai.ui.kit.Tooltip.install(skipForward, "Skip forward");

		centerBox.getChildren().addAll(currentTime, skipBack, playButton, skipForward, duration);

		// ---- right: preview volume popover ------------------------------------
		HBox volumeRow = new HBox(4);
		volumeRow.getStyleClass().add("editor-playback__volume");
		volumeRow.setAlignment(Pos.CENTER_RIGHT);
		volumeButton = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		volumeButton.icon(Icons.SpeakerHigh);
		volumeRow.getChildren().add(volumeButton);
		volumeSlider = new Slider();
		volumeSlider.range(0, 1).step(0.01).hideOutput();
		volumeSlider.getStyleClass().add("editor-playback__volume-slider");
		volumeSlider.setPrefWidth(120);
		volumeSlider.onValueChange(value -> port.setVolume(value));
		volumePercent.getStyleClass().add("editor-playback__volume-percent");
		VBox volumeContent = new VBox(8, volumePercent, volumeSlider);
		volumeContent.setPadding(new Insets(10));
		volumeContent.setAlignment(Pos.CENTER);
		Popover volumePopover = new Popover(volumeButton);
		volumePopover.content(volumeContent);
		volumePopover.align(dev.gravaai.ui.kit.Anchors.Align.END);

		add(tools, 0, 0);
		add(centerBox, 1, 0);
		add(volumeRow, 2, 0);
		GridPane.setHgrow(centerBox, Priority.NEVER);

		subscription = port.changes().subscribe(state -> Platform.runLater(this::refresh));
		refresh();
	}

	private Button iconButton(String icon, String tooltip, Runnable action) {
		Button button = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		button.icon(icon);
		button.setOnAction(event -> action.run());
		dev.gravaai.ui.kit.Tooltip.install(button, tooltip);
		return button;
	}

	/** Re-renders from the playback seam. */
	public void refresh() {
		EditorPlaybackPort.PlaybackState state = port.state();
		currentTime.setText(formatTime(state.currentTimeSeconds()));
		duration.setText(formatTime(state.durationSeconds()));
		playButton.setGraphic(state.isPlaying() ? pauseIcon : playIcon);
		Color iconColour = state.isPlaying() ? Color.web("#f2f5f7") : Color.web("#0b0d10");
		playIcon.setIconColor(iconColour);
		pauseIcon.setIconColor(iconColour);
		if (state.isPlaying()) {
			if (!playButton.getStyleClass().contains("editor-playback__play--playing")) {
				playButton.getStyleClass().add("editor-playback__play--playing");
			}
		} else {
			playButton.getStyleClass().remove("editor-playback__play--playing");
		}
		volumeSlider.setValue(state.volume());
		volumePercent.setText(Math.round(state.volume() * 100) + "%");
		volumeButton.setGraphic(Icon.of(volumeIcon(state.volume()), 16));
	}

	private static String volumeIcon(double volume) {
		if (volume <= 0.001) {
			return Icons.SpeakerX;
		}
		return volume < 0.5 ? Icons.SpeakerLow : Icons.SpeakerHigh;
	}

	/** {@code formatTime}: {@code m:ss} with a 0:00 floor. */
	public static String formatTime(double seconds) {
		if (!Double.isFinite(seconds) || seconds < 0) {
			return "0:00";
		}
		long minutes = (long) Math.floor(seconds / 60);
		long secs = (long) Math.floor(seconds % 60);
		return minutes + ":" + (secs < 10 ? "0" + secs : Long.toString(secs));
	}

	/** Stops listening to the playback seam. */
	public void dispose() {
		subscription.close();
	}
}
