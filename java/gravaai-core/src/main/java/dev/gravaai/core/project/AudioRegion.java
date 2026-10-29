package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/** An extra audio region (music/voice file) on the timeline. */
public record AudioRegion(
		String id,
		long startMs,
		long endMs,
		@Nullable String audioPath,
		@Nullable double volume,
		@Nullable Boolean normalize,
		@Nullable Integer trackIndex) {}
