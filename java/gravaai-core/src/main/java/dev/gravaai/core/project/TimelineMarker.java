package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/**
 * A named point on the recording (port of the marker concept from Descript/Screen Studio).
 *
 * <p>{@code startMs} is a <b>source</b> time, like caption cues: a marker rides the footage it was
 * placed on, so every trim, cut and reorder moves it with the content instead of leaving it behind.
 * Markers whose footage was cut away keep their source time and come back with the footage; the
 * timeline only draws the ones that still land on a retained clip
 * ({@link MarkerTimeline#projectMarkers}).
 *
 * @param chapter whether the marker is also a chapter: exported MP4s carry these as chapter
 *     metadata and the export writes a YouTube-ready {@code chapters.txt} next to the video
 */
public record TimelineMarker(String id, long startMs, String name, boolean chapter) {

	/** Longest accepted marker name; chapter titles end up in file metadata. */
	public static final int MAX_NAME_LENGTH = 120;

	public TimelineMarker {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("marker id must not be blank");
		}
		if (startMs < 0) {
			throw new IllegalArgumentException("marker start must be >= 0");
		}
		if (name == null) {
			throw new IllegalArgumentException("marker name must not be null");
		}
		name = name.trim();
		if (name.length() > MAX_NAME_LENGTH) {
			name = name.substring(0, MAX_NAME_LENGTH);
		}
	}

	public TimelineMarker withStartMs(long nextStartMs) {
		return new TimelineMarker(id, nextStartMs, name, chapter);
	}

	public TimelineMarker withName(String nextName) {
		return new TimelineMarker(id, startMs, nextName, chapter);
	}

	public TimelineMarker withChapter(boolean nextChapter) {
		return new TimelineMarker(id, startMs, name, nextChapter);
	}

	/** The default name of the {@code ordinal}th marker ({@code Marker 1}, {@code Marker 2}, …). */
	public static String defaultName(int ordinal) {
		return "Marker " + Math.max(1, ordinal);
	}

	/** Normalizes a user-typed name: trimmed, single-spaced, never blank. */
	public static String cleanName(@Nullable String rawName) {
		if (rawName == null) {
			return "Marker";
		}
		String cleaned = rawName.replaceAll("\\s+", " ").trim();
		return cleaned.isEmpty() ? "Marker" : cleaned;
	}
}
