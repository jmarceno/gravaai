package dev.gravaai.ui.kit;

import org.jspecify.annotations.Nullable;

import java.util.WeakHashMap;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * HeroUI Toast port (plan §5.12), source {@code src/components/ui/toast.tsx} +
 * {@code @heroui/styles/components/toast.css}.
 *
 * <p>In-window stack (bottom end): bg-surface radius 24 px-4 py-3, title text-sm medium and muted
 * description, variant-tinted indicator, 350 ms ease-out-fluid enter, 250 ms exit and 150 ms
 * opacity. Default timeout 4000 ms, danger 8000 ms (from toast.tsx). {@code duration(Infinity)}
 * keeps it open.
 *
 * <pre>{@code
 * Toast.show(rootNode, "Recording saved").description("recording-2026-10-27.mkv").variant(Toast.Variant.SUCCESS);
 * }</pre>
 */
public final class Toast {

	/** Toast variants from toast.tsx ({@code danger} is the error toast). */
	public enum Variant { DEFAULT, ACCENT, SUCCESS, WARNING, DANGER }

	private static final double MAX_WIDTH = 384;
	private static final java.util.Map<KitRoot, VBox> HOSTS = new WeakHashMap<>();

	private final HBox node = new HBox();
	private final VBox content = new VBox();
	private final Label titleLabel = new Label();
	private final Label descriptionLabel = new Label();
	private final VBox host;
	private final PauseTransition timer = new PauseTransition();
	private @Nullable Duration duration;
	private Variant variant = Variant.DEFAULT;
	private String title;
	private String description = "";
	private @Nullable String actionLabel;
	private @Nullable Runnable action;
	private Runnable onDismiss = () -> {};
	private boolean dismissed;

	private Toast(KitRoot root, String title) {
		this.title = title == null ? "" : title;
		this.host = host(root);
		node.getStyleClass().add("toast");
		node.setAlignment(Pos.TOP_LEFT);
		node.setSpacing(6);
		node.setMaxWidth(MAX_WIDTH);
		content.getStyleClass().add("toast__content");
		titleLabel.getStyleClass().add("toast__title");
		titleLabel.setWrapText(true);
		descriptionLabel.getStyleClass().add("toast__description");
		descriptionLabel.setWrapText(true);
		descriptionLabel.setVisible(false);
		descriptionLabel.setManaged(false);
		content.getChildren().addAll(titleLabel, descriptionLabel);
		HBox.setHgrow(content, Priority.ALWAYS);
		Button close = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		close.getStyleClass().add("toast__close-button");
		close.icon(Icons.X);
		close.setFocusTraversable(false);
		close.setOnAction(event -> dismiss());
		node.getChildren().addAll(content, close);
		titleLabel.setText(this.title);
	}

	/** Shows a toast in the window that hosts {@code owner}. */
	public static Toast show(Node owner, String title) {
		KitRoot root = Kit.root(owner);
		Toast toast = new Toast(root, title);
		toast.play();
		return toast;
	}

	/** Secondary line under the title. */
	public Toast description(String text) {
		this.description = text == null ? "" : text;
		descriptionLabel.setText(description);
		descriptionLabel.setVisible(!description.isEmpty());
		descriptionLabel.setManaged(!description.isEmpty());
		return this;
	}

	/** Variant: colours the title/indicator and the default duration (danger 8 s). */
	public Toast variant(Variant variant) {
		this.variant = variant == null ? Variant.DEFAULT : variant;
		applyVariant();
		return this;
	}

	/** Explicit timeout; {@code Duration.INDEFINITE} keeps the toast until dismissed. */
	public Toast duration(Duration duration) {
		this.duration = duration;
		return this;
	}

	/** An inline action button (toast.tsx actionProps). */
	public Toast action(String label, Runnable run) {
		this.actionLabel = label;
		this.action = run;
		return this;
	}

	public Toast onDismiss(Runnable listener) {
		this.onDismiss = listener == null ? () -> {} : listener;
		return this;
	}

