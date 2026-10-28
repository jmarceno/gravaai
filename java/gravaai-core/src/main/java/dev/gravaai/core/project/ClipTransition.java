package dev.gravaai.core.project;

/** Transition from a clip into the next one (or a fade-out on the last clip). */
public record ClipTransition(ClipTransitionKind kind, long durationMs) {

	public static final long DEFAULT_CLIP_TRANSITION_MS = 500;
	public static final long MIN_CLIP_TRANSITION_MS = 200;
	public static final long MAX_CLIP_TRANSITION_MS = 2000;
}
