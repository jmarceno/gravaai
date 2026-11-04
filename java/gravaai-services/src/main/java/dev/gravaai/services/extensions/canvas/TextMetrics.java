package dev.gravaai.services.extensions.canvas;

import org.graalvm.polyglot.HostAccess;

/**
 * The result of {@code ctx.measureText(text)}: the browser {@code TextMetrics} fields components
 * need for layout and centring. Bounding-box values are distances from the drawing point, positive
 * leftwards/upwards/rightwards/downwards as in the HTML spec, for the current
 * {@code textAlign}/{@code textBaseline}.
 */
public final class TextMetrics {

	private final double width;
	private final double actualBoundingBoxLeft;
	private final double actualBoundingBoxRight;
	private final double actualBoundingBoxAscent;
	private final double actualBoundingBoxDescent;
	private final double fontBoundingBoxAscent;
	private final double fontBoundingBoxDescent;

	TextMetrics(double width, double left, double right, double ascent, double descent,
			double fontAscent, double fontDescent) {
		this.width = width;
		this.actualBoundingBoxLeft = left;
		this.actualBoundingBoxRight = right;
		this.actualBoundingBoxAscent = ascent;
		this.actualBoundingBoxDescent = descent;
		this.fontBoundingBoxAscent = fontAscent;
		this.fontBoundingBoxDescent = fontDescent;
	}

	@HostAccess.Export
	public double getWidth() {
		return width;
	}

	@HostAccess.Export
	public double getActualBoundingBoxLeft() {
		return actualBoundingBoxLeft;
	}

	@HostAccess.Export
	public double getActualBoundingBoxRight() {
		return actualBoundingBoxRight;
	}

	@HostAccess.Export
	public double getActualBoundingBoxAscent() {
		return actualBoundingBoxAscent;
	}

	@HostAccess.Export
	public double getActualBoundingBoxDescent() {
		return actualBoundingBoxDescent;
	}

	@HostAccess.Export
	public double getFontBoundingBoxAscent() {
		return fontBoundingBoxAscent;
	}

	@HostAccess.Export
	public double getFontBoundingBoxDescent() {
		return fontBoundingBoxDescent;
	}
}
