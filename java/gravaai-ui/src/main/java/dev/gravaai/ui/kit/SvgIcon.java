package dev.gravaai.ui.kit;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Parses the SVG files produced by {@code java/tools/extract-icons.mjs} into a small immutable
 * model. Pure logic (no scene graph), so it is unit-tested without a JavaFX toolkit; {@link Icon}
 * turns the model into {@code SVGPath} nodes.
 *
 * <p>Supported attributes: {@code fill}, {@code stroke}, {@code stroke-width}, {@code fill-rule},
 * {@code stroke-linecap}, {@code stroke-linejoin}, {@code opacity}, {@code fill-opacity},
 * {@code stroke-opacity} and {@code transform} ({@code translate}, {@code scale}, {@code rotate},
 * {@code matrix}). Shapes: {@code path}, {@code circle}, {@code ellipse}, {@code rect},
 * {@code line}, {@code polygon}, {@code polyline}; groups are traversed with inherited paint.
 */
public final class SvgIcon {

	/** How a shape's fill/stroke is resolved. */
	public enum PaintKind { NONE, CURRENT_COLOR, COLOR }

	/** Immutable paint description of one shape. */
	public record SvgPaint(PaintKind fillKind, Color fillColor, PaintKind strokeKind, Color strokeColor,
			double strokeWidth, StrokeLineCap lineCap, StrokeLineJoin lineJoin, boolean evenOdd,
			double opacity) {}

	/** One transform function, in document order. */
	public record SvgTransform(String function, double[] parameters) {}

	/** One drawable shape with its already-converted SVG path data. */
	public record SvgShape(String element, String pathData, SvgPaint paint,
			List<SvgTransform> transforms) {}

	/** A parsed icon: view box plus shapes. */
	public record Parsed(double minX, double minY, double width, double height, List<SvgShape> shapes) {}

	private static final SvgPaint DEFAULT_PAINT = new SvgPaint(PaintKind.COLOR, Color.BLACK,
			PaintKind.NONE, null, 1, StrokeLineCap.BUTT, StrokeLineJoin.MITER, false, 1);

	private SvgIcon() {}

	/** Parses SVG from a class-path style stream; the caller closes it. */
	public static Parsed parse(InputStream in) throws IOException {
		try {
			return parse(parseDocument(new InputSource(in)));
		} catch (SAXException error) {
			throw new IOException("Could not parse SVG: " + error.getMessage(), error);
		}
	}

	/** Parses SVG from a string. */
	public static Parsed parse(String xml) throws IOException {
		try {
			return parse(parseDocument(new InputSource(new StringReader(xml))));
		} catch (SAXException error) {
			throw new IOException("Could not parse SVG: " + error.getMessage(), error);
		}
	}

	/** One hardened factory for every icon parse; a DocumentBuilder per thread. */
	private static final DocumentBuilderFactory FACTORY = createFactory();
	private static final ThreadLocal<DocumentBuilder> BUILDERS = ThreadLocal.withInitial(SvgIcon::newBuilder);

