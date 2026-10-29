package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/**
 * Shared image/video gallery (port of {@code WallpaperGrid.tsx}): five columns of 4:3 tiles, the
 * add tile first, a check badge on the selected tile.
 */
public final class WallpaperGrid extends GridPane {

	private final Runnable onAdd;
	private final Consumer<String> onSelect;
	private List<EditorWallpapers.Tile> tiles;
	private @Nullable String selected;

	public WallpaperGrid(List<EditorWallpapers.Tile> tiles, @Nullable String selected,
			Consumer<String> onSelect, Runnable onAdd) {
		this.tiles = tiles;
		this.selected = selected;
		this.onSelect = onSelect;
		this.onAdd = onAdd;
		getStyleClass().add("editor-tiles");
		for (int column = 0; column < 5; column++) {
			ColumnConstraints constraints = new ColumnConstraints();
			constraints.setPercentWidth(20);
			constraints.setFillWidth(true);
			getColumnConstraints().add(constraints);
		}
		rebuild();
	}

	public void setSelection(String value) {
		this.selected = value;
		rebuild();
	}

	public void setTiles(List<EditorWallpapers.Tile> nextTiles, String value) {
		this.tiles = nextTiles;
		this.selected = value;
		rebuild();
	}

	private void rebuild() {
		getChildren().clear();
		int column = 0;
		int row = 0;
		add(tile(addButton()), column++, row);
		for (EditorWallpapers.Tile tile : tiles) {
			add(tile(tileNode(tile)), column, row);
			column++;
			if (column == 5) {
				column = 0;
				row++;
			}
		}
	}

	private Button addButton() {
		Button button = new Button();
		button.getStyleClass().addAll("editor-tile", "editor-tile__add");
		Icon plus = Icon.of(Icons.Plus, 14);
		plus.setIconColor(Color.web("#8b929a"));
		button.setGraphic(plus);
		button.setMaxWidth(Double.MAX_VALUE);
		button.setPrefHeight(38);
		button.setOnAction(event -> onAdd.run());
		GridPane.setHgrow(button, Priority.ALWAYS);
		return button;
	}

	private Button tile(Node graphic) {
		Button button = new Button();
		button.getStyleClass().add("editor-tile");
		button.setGraphic(graphic);
		button.setMaxWidth(Double.MAX_VALUE);
		button.setPrefHeight(38);
		GridPane.setHgrow(button, Priority.ALWAYS);
		return button;
	}

	private Node tileNode(EditorWallpapers.Tile tile) {
		Image thumbnail = EditorWallpapers.thumbnail(tile.value());
		ImageView image = new ImageView(thumbnail);
		image.setFitWidth(48);
		image.setFitHeight(36);
		image.setPreserveRatio(false);
		image.setSmooth(true);
		StackPane stack = new StackPane(image);
		stack.setAlignment(Pos.CENTER_LEFT);
		if (tile.value().equals(selected)) {
			Icon check = Icon.of(Icons.Check, 9, "bold");
			check.setIconColor(Color.WHITE);
			HBox badge = new HBox(check);
			badge.getStyleClass().add("editor-tile__check");
			badge.setAlignment(Pos.CENTER);
			badge.setMaxSize(16, 16);
			StackPane.setAlignment(badge, Pos.BOTTOM_LEFT);
			stack.getChildren().add(badge);
		}
		Button button = new Button();
		button.getStyleClass().add("editor-tile");
		button.setGraphic(stack);
		button.setMaxWidth(Double.MAX_VALUE);
		button.setPrefHeight(38);
		button.setOnAction(event -> onSelect.accept(tile.value()));
		return button;
	}
}
