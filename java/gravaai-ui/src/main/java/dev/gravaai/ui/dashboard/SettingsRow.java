package dev.gravaai.ui.dashboard;

import dev.gravaai.ui.kit.Label;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * One preferences row (port of {@code src/components/video-editor/SettingsRow.tsx}): a 13 px
 * medium label over a 12 px relaxed description, with the control on the right (or below when
 * {@code stacked}).
 */
public final class SettingsRow extends HBox {

	public SettingsRow(String title, String description, Node control) {
		this(title, description, control, false);
	}

	public SettingsRow(String title, String description, Node control, boolean stacked) {
		getStyleClass().add("settings-row");
		setSpacing(16);
		setAlignment(stacked ? Pos.TOP_LEFT : Pos.CENTER_LEFT);

		VBox texts = new VBox(4);
		HBox.setHgrow(texts, Priority.ALWAYS);
		Label heading = new Label(title);
		heading.getStyleClass().add("settings-row__title");
		texts.getChildren().add(heading);
		if (description != null && !description.isBlank()) {
			Label detail = new Label(description);
			detail.getStyleClass().add("settings-row__description");
			detail.setWrapText(true);
			texts.getChildren().add(detail);
		}

		if (stacked) {
			VBox column = new VBox(12, texts, control);
			getChildren().add(column);
			setAlignment(Pos.TOP_LEFT);
		} else {
			getChildren().addAll(texts, control);
		}
	}
}
