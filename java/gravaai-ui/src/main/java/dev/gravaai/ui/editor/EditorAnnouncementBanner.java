package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icons;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Editor announcement banner (port of {@code EditorAnnouncementBanner.tsx}): shows the first
 * eligible banner announcement, reports one impression per session, advances after
 * {@code displayDurationSeconds} and dismisses on close/action. The bundled feed is empty for this
 * release, so the banner is hidden by default; the app can push a selected list via
 * {@link #show(List)}.
 */
public final class EditorAnnouncementBanner extends HBox {

	private final Consumer<String> onDismiss;
	private final Consumer<String> onImpression;
	private final Consumer<Announcements.Action> onAction;
	private final Set<String> countedThisSession = new HashSet<>();
	private final Label title = new Label();
	private final Label body = new Label();
	private final Button actionButton;
	private final Button closeButton;
	private List<Announcements.Announcement> queue = List.of();
	private @Nullable PauseTransition autoAdvance;

	public EditorAnnouncementBanner() {
		this(Announcements.bundledFeed(), Announcements.Feed.EMPTY, Set.of(), Map.of(), "0.0.0",
				id -> { }, id -> { }, action -> { });
	}

	public EditorAnnouncementBanner(
			Announcements.Feed bundled, Announcements.Feed remote, Set<String> dismissedIds,
			Map<String, Integer> impressionCounts, String appVersion, Consumer<String> onDismiss,
			Consumer<String> onImpression, Consumer<Announcements.Action> onAction) {
		this.onDismiss = onDismiss == null ? id -> { } : onDismiss;
		this.onImpression = onImpression == null ? id -> { } : onImpression;
		this.onAction = onAction == null ? action -> { } : onAction;
		getStyleClass().add("editor-announcement");

		title.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #ffffff;");
		Label dash = new Label("—");
		dash.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(255,255,255,0.65);");
		body.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(255,255,255,0.8);");
		HBox texts = new HBox(6, title, dash, body);
		texts.setAlignment(Pos.CENTER);
		texts.setMinWidth(0);

		actionButton = new Button("", Button.Variant.LINK, Button.Size.SM);
		actionButton.icon(Icons.ArrowRight, "fill");
		actionButton.setGraphicTextGap(4);
		actionButton.setOnAction(event -> {
			Announcements.Announcement current = current();
			if (current != null && current.action() != null) {
				onAction.accept(current.action());
				dismissCurrent();
			}
		});

		closeButton = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		closeButton.icon(Icons.X);
		closeButton.setAccessibleText("Dismiss");
		closeButton.setOnAction(event -> dismissCurrent());

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		getChildren().addAll(texts, spacer, actionButton, closeButton);
		setAlignment(Pos.CENTER);
		setSpacing(12);
		setPadding(new Insets(6, 48, 6, 48));
		setStyle("-fx-background-color: #000000; -fx-border-color: transparent transparent "
				+ "#000000 transparent; -fx-border-width: 0 0 1 0;");

		show(Announcements.selectAnnouncements(bundled.announcements(),
				remote == null ? List.of() : remote.announcements(), dismissedIds,
				impressionCounts, appVersion, Announcements.Audience.EDITOR,
				java.time.Instant.now()));
	}

	/** Replaces the visible queue (already selected/sorted by the caller). */
	public void show(List<Announcements.Announcement> announcements) {
		this.queue = announcements.stream()
				.filter(announcement -> announcement.presentation()
						== Announcements.Presentation.BANNER)
				.toList();
		render();
	}

	/** The announcement currently shown, or null. */
	public Announcements.@Nullable Announcement current() {
		return queue.isEmpty() ? null : queue.get(0);
	}

	private void render() {
		Announcements.@Nullable Announcement current = current();
		setVisible(current != null);
		setManaged(current != null);
		if (autoAdvance != null) {
			autoAdvance.stop();
			autoAdvance = null;
		}
		if (current == null) {
			return;
		}

		title.setText(current.title());
		body.setText(current.body());
		boolean showAction = current.action() != null && (current.controls() == null
				|| !Boolean.FALSE.equals(current.controls().action()));
		actionButton.setVisible(showAction);
		actionButton.setManaged(showAction);
		Announcements.@Nullable Action action = current.action();
		if (showAction && action != null) {
			actionButton.setText(actionLabel(action));
		}
		boolean showClose = current.controls() == null
				|| !Boolean.FALSE.equals(current.controls().close());
		closeButton.setVisible(showClose);
		closeButton.setManaged(showClose);

		if (countedThisSession.add(current.id())) {
			onImpression.accept(current.id());
		}
		if (current.displayDurationSeconds() != null) {
			autoAdvance = new PauseTransition(Duration.seconds(current.displayDurationSeconds()));
			autoAdvance.setOnFinished(event -> advance());
			autoAdvance.play();
		}
	}

	private void dismissCurrent() {
		Announcements.Announcement current = current();
		if (current != null) {
			onDismiss.accept(current.id());
			advance();
		}
	}

	private void advance() {
		queue = queue.isEmpty() ? List.of() : List.copyOf(queue.subList(1, queue.size()));
		render();
	}

	private static String actionLabel(Announcements.Action action) {
		return action instanceof Announcements.UrlAction url ? url.label()
				: ((Announcements.SectionAction) action).label();
	}
}