	private static DocumentBuilderFactory createFactory() {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			factory.setNamespaceAware(false);
			return factory;
		} catch (ParserConfigurationException error) {
			throw new IllegalStateException("Could not configure the SVG parser", error);
		}
	}

	private static DocumentBuilder newBuilder() {
		try {
			return FACTORY.newDocumentBuilder();
		} catch (ParserConfigurationException error) {
			throw new IllegalStateException("Could not create the SVG parser", error);
		}
	}

	private static Document parseDocument(InputSource source) throws SAXException, IOException {
		DocumentBuilder builder = BUILDERS.get();
		builder.reset();
		return builder.parse(source);
	}

	private static Parsed parse(Document document) {
		Element root = document.getDocumentElement();
		if (root == null || !root.getTagName().equalsIgnoreCase("svg")) {
			throw new IllegalArgumentException("Not an SVG document: " + (root == null ? "null" : root.getTagName()));
		}
		double[] viewBox = parseViewBox(root);
		List<SvgShape> shapes = new ArrayList<>();
		SvgPaint rootPaint = paintOf(DEFAULT_PAINT, root);
		collect(root, rootPaint, List.of(), shapes);
		return new Parsed(viewBox[0], viewBox[1], viewBox[2], viewBox[3], List.copyOf(shapes));
	}

	private static double[] parseViewBox(Element root) {
		String viewBox = attr(root, "viewBox");
		if (viewBox != null) {
			String[] parts = viewBox.trim().split("[\\s,]+");
			if (parts.length == 4) {
				return new double[] {number(parts[0]), number(parts[1]), number(parts[2]), number(parts[3])};
			}
		}
		return new double[] {0, 0, number(attrOr(root, "width", "24")), number(attrOr(root, "height", "24"))};
	}

	private static void collect(Element parent, SvgPaint inheritedPaint, List<SvgTransform> inheritedTransforms,
			List<SvgShape> out) {
		NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node node = children.item(i);
			if (node.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			Element element = (Element) node;
			String tag = element.getTagName().toLowerCase(Locale.ROOT);
			SvgPaint paint = paintOf(inheritedPaint, element);
			List<SvgTransform> transforms = new ArrayList<>(inheritedTransforms);
			transforms.addAll(parseTransforms(attr(element, "transform")));
			switch (tag) {
				case "g" -> collect(element, paint, transforms, out);
				case "defs" -> { /* not used by the extracted icons */ }
				case "svg" -> collect(element, paint, transforms, out);
				default -> {
					String pathData = pathData(tag, element);
					if (pathData != null) {
						out.add(new SvgShape(tag, pathData, paint, List.copyOf(transforms)));
					}
				}
			}
		}
	}

	/** Converts an SVG shape element to SVG path data; returns null when the shape is unusable. */
	public static String pathData(String element, Element shape) {
		switch (element) {
			case "path" -> {
				String d = attr(shape, "d");
				return d == null || d.isBlank() ? null : d;
			}
			case "circle" -> {
				double cx = number(attrOr(shape, "cx", "0"));
				double cy = number(attrOr(shape, "cy", "0"));
				double r = number(attrOr(shape, "r", "0"));
				return r <= 0 ? null : circlePath(cx, cy, r);
			}
			case "ellipse" -> {
				double cx = number(attrOr(shape, "cx", "0"));
				double cy = number(attrOr(shape, "cy", "0"));
				double rx = number(attrOr(shape, "rx", "0"));
				double ry = number(attrOr(shape, "ry", "0"));
				return rx <= 0 || ry <= 0 ? null : ellipsePath(cx, cy, rx, ry);
			}
			case "rect" -> {
				double x = number(attrOr(shape, "x", "0"));
				double y = number(attrOr(shape, "y", "0"));
				double w = number(attrOr(shape, "width", "0"));
				double h = number(attrOr(shape, "height", "0"));
				double rx = number(attrOr(shape, "rx", "0"));
				double ry = number(attrOr(shape, "ry", "0"));
				return w <= 0 || h <= 0 ? null : rectPath(x, y, w, h, rx, ry);
			}
			case "line" -> {
				double x1 = number(attrOr(shape, "x1", "0"));
				double y1 = number(attrOr(shape, "y1", "0"));
				double x2 = number(attrOr(shape, "x2", "0"));
				double y2 = number(attrOr(shape, "y2", "0"));
				return "M" + fmt(x1) + " " + fmt(y1) + "L" + fmt(x2) + " " + fmt(y2);
			}
			case "polygon", "polyline" -> {
				String points = attr(shape, "points");
				if (points == null || points.isBlank()) {
					return null;
				}
				String[] values = points.trim().split("[\\s,]+");
				if (values.length < 4) {
					return null;
				}
				StringBuilder d = new StringBuilder("M").append(fmt(number(values[0]))).append(" ")
						.append(fmt(number(values[1])));
				for (int i = 2; i + 1 < values.length; i += 2) {
					d.append("L").append(fmt(number(values[i]))).append(" ").append(fmt(number(values[i + 1])));
				}
				if (element.equals("polygon")) {
					d.append("Z");
				}
				return d.toString();
			}
			default -> {
				return null;
			}
		}
	}

	private static String circlePath(double cx, double cy, double r) {
		// Two half-circle arcs; exactly what a circle is in path data.
		return "M" + fmt(cx - r) + " " + fmt(cy)
				+ "a" + fmt(r) + " " + fmt(r) + " 0 1 0 " + fmt(2 * r) + " 0"
				+ "a" + fmt(r) + " " + fmt(r) + " 0 1 0 " + fmt(-2 * r) + " 0Z";
	}

	private static String ellipsePath(double cx, double cy, double rx, double ry) {
		return "M" + fmt(cx - rx) + " " + fmt(cy)
				+ "a" + fmt(rx) + " " + fmt(ry) + " 0 1 0 " + fmt(2 * rx) + " 0"
				+ "a" + fmt(rx) + " " + fmt(ry) + " 0 1 0 " + fmt(-2 * rx) + " 0Z";
	}

	private static String rectPath(double x, double y, double w, double h, double rx, double ry) {
		double radius = Math.min(Math.max(rx, ry), Math.min(w, h) / 2);
		if (radius <= 0) {
			return "M" + fmt(x) + " " + fmt(y) + "h" + fmt(w) + "v" + fmt(h) + "h" + fmt(-w) + "Z";
		}
		double r = radius;
		return "M" + fmt(x + r) + " " + fmt(y)
				+ "h" + fmt(w - 2 * r)
				+ "a" + fmt(r) + " " + fmt(r) + " 0 0 1 " + fmt(r) + " " + fmt(r)
				+ "v" + fmt(h - 2 * r)
				+ "a" + fmt(r) + " " + fmt(r) + " 0 0 1 " + fmt(-r) + " " + fmt(r)
				+ "h" + fmt(-(w - 2 * r))
				+ "a" + fmt(r) + " " + fmt(r) + " 0 0 1 " + fmt(-r) + " " + fmt(-r)
				+ "v" + fmt(-(h - 2 * r))
				+ "a" + fmt(r) + " " + fmt(r) + " 0 0 1 " + fmt(r) + " " + fmt(-r) + "Z";
	}

	private static SvgPaint paintOf(SvgPaint inherited, Element element) {
		PaintKind fillKind = inherited.fillKind();
		Color fillColor = inherited.fillColor();
		String fill = attr(element, "fill");
		if (fill != null) {
			PaintValue value = paintValue(fill);
			fillKind = value.kind();
			fillColor = value.color();
		}
		PaintKind strokeKind = inherited.strokeKind();
		Color strokeColor = inherited.strokeColor();
		String stroke = attr(element, "stroke");
		if (stroke != null) {
			PaintValue value = paintValue(stroke);
			strokeKind = value.kind();
			strokeColor = value.color();
		}
		double strokeWidth = inherited.strokeWidth();
		String width = attr(element, "stroke-width");
		if (width != null) {
			strokeWidth = length(width);
		}
		StrokeLineCap cap = inherited.lineCap();
		String lineCap = attr(element, "stroke-linecap");
		if (lineCap != null) {
			cap = switch (lineCap.toLowerCase(Locale.ROOT)) {
				case "round" -> StrokeLineCap.ROUND;
				case "square" -> StrokeLineCap.SQUARE;
				default -> StrokeLineCap.BUTT;
			};
		}
		StrokeLineJoin join = inherited.lineJoin();
		String lineJoin = attr(element, "stroke-linejoin");
		if (lineJoin != null) {
			join = switch (lineJoin.toLowerCase(Locale.ROOT)) {
				case "round" -> StrokeLineJoin.ROUND;
				case "bevel" -> StrokeLineJoin.BEVEL;
				default -> StrokeLineJoin.MITER;
			};
		}
		boolean evenOdd = inherited.evenOdd();
		String fillRule = attr(element, "fill-rule");
		if (fillRule != null) {
			evenOdd = fillRule.equalsIgnoreCase("evenodd");
		}
		double opacity = inherited.opacity();
		String ownOpacity = attr(element, "opacity");
		if (ownOpacity != null) {
			opacity = number(ownOpacity);
		}
		String fillOpacity = attr(element, "fill-opacity");
		if (fillOpacity != null && fillKind == PaintKind.COLOR) {
			fillColor = withAlpha(fillColor, fillColor.getOpacity() * number(fillOpacity));
		}
		String strokeOpacity = attr(element, "stroke-opacity");
		if (strokeOpacity != null && strokeKind == PaintKind.COLOR) {
			strokeColor = withAlpha(strokeColor, strokeColor.getOpacity() * number(strokeOpacity));
		}
		return new SvgPaint(fillKind, fillColor, strokeKind, strokeColor, strokeWidth, cap, join, evenOdd,
				opacity);
	}

	/** A parsed paint value: kind plus explicit colour (null for currentColor/none). */
	public record PaintValue(PaintKind kind, Color color) {}

	/** SVG paint value: currentColor, none, #hex, rgb()/rgba(), or a var() with fallback. */
	public static PaintValue paintValue(String raw) {
		String value = raw.trim();
		if (value.startsWith("var(")) {
			int comma = value.lastIndexOf(',');
			int end = value.lastIndexOf(')');
			if (comma >= 0 && end > comma) {
				return paintValue(value.substring(comma + 1, end).trim());
			}
		}
		if (value.equalsIgnoreCase("currentColor")) {
			return new PaintValue(PaintKind.CURRENT_COLOR, null);
		}
		if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("transparent")) {
			return new PaintValue(PaintKind.NONE, null);
		}
		if (value.startsWith("#") || value.startsWith("rgb")) {
			return new PaintValue(PaintKind.COLOR, Color.web(value));
		}
		return new PaintValue(PaintKind.COLOR, Color.BLACK);
	}

	private static Color withAlpha(Color color, double alpha) {
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(1, alpha)));
	}

	/** All transform functions in a {@code transform="..."} attribute, in order. */
	public static List<SvgTransform> parseTransforms(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		List<SvgTransform> out = new ArrayList<>();
		int index = 0;
		while (index < raw.length()) {
			int open = raw.indexOf('(', index);
			if (open < 0) {
				break;
			}
			int close = raw.indexOf(')', open);
			if (close < 0) {
				break;
			}
			String function = raw.substring(index, open).trim().toLowerCase(Locale.ROOT);
			String[] parts = raw.substring(open + 1, close).trim().split("[\\s,]+");
			double[] values = new double[parts.length];
			for (int i = 0; i < parts.length; i++) {
				values[i] = number(parts[i]);
			}
			if (!function.isEmpty()) {
				out.add(new SvgTransform(function, values));
			}
			index = close + 1;
		}
		return out;
	}

	/** {@code stroke-width} style length: plain number, px, or a CSS var with a fallback. */
	public static double length(String raw) {
		String value = raw.trim();
		if (value.startsWith("var(")) {
			int comma = value.lastIndexOf(',');
			int end = value.lastIndexOf(')');
			if (comma >= 0 && end > comma) {
				return length(value.substring(comma + 1, end).trim());
			}
		}
		if (value.endsWith("px")) {
			value = value.substring(0, value.length() - 2);
		}
		return number(value);
	}

	private static double number(String raw) {
		String value = raw.trim();
		if (value.isEmpty()) {
			return 0;
		}
		if (value.endsWith("px")) {
			value = value.substring(0, value.length() - 2);
		}
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException error) {
			return 0;
		}
	}

	/** Formats a coordinate the way SVG path data expects: no trailing zeros. */
	static String fmt(double value) {
		if (value == Math.rint(value)) {
			return Long.toString(Math.round(value));
		}
		String text = String.format(Locale.ROOT, "%.4f", value);
		while (text.endsWith("0")) {
			text = text.substring(0, text.length() - 1);
		}
		return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
	}

	private static String attr(Element element, String name) {
		return element.hasAttribute(name) ? element.getAttribute(name) : null;
	}

	private static String attrOr(Element element, String name, String fallback) {
		String value = attr(element, name);
		return value == null || value.isBlank() ? fallback : value;
	}
}
