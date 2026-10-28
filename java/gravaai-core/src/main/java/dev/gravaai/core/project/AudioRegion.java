package dev.gravaai.core.project;

/** An extra audio region (music/voice file) on the timeline. */
public record AudioRegion(
		String id,
		long startMs,
		long endMs,
		String audioPath,
		double volume,
		Boolean normalize,
		Integer trackIndex) {}
