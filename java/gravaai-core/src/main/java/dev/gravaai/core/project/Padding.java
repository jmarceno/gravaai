package dev.gravaai.core.project;

/** Output padding, either linked (all sides equal) or per axis. */
public record Padding(double top, double bottom, double left, double right, Boolean linked) {

	public static final double ADVANCED_VERTICAL_PADDING_MAX = 250;
	public static final Padding DEFAULT = new Padding(20, 20, 20, 20, true);
}
