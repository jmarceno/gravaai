package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/** Normalized crop rectangle (0-1) inside the source frame. */
public record CropRegion(@Nullable double x, @Nullable double y, double width, double height) {

	public static final CropRegion DEFAULT = new CropRegion(0, 0, 1, 1);
}
