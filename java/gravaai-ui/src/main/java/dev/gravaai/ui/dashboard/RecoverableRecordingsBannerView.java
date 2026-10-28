package dev.gravaai.ui.dashboard;

import java.util.List;

import dev.gravaai.services.projects.RecoverableRecording;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Card;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Offers to recover recordings interrupted by a crash, kill or power loss (port of
 * {@code RecoverableRecordingsBanner.tsx}).
 *
 * <p>The rows come from the {@link dev.gravaai.services.projects.RecoverableRecordingsProvider}
 * read port; the recording service owns recovery/discard, so the buttons call the injected
 * {@link DashboardDependencies.Actions} callbacks. While the port returns nothing the banner stays
 * hidden, exactly like the React component with an empty list.
 */
public final class RecoverableRecordingsBannerView extends VBox {

	public RecoverableRecordingsBannerView(DashboardModel model, DashboardDependencies dependencies) {
		getStyleClass().add("recoverable-banner");
		setSpacing(8);
		model.recoverableRecordings().addListener((ListChangeListener<RecoverableRecording>) change ->
				rebuild(model, dependencies));
		rebuild(model, dependencies);
	}

	private void rebuild(DashboardModel model, DashboardDependencies dependencies) {
		getChildren().clear();
		List<RecoverableRecording> items = List.copyOf(model.recoverableRecordings());
		boolean visible = !items.isEmpty();
		setVisible(visible);
		setManaged(visible);
		for (RecoverableRecording item : items) {
			getChildren().add(buildRow(item, dependencies));
		}
	}

	private Region buildRow(RecoverableRecording item, DashboardDependencies dependencies) {
		Card card = new Card();
		card.getStyleClass().add("recoverable-banner__card");
		HBox row = new HBox(12);
		row.setAlignment(Pos.CENTER_LEFT);

		Region iconHost = new Region();
		iconHost.getStyleClass().add("recoverable-banner__icon");
		Icon warning = Icon.of(Icons.WarningCircleIcon, 20, "fill");
		VBox icon = new VBox(warning);
		icon.getStyleClass().add("recoverable-banner__icon-box");
		icon.setAlignment(Pos.CENTER);

		VBox texts = new VBox(2);
		HBox.setHgrow(texts, Priority.ALWAYS);
		Label title = new Label(item.meeting()
				? "A meeting recording was interrupted"
				: "A screen recording was interrupted");
		title.getStyleClass().add("recoverable-banner__title");
		Label detail = new Label(DashboardDates.mediumDateTime(item.createdAt()) + " · "
				+ formatRecoverableSize(item.bytes()) + " · Recover it to keep editing.");
		detail.getStyleClass().add("recoverable-banner__detail");
		texts.getChildren().addAll(title, detail);

		Button discard = new Button("Discard", Button.Variant.GHOST, Button.Size.SM);
		discard.setOnAction(event -> dependencies.actions().discardRecording(item));
		Button recover = new Button("Recover", Button.Variant.DEFAULT, Button.Size.SM);
		recover.setOnAction(event -> dependencies.actions().recoverRecording(item));
		HBox actions = new HBox(8, discard, recover);
		actions.setAlignment(Pos.CENTER_RIGHT);

		row.getChildren().addAll(icon, texts, actions);
		card.getChildren().add(row);
		return card;
	}

	/** Port of {@code formatRecoverableSize}. */
	public static String formatRecoverableSize(long bytes) {
		if (bytes >= 1024L * 1024L * 1024L) {
			return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
		}
		if (bytes >= 1024L * 1024L) {
			return Math.round(bytes / (1024.0 * 1024.0)) + " MB";
		}
		return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
	}
}
