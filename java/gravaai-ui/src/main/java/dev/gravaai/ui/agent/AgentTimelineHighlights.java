package dev.gravaai.ui.agent;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/**
 * Transient agent overlay for the timeline (port of {@code AgentTimelineHighlights.tsx}, plan
 * §5.16/P3.2): after an agent batch the spans it cut, added or changed are drawn in the AI colours.
 *
 * <p>The component is a transparent {@link Pane} the timeline mounts above its rows; the timeline
 * provides the exact pixel mapping through {@link TimeAxis} and the visible range, so the overlay
 * uses the same coordinates as the items below it. It never consumes mouse events.
 */
public final class AgentTimelineHighlights extends Pane {

	/** Pixel mapping provided by the timeline (valueToPixels + range in the React source). */
	public interface TimeAxis {
		/** The x offset for a timeline millisecond inside the visible range. */
		double xForMs(double timeMs);

		/** The width in pixels for a duration. */
		double widthForMs(double durationMs);
	}

	private static final double TRACK_TOP = 24;
	private static final double CUT_LINE_WIDTH = 3;
	private static final double BAND_MIN_WIDTH = 3;

	private TimeAxis timeAxis = new TimeAxis() {
		@Override
		public double xForMs(double timeMs) {
			return timeMs;
		}

		@Override
		public double widthForMs(double durationMs) {
			return durationMs;
		}
	};
	private long rangeStartMs;
	private long rangeEndMs = Long.MAX_VALUE;
	private List<AgentHighlightView> highlights = List.of();

	public AgentTimelineHighlights() {
		getStyleClass().add("agent-timeline-highlights");
		setMouseTransparent(true);
		setPickOnBounds(false);
		widthProperty().addListener((observable, oldValue, newValue) -> redraw());
		heightProperty().addListener((observable, oldValue, newValue) -> redraw());
	}

	/** Sets the timeline's pixel mapping. */
	public void timeAxis(TimeAxis axis) {
		this.timeAxis = axis == null ? this.timeAxis : axis;
		redraw();
	}

	/** Sets the visible timeline range so bands are clamped like the React overlay. */
	public void range(long startMs, long endMs) {
		this.rangeStartMs = startMs;
		this.rangeEndMs = endMs;
		redraw();
	}

	/** Replaces the drawn bands. */
	public void setHighlights(List<AgentHighlightView> nextHighlights) {
		this.highlights = nextHighlights == null ? List.of() : List.copyOf(nextHighlights);
		redraw();
	}

	private void redraw() {
		getChildren().clear();
		if (highlights.isEmpty() || getWidth() <= 0) {
			return;
		}
		for (AgentHighlightView highlight : highlights) {
			long startMs = Math.max(highlight.startMs(), rangeStartMs);
			long endMs = Math.min(highlight.endMs(), rangeEndMs);
			if (endMs <= startMs) {
				continue;
			}
			double x = timeAxis.xForMs(startMs);
			double width = Math.max(BAND_MIN_WIDTH, timeAxis.widthForMs(endMs - startMs));
			Node node = highlight.kind() == AgentHighlightUpdate.Kind.CUT
					? cutMarker(highlight, x)
					: band(highlight, x, width);
			if (node != null) {
				getChildren().add(node);
			}
		}
	}

	/** A cut is a join on the clip handle: a solid glowing line plus a labelled pill. */
	private Node cutMarker(AgentHighlightView highlight, double x) {
		String colour = cssColour(highlight.kind().argb());
		Pane marker = new Pane();
		marker.setMouseTransparent(true);
		Region line = new Region();
		line.setPrefSize(CUT_LINE_WIDTH, Math.max(0, getHeight() - TRACK_TOP));
		line.setLayoutX(x - CUT_LINE_WIDTH / 2);
		line.setLayoutY(TRACK_TOP);
		line.setStyle("-fx-background-color: " + colour + "; -fx-background-radius: 2;"
				+ " -fx-effect: dropshadow(gaussian, " + colour + ", 6, 0, 0, 0);");
		Label pill = new Label(highlight.displayLabel());
		pill.setMouseTransparent(true);
		pill.setStyle("-fx-background-color: " + colour
				+ "; -fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: 600;"
				+ " -fx-padding: 1 6 1 6; -fx-background-radius: 999;");
		pill.applyCss();
		pill.autosize();
		pill.setLayoutX(x - pill.prefWidth(-1) / 2);
		pill.setLayoutY(0);
		marker.getChildren().addAll(line, pill);
		return marker;
	}

	/** Added/changed spans: a translucent band with a 2 px AI-colour top border. */
	private Node band(AgentHighlightView highlight, double x, double width) {
		Region band = new Region();
		band.setMouseTransparent(true);
		band.setLayoutX(x);
		band.setLayoutY(TRACK_TOP);
		band.setPrefSize(width, Math.max(0, getHeight() - TRACK_TOP));
		String colour = cssColour(highlight.kind().argb());
		band.setStyle("-fx-background-color: " + withAlpha(colour, 0.15)
				+ "; -fx-border-color: " + colour + " transparent transparent transparent;"
				+ " -fx-border-width: 2 0 0 0; -fx-background-radius: 3; -fx-border-radius: 3;");
		return band;
	}

	private static String cssColour(int argb) {
		return String.format("#%06X", argb & 0xFFFFFF);
	}

	/** {@code rgba(r,g,b,alpha)} for a #rrggbb colour. */
	private static String withAlpha(String hexColour, double alpha) {
		int rgb = Integer.parseInt(hexColour.substring(1), 16);
		return "rgba(" + ((rgb >> 16) & 0xFF) + "," + ((rgb >> 8) & 0xFF) + "," + (rgb & 0xFF)
				+ "," + alpha + ")";
	}

	/** Clips the overlay to its bounds (rounding artefacts otherwise leak outside the timeline). */
	public void clipToBounds() {
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(widthProperty());
		clip.heightProperty().bind(heightProperty());
		setClip(clip);
	}
}
