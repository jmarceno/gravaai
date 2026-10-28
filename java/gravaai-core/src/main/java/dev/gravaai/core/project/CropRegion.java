package dev.gravaai.core.project;

/** Normalized crop rectangle (0-1) inside the source frame. */
public record CropRegion(double x, double y, double width, double height) {

	public static final CropRegion DEFAULT = new CropRegion(0, 0, 1, 1);
}
