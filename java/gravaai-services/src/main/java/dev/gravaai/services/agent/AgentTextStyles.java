package dev.gravaai.services.agent;

import dev.gravaai.core.project.AnnotationTextStyle;
import dev.gravaai.services.agent.AgentOpReader.StyleInput;

/** Maps the agent's text-style input onto the core annotation style (defaults from EditorDefaults). */
final class AgentTextStyles {

	private AgentTextStyles() {}

	static AnnotationTextStyle apply(StyleInput input) {
		AnnotationTextStyle style = AnnotationTextStyle.defaultStyle();
		if (input == null) {
			return style;
		}
		double fontSize = input.fontSize() == null ? style.fontSize() : input.fontSize();
		AnnotationTextStyle.FontWeight fontWeight = input.bold() == null ? style.fontWeight()
				: input.bold() ? AnnotationTextStyle.FontWeight.BOLD
						: AnnotationTextStyle.FontWeight.NORMAL;
		AnnotationTextStyle.FontStyle fontStyle = input.italic() == null ? style.fontStyle()
				: input.italic() ? AnnotationTextStyle.FontStyle.ITALIC
						: AnnotationTextStyle.FontStyle.NORMAL;
		String color = input.color() == null ? style.color() : input.color();
		String backgroundColor = input.backgroundColor() == null ? style.backgroundColor()
				: input.backgroundColor();
		AnnotationTextStyle.TextAlign align = input.align() == null ? style.textAlign()
				: switch (input.align()) {
					case "left" -> AnnotationTextStyle.TextAlign.LEFT;
					case "right" -> AnnotationTextStyle.TextAlign.RIGHT;
					default -> AnnotationTextStyle.TextAlign.CENTER;
				};
		String fontFamily = input.fontFamily() == null ? style.fontFamily() : input.fontFamily();
		return new AnnotationTextStyle(color, backgroundColor, fontSize, fontFamily, fontWeight,
				fontStyle, style.textDecoration(), align, style.borderRadius());
	}
}
