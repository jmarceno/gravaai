package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chapter list of an export (the YouTube-ready view of the markers promoted to chapters).
 *
 * <p>YouTube only honours a chapter list whose first timestamp is {@code 00:00}, so a project whose
 * first chapter starts later gets an implicit {@code 00:00 Intro} entry: the alternative is
 * silently exporting a list YouTube then throws away. Duplicate timestamps collapse (the first
 * title wins) and chapters that would start at or after the end of the edit are dropped.
 */
public final class Chapters {

	/** The title used for the implicit opening chapter when the first chapter starts later. */
	public static final String INTRO_TITLE = "Intro";

	/** One chapter on the edited timeline. */
	public record Chapter(long startMs, String title) {}

	private Chapters() {}

	/** The chapter list of an export, built from the project's markers. */
	public static List<Chapter> build(List<TimelineMarker> markers, List<ClipRegion> clips,
			long timelineDurationMs) {
		return fromFragments(MarkerTimeline.projectMarkers(markers, clips), timelineDurationMs);
	}

	/** The chapter list for markers already projected onto the timeline. */
	public static List<Chapter> fromFragments(List<MarkerTimeline.Fragment> fragments,
			long timelineDurationMs) {
		long duration = Math.max(0, timelineDurationMs);
		List<Chapter> chapters = new ArrayList<>();
		long previousStart = Long.MIN_VALUE;
		for (MarkerTimeline.Fragment fragment : fragments) {
			if (!fragment.marker().chapter()) {
				continue;
			}
			long startMs = Math.max(0, Math.min(fragment.timelineMs(), duration));
			if (startMs >= duration && duration > 0) {
				continue;
			}
			if (startMs == previousStart) {
				continue;
			}
			previousStart = startMs;
			chapters.add(new Chapter(startMs, TimelineMarker.cleanName(fragment.marker().name())));
		}
		if (chapters.isEmpty()) {
			return List.of();
		}
		if (chapters.get(0).startMs() > 0) {
			chapters.add(0, new Chapter(0, INTRO_TITLE));
		}
		return List.copyOf(chapters);
	}

	/** {@code MM:SS} (or {@code H:MM:SS} past an hour) as YouTube chapter lines expect it. */
	public static String timestamp(long startMs) {
		long totalSeconds = Math.max(0, Math.round(startMs / 1000.0));
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		if (hours > 0) {
			return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
	}

	/** The {@code chapters.txt} sidecar text: one {@code 00:00 Title} line per chapter. */
	public static String sidecarText(List<Chapter> chapters) {
		StringBuilder text = new StringBuilder();
		for (Chapter chapter : chapters) {
			text.append(timestamp(chapter.startMs())).append(' ').append(chapter.title())
					.append('\n');
		}
		return text.toString();
	}
}
