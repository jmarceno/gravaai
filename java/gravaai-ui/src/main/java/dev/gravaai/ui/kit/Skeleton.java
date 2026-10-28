package dev.gravaai.ui.kit;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/**
 * HeroUI Skeleton port (plan §5.12), source {@code src/components/ui/skeleton.tsx} +
 * {@code @heroui/styles/components/skeleton.css}.
 *
 * <p>{@code bg-surface-tertiary/70}, {@code rounded-sm} (4 px) with a shimmer overlay: a gradient
 * sweeping from -100% to 200% over 2 s linear, infinite ({@code @keyframes skeleton}).
 */
public final class Skeleton extends Region {

	private final Region shimmer = new Region();
	private final TranslateTransition animation;

	public Skeleton() {
		this(160, 16);
	}

	public Skeleton(double width, double height) {
		getStyleClass().add("skeleton");
		setMinSize(width, height);
		setPrefSize(width, height);
		setMaxSize(width, height);
		setMouseTransparent(true);

		shimmer.getStyleClass().add("skeleton__shimmer");
		shimmer.setMouseTransparent(true);
		getChildren().add(shimmer);

		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(widthProperty());
		clip.heightProperty().bind(heightProperty());
		setClip(clip);

		animation = new TranslateTransition(Motion.SKELETON_SHIMMER, shimmer);
		animation.setFromX(-1);
		animation.setToX(2);
		animation.setInterpolator(Motion.LINEAR);
		animation.setCycleCount(Animation.INDEFINITE);
		animation.play();
	}

	/** Stops the shimmer (screens stop it when the skeleton leaves the view). */
	public void stop() {
		animation.stop();
	}

	@Override
	protected void layoutChildren() {
		// The shimmer is one skeleton-width wide and travels -100% -> 200% via translateX, so its
		// layout box is the skeleton itself; the translate is relative to the node width.
		double width = getWidth();
		double height = getHeight();
		shimmer.resizeRelocate(0, 0, width, height);
	}

	/** HeroUI {@code --pulse} variant: opacity 1 -> 0.5 -> 1 over 2 s (Tailwind animate-pulse). */
	public Skeleton pulse() {
		animation.stop();
		getStyleClass().add("skeleton--pulse");
		FadeTransition fade = new FadeTransition(javafx.util.Duration.millis(1000), this);
		fade.setFromValue(1);
		fade.setToValue(0.5);
		fade.setInterpolator(Motion.EASE_IN_OUT_QUAD);
		fade.setAutoReverse(true);
		fade.setCycleCount(Animation.INDEFINITE);
		fade.play();
		return this;
	}
}
