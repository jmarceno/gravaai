package dev.gravaai.ui.kit;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

/**
 * SVG icon control (plan §5.12). Loads the files extracted by {@code java/tools/extract-icons.mjs}
 * and builds a {@link Group} of {@link SVGPath}s from them; icons are never hand-drawn.
 *
 * <p>Naming: {@code Icon.of("phosphor/Microphone", 16)} uses the phosphor regular weight;
 * {@code Icon.of("solar/add", 16, "bold")} uses solar's bold set. The extra weight segment is
 * optional: {@code "solar/bold/add"} works too. Weights:
 * <ul>
 *   <li>solar: {@code linear} (default), {@code bold} (also accepted: {@code fill}, {@code bold})
 *   <li>phosphor: {@code regular} (default), {@code thin}, {@code light}, {@code bold},
 *       {@code fill}, {@code duotone}
 *   <li>react-icons: no weights
 * </ul>
 *
 * <p>The colour comes from the looked-up colour {@code -g-icon-color}, which the parent control
 * sets (a primary Button sets it to {@code -g-accent-foreground}). {@link #setIconColor(Color)}
 * overrides that for icons that need a code-supplied colour.
 */
public final class Icon extends javafx.scene.layout.Region {

	/** Default icon size: Tailwind {@code size-4} (16 px), the HeroUI desktop button icon size. */
	public static final double DEFAULT_SIZE = 16;

	private static final Map<String, SvgIcon.Parsed> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> PHOSPHOR_WEIGHTS =
			Set.of("regular", "thin", "light", "bold", "fill", "duotone");

	private final Group group = new Group();
	private final List<ColoredPath> coloredPaths = new ArrayList<>();
	private final double size;

	private Icon(SvgIcon.Parsed parsed, double size) {
		this.size = size;
		getStyleClass().add("icon");
		setPickOnBounds(false);
		setMouseTransparent(true);
		getChildren().add(group);
		group.setMouseTransparent(true);
		for (SvgIcon.SvgShape shape : parsed.shapes()) {
			group.getChildren().add(buildPath(shape));
		}
		applyTransform(parsed);
	}

	/** Loads an icon at the default 16 px size (regular weight). */
	public static Icon of(String name) {
		return of(name, DEFAULT_SIZE);
	}

	/** Loads an icon at {@code size} px (regular weight). */
	public static Icon of(String name, double size) {
		return of(name, size, "");
	}

	/** Loads an icon at {@code size} px with a library weight name. */
	public static Icon of(String name, double size, String weight) {
		String path = resolveResource(name, weight);
		SvgIcon.Parsed parsed = CACHE.computeIfAbsent(path, Icon::load);
		return new Icon(parsed, size);
	}

	/**
	 * Parses and caches an icon SVG without building scene nodes. Safe on any thread, so the HUD
	 * can prewarm its icon set on a background thread before the bar is built (startup budget).
	 */
	public static void prewarm(String name, String weight) {
		String path = resolveResource(name, weight);
		CACHE.computeIfAbsent(path, Icon::load);
	}

	/** True when the icon resource exists (used by the showcase and tests). */
	public static boolean exists(String name) {
		return Icon.class.getResource(resourcePath(name, "")) != null;
	}

	/** The effective resource path for a name and weight, for diagnostics. */
	public static String resolveResource(String name, String weight) {
		return resourcePath(name, weight);
	}

