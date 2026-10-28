package dev.gravaai.ui.kit;

import java.util.function.DoublePredicate;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/**
 * Port of {@code src/components/launch/MarqueeText.tsx} + {@code MarqueeText.css} (plan §5.12).
 *
 * <p>Static ellipsised text by default. When the text overflows and the pointer is over the
 * control, the static label hides and a duplicated track scrolls from 0 to -50% over 10 s, linear,
 * infinite (the CSS {@code source-selector-marquee} animation). With reduced motion JavaFX still
 * animates; {@link #setDuration} lets a screen honour a reduced-motion preference.
 */
public final class MarqueeText extends Region {

	private final Label staticLabel = new Label();
	private final Pane animatedLayer = new Pane();
	private final HBox track = new HBox();
	private final Label first = new Label();
	private final Label duplicate = new Label();
	private final TranslateTransition animation;
	private boolean overflowing;

	public MarqueeText(String text) {
		getStyleClass().add("source-selector-marquee");
		setMinHeight(20);
		setPrefHeight(20);
		setMaxHeight(20);

		staticLabel.getStyleClass().add("source-selector-marquee-static");
		staticLabel.setText(text);
		staticLabel.setMaxWidth(Double.MAX_VALUE);
		staticLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
		staticLabel.setMinWidth(0);

		animatedLayer.getStyleClass().add("source-selector-marquee-animated");
		track.getStyleClass().add("source-selector-marquee-track");
		first.getStyleClass().add("source-selector-marquee-segment");
		duplicate.getStyleClass().addAll("source-selector-marquee-segment", "source-selector-marquee-duplicate");
		first.setText(text);
		duplicate.setText(text);
		track.getChildren().addAll(first, duplicate);
		track.setAlignment(Pos.CENTER_LEFT);
		animatedLayer.getChildren().add(track);
		animatedLayer.setMouseTransparent(true);
		animatedLayer.setOpacity(0);

		getChildren().addAll(staticLabel, animatedLayer);
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(widthProperty());
		clip.heightProperty().bind(heightProperty());
		setClip(clip);

		animation = new TranslateTransition(Motion.MARQUEE, track);
		animation.setInterpolator(Motion.LINEAR);
		animation.setCycleCount(Animation.INDEFINITE);

		setOnMouseEntered(event -> startMarquee());
		setOnMouseExited(event -> {
			animation.pause();
			animatedLayer.setOpacity(0);
		});
		widthProperty().addListener((observable, oldValue, newValue) -> requestLayout());
	}

	/** True when the text is wider than the available width + 1 px (the TSX check). */
	public static boolean overflows(double textWidth, double availableWidth) {
		return textWidth > availableWidth + 1;
	}

	private void startMarquee() {
		if (!overflowing) {
			return;
		}
		animatedLayer.setOpacity(1);
		double trackWidth = track.prefWidth(-1);
		if (trackWidth <= 0) {
			return;
		}
		animation.stop();
		animation.setFromX(0);
		animation.setToX(-trackWidth / 2);
		animation.play();
	}

	/** Overrides the 10 s scroll duration (used by reduced-motion preferences). */
	public void setDuration(javafx.util.Duration duration) {
		animation.setDuration(duration);
	}

	/** The displayed text. */
	public String text() {
		return staticLabel.getText();
	}

	public void setText(String text) {
		staticLabel.setText(text);
		first.setText(text);
		duplicate.setText(text);
		requestLayout();
	}

	@Override
	protected void layoutChildren() {
		double width = getWidth();
		double height = getHeight();
		staticLabel.resizeRelocate(0, 0, width, height);
		animatedLayer.resizeRelocate(0, 0, width, height);
		staticLabel.applyCss();
		double textWidth = staticLabel.prefWidth(-1);
		overflowing = overflows(textWidth, width);
		Kit.setClass(this, "source-selector-marquee--overflowing", overflowing);
		if (!overflowing) {
			animatedLayer.setOpacity(0);
			animation.pause();
		}
		// Keep the duplicated track at least as wide as the viewport so the loop never shows a gap.
		double trackWidth = Math.max(width * 2, track.prefWidth(-1));
		track.resizeRelocate(0, 0, trackWidth, height);
	}
}
