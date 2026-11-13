package dev.gravaai.ui.editor.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;

/**
 * One stretch of the edited timeline where the presenter drew on screen while recording: the
 * timeline's pen band on the clip row.
 *
 * @param startMs timeline start
 * @param endMs timeline end (after {@code startMs})
 * @param sourceStartMs where the draw session began in the source video
 * @param sourceEndMs where it ended in the source video
 * @param strokes how many strokes the session holds
 */
public record InkMark(long startMs, long endMs, long sourceStartMs, long sourceEndMs, int strokes) {

	/** A draw session in source time, with its stroke count. */
	public record Session(long startMs, long endMs, int strokes) {
	}

	/**
	 * Projects draw sessions (source time) onto the edited timeline. A session that spans a cut
	 * becomes one mark per surviving piece, with clip speed applied; footage cut away has no mark.
	 */
	public static List<InkMark> project(List<Session> sessions, List<ClipRegion> clips) {
		List<InkMark> marks = new ArrayList<>();
		for (Session session : sessions) {
			for (ClipRegion clip : clips) {
				long clipSourceStart = Clips.getClipSourceStartMs(clip);
				long clipSourceEnd = Clips.getClipSourceEndMs(clip);
				long from = Math.max(session.startMs(), clipSourceStart);
				long to = Math.min(session.endMs(), clipSourceEnd);
				if (to <= from) {
					continue;
				}
				double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
				long start = clip.startMs() + Math.round((from - clipSourceStart) / speed);
				long end = clip.startMs() + Math.round((to - clipSourceStart) / speed);
				if (end > start) {
					marks.add(new InkMark(start, end, session.startMs(), session.endMs(), session.strokes()));
				}
			}
		}
		marks.sort(Comparator.comparingLong(InkMark::startMs));
		return List.copyOf(marks);
	}
}
