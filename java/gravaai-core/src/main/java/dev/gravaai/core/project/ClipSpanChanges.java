package dev.gravaai.core.project;

/** Clip resize/move rules (port of {@code clipSpanChange.ts}). */
public final class ClipSpanChanges {

	private ClipSpanChanges() {}

	/**
	 * {@code changeClipSpan}: a move keeps the source window; a resize reveals/hides footage but
	 * cannot manufacture source before 0 or after EOF.
	 */
	public static ClipRegion changeClipSpan(
			ClipRegion clip, double startMs, double endMs, double sourceDurationMs) {
		double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
		long sourceStart = Clips.getClipSourceStartMs(clip);

		boolean isMove = startMs - clip.startMs() == endMs - clip.endMs();
		if (isMove) {
			return withSpan(clip, Math.round(startMs), Math.round(endMs), sourceStart);
		}

		double start = Math.max(startMs,
				Math.ceil(clip.startMs() - (sourceStart - (clip.sourceMinMs() == null ? 0 : clip.sourceMinMs())) / speed));
		long sourceStartMs = Math.round(sourceStart + (start - clip.startMs()) * speed);
		double sourceMax = clip.sourceMaxMs() == null ? sourceDurationMs : clip.sourceMaxMs();
		double end = Math.min(endMs,
				Math.floor(start + (Math.min(sourceDurationMs, sourceMax) - sourceStartMs) / speed));
		return withSpan(clip, Math.round(start), Math.round(end), sourceStartMs);
	}

	private static ClipRegion withSpan(ClipRegion clip, long startMs, long endMs, long sourceStartMs) {
		return new ClipRegion(clip.id(), startMs, endMs, sourceStartMs, clip.sourceMinMs(),
				clip.sourceMaxMs(), clip.speed(), clip.muted(), clip.showSourceAudio(),
				clip.cameraLayout(), clip.transitionOut());
	}
}
