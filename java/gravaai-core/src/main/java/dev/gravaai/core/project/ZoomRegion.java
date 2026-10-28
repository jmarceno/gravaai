package dev.gravaai.core.project;

/** A zoom region on the timeline. */
public record ZoomRegion(
		String id, long startMs, long endMs, ZoomDepth depth, ZoomFocus focus, ZoomMode mode) {}