	private static String resourcePath(String name, String weight) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Icon name must not be blank");
		}
		String[] parts = name.split("/");
		if (parts.length < 2) {
			throw new IllegalArgumentException(
					"Icon name must be \"<lib>/<Name>\" or \"<lib>/<weight>/<Name>\", got: " + name);
		}
		String lib = parts[0];
		String path;
		if (parts.length >= 3) {
			path = lib + "/" + parts[1] + "/" + parts[2];
		} else {
			String rest = parts[1];
			path = switch (lib) {
				case "solar" -> lib + "/" + solarWeight(weight) + "/" + rest;
				case "phosphor" -> lib + "/" + phosphorWeight(weight) + "/" + rest;
				case "react-icons" -> lib + "/" + rest;
				default -> lib + "/" + rest;
			};
		}
		return "/kit/icons/" + path + ".svg";
	}

	private static String solarWeight(String weight) {
		String value = weight == null ? "" : weight.toLowerCase(Locale.ROOT);
		return switch (value) {
			case "bold", "fill" -> "bold";
			default -> "linear";
		};
	}

	private static String phosphorWeight(String weight) {
		String value = weight == null || weight.isBlank() ? "regular" : weight.toLowerCase(Locale.ROOT);
		if (!PHOSPHOR_WEIGHTS.contains(value)) {
			throw new IllegalArgumentException("Unknown phosphor weight \"" + weight + "\"; expected one of "
					+ PHOSPHOR_WEIGHTS);
		}
		return value;
	}

	private static SvgIcon.Parsed load(String resource) {
		try (InputStream in = Icon.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalArgumentException("Icon resource not found on the classpath: " + resource);
			}
			return SvgIcon.parse(in);
		} catch (IOException error) {
			throw new IllegalStateException("Could not read icon resource " + resource, error);
		}
	}

	private record ColoredPath(SVGPath path, SvgIcon.PaintKind fillKind, SvgIcon.PaintKind strokeKind) {}

	private SVGPath buildPath(SvgIcon.SvgShape shape) {
		SVGPath path = new SVGPath();
		path.setContent(shape.pathData());
		SvgIcon.SvgPaint paint = shape.paint();
		switch (paint.fillKind()) {
			case NONE -> path.setFill(null);
			case CURRENT_COLOR -> path.getStyleClass().add("g-icon-fill");
			case COLOR -> path.setFill(paint.fillColor());
		}
		if (paint.strokeKind() != SvgIcon.PaintKind.NONE) {
			path.setStroke(paint.strokeKind() == SvgIcon.PaintKind.COLOR ? paint.strokeColor() : null);
			if (paint.strokeKind() == SvgIcon.PaintKind.CURRENT_COLOR) {
				path.getStyleClass().add("g-icon-stroke");
			}
			path.setStrokeWidth(paint.strokeWidth());
			path.setStrokeLineCap(paint.lineCap());
			path.setStrokeLineJoin(paint.lineJoin());
		}
		if (paint.evenOdd()) {
			path.setFillRule(FillRule.EVEN_ODD);
		}
		path.setOpacity(paint.opacity());
		for (SvgIcon.SvgTransform transform : shape.transforms()) {
			path.getTransforms().add(toTransform(transform));
		}
		coloredPaths.add(new ColoredPath(path, paint.fillKind(), paint.strokeKind()));
		return path;
	}

	private static javafx.scene.transform.Transform toTransform(SvgIcon.SvgTransform transform) {
		double[] values = transform.parameters();
		return switch (transform.function()) {
			case "translate" -> new javafx.scene.transform.Translate(value(values, 0), value(values, 1));
			case "scale" -> new javafx.scene.transform.Scale(value(values, 0),
					values.length > 1 ? values[1] : value(values, 0));
			case "rotate" -> values.length >= 3
					? new javafx.scene.transform.Rotate(value(values, 0), value(values, 1), value(values, 2))
					: new javafx.scene.transform.Rotate(value(values, 0));
			case "matrix" -> new javafx.scene.transform.Affine(value(values, 0), value(values, 2),
					value(values, 4), value(values, 1), value(values, 3), value(values, 5));
			default -> throw new IllegalArgumentException("Unsupported SVG transform: " + transform.function());
		};
	}

	private static double value(double[] values, int index) {
		return index < values.length ? values[index] : 0;
	}

	private void applyTransform(SvgIcon.Parsed parsed) {
		double scale = Math.min(size / parsed.width(), size / parsed.height());
		double offsetX = (size - parsed.width() * scale) / 2 - parsed.minX() * scale;
		double offsetY = (size - parsed.height() * scale) / 2 - parsed.minY() * scale;
		group.getTransforms().setAll(new javafx.scene.transform.Affine(scale, 0, offsetX, 0, scale, offsetY));
	}

	/**
	 * Overrides the {@code -g-icon-color} token for this icon only. The colour is applied as an
	 * inline style: a plain {@code setFill} loses to the {@code .g-icon-fill} stylesheet rule, so a
	 * dark glyph on a light button used to come out in the button's (white) icon colour.
	 * {@code null} returns the icon to the inherited token.
	 */
	public void setIconColor(Color color) {
		String css = color == null ? null : toCss(color);
		for (ColoredPath colored : coloredPaths) {
			StringBuilder style = new StringBuilder();
			if (colored.fillKind() == SvgIcon.PaintKind.CURRENT_COLOR) {
				colored.path().setFill(color);
				if (css != null) {
					style.append("-fx-fill: ").append(css).append(';');
				}
			}
			if (colored.strokeKind() == SvgIcon.PaintKind.CURRENT_COLOR) {
				colored.path().setStroke(color);
				if (css != null) {
					style.append("-fx-stroke: ").append(css).append(';');
				}
			}
			colored.path().setStyle(style.toString());
		}
	}

	private static String toCss(Color color) {
		return String.format(java.util.Locale.ROOT, "rgba(%d, %d, %d, %.3f)",
				Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255),
				Math.round(color.getBlue() * 255), color.getOpacity());
	}

	/** Mirrors the glyph horizontally; {@code icons.tsx} uses this for MicrophoneSlashIcon. */
	public Icon mirrorX() {
		setScaleX(-1);
		return this;
	}

	/** The configured size in px. */
	public double iconSize() {
		return size;
	}

	@Override
	protected double computePrefWidth(double height) {
		return size;
	}

	@Override
	protected double computePrefHeight(double width) {
		return size;
	}

	@Override
	protected double computeMinWidth(double height) {
		return size;
	}

	@Override
	protected double computeMinHeight(double width) {
		return size;
	}

	@Override
	protected double computeMaxWidth(double height) {
		return size;
	}

	@Override
	protected double computeMaxHeight(double width) {
		return size;
	}

	@Override
	protected void layoutChildren() {
		// The group transform already maps the view box onto the requested size.
	}
}
