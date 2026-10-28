package dev.gravaai.ui.dashboard;

import java.util.List;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Card;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The dashboard announcement carousel (port of {@code DashboardAnnouncements.tsx} and
 * {@code announcementConfig.ts}).
 *
 * <p>{@code dashboardAnnouncements.enabled} is {@code false} in the React source, so the card is
 * hidden by default; flipping {@link #CONFIG} shows the same carousel with arrow/dot navigation.
 * Banner images are loaded from the classpath ({@code /ui/dashboard/announcements/...}) or an
 * http(s)/data URL; a missing image leaves the card text-only instead of failing the screen.
 */
public final class DashboardAnnouncementsView extends StackPane {

	/** One banner in {@code AnnouncementConfig.banners}. */
	public record Banner(String src, String alt, String href) {
	}

	/** Port of the {@code dashboardAnnouncements} constant. */
	public record Config(boolean enabled, List<Banner> banners) {
	}

	/** Port of {@code dashboardAnnouncements}: code-only configuration, disabled by default. */
	public static final Config CONFIG = new Config(false, List.of(
			new Banner("announcements/placeholder-1.svg", "Announcement banner placeholder 1", null),
			new Banner("announcements/placeholder-2.svg", "Announcement banner placeholder 2", null)));

	private final DashboardDependencies dependencies;
	private final boolean enabled;
	private int index;

	public DashboardAnnouncementsView(DashboardDependencies dependencies) {
		this(dependencies, CONFIG);
	}

	public DashboardAnnouncementsView(DashboardDependencies dependencies, Config config) {
		this.dependencies = dependencies;
		getStyleClass().add("dashboard-announcements");
		boolean visible = config.enabled() && !config.banners().isEmpty();
		this.enabled = visible;
		setVisible(visible);
		setManaged(visible);
		if (!visible) {
			return;
		}
		Card card = new Card();
		card.getStyleClass().add("dashboard-announcements__card");
		card.getChildren().add(buildContent(config));
		getChildren().add(card);
	}

	/** Whether the React config enables the carousel at all (used by the screen layout). */
	public boolean enabled() {
		return enabled;
	}

	private Node buildContent(Config config) {
		StackPane body = new StackPane();
		body.getStyleClass().add("dashboard-announcements__body");
		Banner banner = config.banners().get(index % config.banners().size());
		ImageView image = loadImage(banner.src());
		if (image != null) {
			image.getStyleClass().add("dashboard-announcements__image");
			image.setFitWidth(1100);
			image.setPreserveRatio(true);
			body.getChildren().add(image);
		}
		if (config.banners().size() > 1) {
			Button previous = arrowButton(Icons.ArrowLeft, "Previous announcement",
					() -> select(config, index - 1));
			Button next = arrowButton(Icons.ArrowRight, "Next announcement",
					() -> select(config, index + 1));
			HBox controls = new HBox();
			controls.getStyleClass().add("dashboard-announcements__controls");
			controls.setAlignment(Pos.CENTER);
			controls.setMaxWidth(Double.MAX_VALUE);
			controls.getChildren().addAll(previous, spacer(), dots(config), spacer(), next);
			StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
			body.getChildren().add(controls);
		}
		return body;
	}

	private void select(Config config, int nextIndex) {
		index = Math.floorMod(nextIndex, config.banners().size());
		getChildren().clear();
		Card card = new Card();
		card.getStyleClass().add("dashboard-announcements__card");
		card.getChildren().add(buildContent(config));
		getChildren().add(card);
	}

	private HBox dots(Config config) {
		HBox dots = new HBox(8);
		dots.setAlignment(Pos.CENTER);
		for (int slide = 0; slide < config.banners().size(); slide++) {
			int target = slide;
			Button dot = new Button("", Button.Variant.GHOST, Button.Size.ICON);
			dot.getStyleClass().add("dashboard-announcements__dot");
			dot.setText(slide == index % config.banners().size() ? "●" : "○");
			dot.setAccessibleText("Announcement " + (slide + 1));
			dot.setOnAction(event -> select(config, target));
			dots.getChildren().add(dot);
		}
		return dots;
	}

	private Button arrowButton(String icon, String accessibleText, Runnable action) {
		Button button = new Button("", Button.Variant.SECONDARY, Button.Size.SM);
		button.setGraphic(Icon.of(icon, 16));
		button.setAccessibleText(accessibleText);
		button.setOnAction(event -> action.run());
		return button;
	}

	private static javafx.scene.layout.Region spacer() {
		javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
		HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
		return spacer;
	}

	private ImageView loadImage(String src) {
		if (src == null || src.isBlank()) {
			return null;
		}
		try {
			String url;
			if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:")) {
				url = src;
			} else {
				var resource = DashboardAnnouncementsView.class.getResource(
						"/ui/dashboard/" + src);
				if (resource == null) {
					return null;
				}
				url = resource.toExternalForm();
			}
			Image image = new Image(url, true);
			ImageView view = new ImageView(image);
			if (image.isError()) {
				return null;
			}
			return view;
		} catch (RuntimeException error) {
			return null;
		}
	}
}
