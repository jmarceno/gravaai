package dev.gravaai.ui.editor.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;

/**
 * One stretch of the edited timeline where a push-to-talk recording's microphone was muted: the
 * mic lane's muted band.
 *
 * <p>The muting is in the audio itself (silence was recorded), so the band only explains it.
 *
 * @param startMs timeline start
 * @param endMs timeline end (after {@code startMs})
 * @param sourceStartMs where the muting began in the source video
 * @param sourceEndMs where it ended in the source video
 */
public record MicMuteMark(long startMs, long endMs, long sourceStartMs, long sourceEndMs) {

	/** A muted stretch in source time. */
	public record Session(long startMs, long endMs) {
	}

	/**
	 * Projects muted spans (source time) onto the edited timeline. A span that crosses a cut
	 * becomes one mark per surviving piece, with clip speed applied; footage cut away has no mark.
	 */
	public static List<MicMuteMark> project(List<Session> sessions, List<ClipRegion> clips) {
		List<MicMuteMark> marks = new ArrayList<>();
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
					marks.add(new MicMuteMark(start, end, session.startMs(), session.endMs()));
				}
			}
		}
		marks.sort(Comparator.comparingLong(MicMuteMark::startMs));
		return List.copyOf(marks);
	}
}