	private void applyVariant() {
		node.getStyleClass().removeAll("toast--accent", "toast--success", "toast--warning", "toast--danger");
		switch (variant) {
			case ACCENT -> node.getStyleClass().add("toast--accent");
			case SUCCESS -> node.getStyleClass().add("toast--success");
			case WARNING -> node.getStyleClass().add("toast--warning");
			case DANGER -> node.getStyleClass().add("toast--danger");
			case DEFAULT -> { /* default */ }
		}
	}

	private void play() {
		String iconName = switch (variant) {
			case SUCCESS -> Icons.CheckCircleIcon;
			case WARNING -> Icons.WarningCircleIcon;
			case DANGER -> Icons.WarningCircleIcon;
			case ACCENT -> Icons.Info;
			case DEFAULT -> Icons.Info;
		};
		Icon indicator = Icon.of(iconName, 16);
		indicator.getStyleClass().add("toast__indicator");
		node.getChildren().add(0, indicator);
		applyVariant();
		if (actionLabel != null) {
			Button actionButton = new Button(actionLabel, Button.Variant.SECONDARY, Button.Size.SM);
			actionButton.getStyleClass().add("toast__action");
			actionButton.setOnAction(event -> {
				dismiss();
				if (action != null) {
					action.run();
				}
			});
			content.getChildren().add(actionButton);
		}
		host.getChildren().add(node);
		node.setOpacity(0);
		node.setTranslateY(12);
		FadeTransition fade = Motion.fadeIn(node, Motion.TOAST_OPACITY, Motion.EASE_OUT_FLUID);
		TranslateTransition slide = Motion.translateTo(node, 0, 0, Motion.TOAST_ENTER, Motion.EASE_OUT_FLUID);
		ParallelTransition enter = new ParallelTransition(fade, slide);
		enter.setOnFinished(event -> node.setOpacity(1));
		enter.play();
		Duration timeout = duration != null ? duration
				: variant == Variant.DANGER ? Duration.millis(8000) : Duration.millis(4000);
		timer.setOnFinished(event -> dismiss());
		if (timeout != Duration.INDEFINITE) {
			timer.setDuration(timeout);
			timer.play();
		}
	}

	/** Dismisses with the HeroUI exit timing. */
	public void dismiss() {
		if (dismissed) {
			return;
		}
		dismissed = true;
		timer.stop();
		FadeTransition fade = Motion.fadeOut(node, Motion.TOAST_EXIT, Motion.EASE_OUT_FLUID);
		TranslateTransition slide = Motion.translateTo(node, 0, 12, Motion.TOAST_EXIT, Motion.EASE_OUT_FLUID);
		ParallelTransition exit = new ParallelTransition(fade, slide);
		exit.setOnFinished(event -> {
			host.getChildren().remove(node);
			onDismiss.run();
		});
		exit.play();
	}

	/** The toast sub-layer for one window: a bottom-end VBox filling the toast layer. */
	private static VBox host(KitRoot root) {
		VBox stack = HOSTS.get(root);
		if (stack == null) {
			stack = new VBox(12);
			stack.setAlignment(Pos.BOTTOM_RIGHT);
			stack.setPickOnBounds(false);
			stack.setMaxWidth(MAX_WIDTH);
			VBox stackRef = stack;
			Pane layer = root.toastLayer();
			StackPane container = new StackPane(stack);
			container.setAlignment(Pos.BOTTOM_RIGHT);
			container.setPadding(new Insets(16));
			container.setManaged(false);
			container.setPickOnBounds(false);
			container.setMaxWidth(Region.USE_PREF_SIZE);
			container.setMaxHeight(Region.USE_PREF_SIZE);
			layer.getChildren().add(container);
			Runnable layout = () -> container.resizeRelocate(Math.max(0, layer.getWidth() - MAX_WIDTH - 32),
					Math.max(0, layer.getHeight() - Math.max(0, stackRef.getHeight()) - 32), MAX_WIDTH + 32,
					Math.min(layer.getHeight(), Math.max(0, stackRef.getHeight()) + 32));
			layer.widthProperty().addListener((observable, oldValue, newValue) -> layout.run());
			layer.heightProperty().addListener((observable, oldValue, newValue) -> layout.run());
			stackRef.heightProperty().addListener((observable, oldValue, newValue) -> layout.run());
			layout.run();
			HOSTS.put(root, stack);
		}
		return stack;
	}
}
