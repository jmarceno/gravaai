package dev.gravaai.ui.editor.captions;

import org.jspecify.annotations.Nullable;

import java.util.List;

import dev.gravaai.core.project.AutoCaptionSettings;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionStyle;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Live caption style preview (the style half of P2.8): renders the active cue with the current
 * {@link AutoCaptionSettings} using the core {@link CaptionStyle} sizing rules. The inspector
 * Captions section (editor shell) embeds this and calls {@link #setSettings(AutoCaptionSettings)}
 * whenever the user changes font size, width, colours, radius, offset or background opacity.
 */
public final class CaptionStylePreview extends Pane {

	private final Label caption = new Label();
	private AutoCaptionSettings settings = AutoCaptionSettings.defaults();
	private List<CaptionCue> cues = List.of();
	private double currentTimeMs;
	private String activeText = "";

	public CaptionStylePreview() {
		getStyleClass().add("caption-style-preview");
		setStyle("-fx-background-color: #0b0d11; -fx-background-radius: 12;");
		setPrefSize(560, 260);
		setMinSize(240, 140);
		caption.setWrapText(true);
		caption.setTextAlignment(TextAlignment.CENTER);
		caption.setAlignment(Pos.CENTER);
		caption.setVisible(false);
		getChildren().add(caption);
	}

	/** Replaces the caption settings and repaints the preview. */
	public void setSettings(AutoCaptionSettings next) {
		this.settings = next == null ? AutoCaptionSettings.defaults() : next;
		apply();
	}

	/** Replaces the cues shown by the preview. */
	public void setCues(List<CaptionCue> next) {
		this.cues = next == null ? List.of() : List.copyOf(next);
		apply();
	}

	/** Moves the preview playhead; the active cues are joined into one caption block. */
	public void setCurrentTimeMs(double timeMs) {
		this.currentTimeMs = timeMs;
		apply();
	}

	/** The text currently visible (empty outside every cue). */
	public String activeText() {
		return activeText;
	}

	public @Nullable AutoCaptionSettings settings() {
		return settings;
	}

	/** Recomputes the visual state without changing inputs (called after a resize). */
	public void refresh() {
		apply();
	}

	@Override
	protected void layoutChildren() {
		if (caption.isVisible()) {
			double width = Math.max(1, getWidth());
			double available = Math.max(40,
					CaptionStyle.getCaptionTargetWidth(width, settings.maxWidth())
							- 2 * CaptionStyle.getCaptionPadding(caption.getFont().getSize()).x());
			caption.setMaxWidth(available);
			caption.autosize();
			double boxWidth = Math.min(available, caption.prefWidth(-1));
			double boxHeight = caption.prefHeight(boxWidth);
			double x = (width - boxWidth) / 2;
			double bottom = getHeight() * (settings.bottomOffset() / 100.0);
			double y = Math.max(0, getHeight() - bottom - boxHeight);
			caption.resizeRelocate(x, y, boxWidth, boxHeight);
		}
	}

	private void apply() {
		StringBuilder text = new StringBuilder();
		for (CaptionCue cue : cues) {
			if (currentTimeMs >= cue.startMs() && currentTimeMs <= cue.endMs()
					&& cue.text() != null && !cue.text().isBlank()) {
				if (text.length() > 0) {
					text.append(' ');
				}
				text.append(cue.text().trim());
			}
		}
		activeText = text.toString();
		caption.setVisible(!activeText.isEmpty());
		caption.setText(activeText);
		if (activeText.isEmpty()) {
			requestLayout();
			return;
		}

		double width = Math.max(1, getWidth());
		double fontSize = CaptionStyle.getCaptionScaledFontSize(settings.fontSize(), width,
				settings.maxWidth());
		CaptionStyle.Padding padding = CaptionStyle.getCaptionPadding(fontSize);
		double radius = CaptionStyle.getCaptionScaledRadius(settings.boxRadius(), fontSize);
		caption.setFont(Font.font(fontFamily(settings.fontFamily()), fontSize));
		caption.setTextFill(parseColor(settings.textColor(), Color.WHITE));
		caption.setPadding(new Insets(padding.y(), padding.x(), padding.y(), padding.x()));
		caption.setStyle("-fx-background-color: rgba(0,0,0," + settings.backgroundOpacity()
				+ "); -fx-background-radius: " + radius + ";");
		requestLayout();
	}

	private static @Nullable String fontFamily(String cssFamilyList) {
		if (cssFamilyList == null || cssFamilyList.isBlank()) {
			return null;
		}
		String first = cssFamilyList.split(",")[0].trim().replace("\"", "").replace("'", "");
		return first.isBlank() ? null : first;
	}

	private static Color parseColor(@Nullable String value, Color fallback) {
		try {
			return value == null || value.isBlank() ? fallback : Color.web(value);
		} catch (IllegalArgumentException error) {
			return fallback;
		}
	}
}
