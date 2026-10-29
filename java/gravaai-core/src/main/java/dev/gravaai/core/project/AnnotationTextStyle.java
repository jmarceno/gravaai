package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Text style of an annotation; the font weight/style/decoration/alignment unions become enums. */
public record AnnotationTextStyle(
		String color,
		String backgroundColor,
		double fontSize,
		String fontFamily,
		FontWeight fontWeight,
		FontStyle fontStyle,
		TextDecoration textDecoration,
		TextAlign textAlign,
		double borderRadius) {

	public enum FontWeight {
		NORMAL("normal"), BOLD("bold");

		private final String wire;

		FontWeight(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		@JsonCreator
		public static FontWeight fromWire(String value) {
			return "bold".equals(value) ? BOLD : NORMAL;
		}
	}

	public enum FontStyle {
		NORMAL("normal"), ITALIC("italic");

		private final String wire;

		FontStyle(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		@JsonCreator
		public static FontStyle fromWire(String value) {
			return "italic".equals(value) ? ITALIC : NORMAL;
		}
	}

	public enum TextDecoration {
		NONE("none"), UNDERLINE("underline");

		private final String wire;

		TextDecoration(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		@JsonCreator
		public static TextDecoration fromWire(String value) {
			return "underline".equals(value) ? UNDERLINE : NONE;
		}
	}

	public enum TextAlign {
		LEFT("left"), CENTER("center"), RIGHT("right");

		private final String wire;

		TextAlign(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		@JsonCreator
		public static TextAlign fromWire(String value) {
			return "left".equals(value) ? LEFT : "right".equals(value) ? RIGHT : CENTER;
		}
	}

	/** {@code DEFAULT_ANNOTATION_STYLE}. */
	public static AnnotationTextStyle defaultStyle() {
		return new AnnotationTextStyle("#ffffff", "transparent", 32, defaultAnnotationFontFamily(),
				FontWeight.BOLD, FontStyle.NORMAL, TextDecoration.NONE, TextAlign.CENTER, 8);
	}

	/** {@code getDefaultAnnotationFontFamily}. */
	public static String defaultAnnotationFontFamily() {
		return "\"SF Pro Display\", \"SF Pro Text\", \"Helvetica Neue\", sans-serif";
	}
}
