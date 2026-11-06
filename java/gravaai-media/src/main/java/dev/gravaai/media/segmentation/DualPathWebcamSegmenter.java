package dev.gravaai.media.segmentation;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * The preview's two-path segmenter: the fast selfie model while the timeline plays, the precise
 * MODNet model once it stops — or always, when the project turns on the Advanced high-quality
 * switch.
 *
 * <p>Both paths run the same {@link OnnxMaskSegmenter} pipeline at the same 256 px, so the two masks
 * differ in quality, not in edge character. They still do not agree pixel for pixel, which is why
 * this wrapper remembers the last mask either path produced and keeps serving it while the other
 * path is still working: a pause must not flash an unsegmented card, and a switched-to path that
 * has never run must not blank the card for a whole inference. The async wrapper above it
 * ({@link AsyncWebcamSegmenter}) already holds a mask across an inference, but it holds the one for
 * its own delegate; this selector is what makes the hand-off between two models seamless.</p>
 *
 * <p>{@link #setQuality} is the only state transition: the consumer calls it on a playback state
 * change. A change is a request, not a promise — the next {@link #maskFor} runs whichever model the
 * current quality names, and the first frame after a change answers the previous path's mask (or
 * {@code null} if neither has produced one yet).</p>
 *
 * <p>Single-caller, one instance per consumer. Each path's segmenter is opened lazily by its own
 * {@link LazyWebcamSegmenter}, so a project that never pauses never loads MODNet and a project that
 * stays paused never loads the selfie model.</p>
 */
public final class DualPathWebcamSegmenter implements WebcamSegmenter {

	/** The fast path: playback. */
	private final WebcamSegmenter fast;
	/** The precise path: pause, scrub, export. */
	private final WebcamSegmenter precise;
	/**
	 * Set while a quality switch is in flight: the last mask the previous path produced, served
	 * until the new path's first mask arrives.
	 */
	private @Nullable RgbaFrame carriedMask;
	private WebcamMaskQuality quality;
	private @Nullable Runnable maskReadyListener;

	public DualPathWebcamSegmenter(WebcamSegmenter fast, WebcamSegmenter precise,
			WebcamMaskQuality initialQuality) {
		this.fast = fast;
		this.precise = precise;
		this.quality = initialQuality;
	}

	/** A selector over a fast and a precise lazy segmenter, starting on the fast path. */
	public static DualPathWebcamSegmenter preview() {
		return new DualPathWebcamSegmenter(
				new LazyWebcamSegmenter(WebcamMaskQuality.FAST),
				new LazyWebcamSegmenter(WebcamMaskQuality.PRECISE),
				WebcamMaskQuality.FAST);
	}

	/** The path the next {@link #maskFor} will run. */
	public WebcamMaskQuality quality() {
		return quality;
	}

	/**
	 * Points the selector at a path. Idempotent; a real change makes the next frame run the new
	 * model and keeps the previous mask on screen until that model answers.
	 */
	@Override
	public void setQuality(WebcamMaskQuality next) {
		if (next == quality) {
			return;
		}
		quality = next;
	}

	@Override
	public @Nullable RgbaFrame maskFor(RgbaFrame frame, MaskTuning tuning) {
		WebcamSegmenter active = quality == WebcamMaskQuality.FAST ? fast : precise;
		RgbaFrame mask = active.maskFor(frame, tuning);
		if (mask != null) {
			carriedMask = mask;
			return mask;
		}
		// The active path has nothing for this frame yet (still opening its model, or unavailable).
		// Fall back to the last mask either path produced rather than drawing the room back in.
		return carriedMask;
	}

	@Override
	public @Nullable RgbaFrame completedMask() {
		RgbaFrame carried = carriedMask;
		return carried != null ? carried : active().completedMask();
	}

	/**
	 * Forwards the listener to both paths, so a paused repaint happens whichever model is the one
	 * that finished.
	 */
	@Override
	public void setMaskReadyListener(@Nullable Runnable listener) {
		maskReadyListener = listener;
		fast.setMaskReadyListener(listener);
		precise.setMaskReadyListener(listener);
	}

	/** Whether the precise path's model has been opened (diagnostics/tests). */
	public boolean preciseOpened() {
		return precise instanceof LazyWebcamSegmenter lazy && lazy.opened();
	}

	/** Whether the fast path's model has been opened (diagnostics/tests). */
	public boolean fastOpened() {
		return fast instanceof LazyWebcamSegmenter lazy && lazy.opened();
	}

	private WebcamSegmenter active() {
		return quality == WebcamMaskQuality.FAST ? fast : precise;
	}

	@Override
	public void close() {
		fast.setMaskReadyListener(null);
		precise.setMaskReadyListener(null);
		maskReadyListener = null;
		fast.close();
		precise.close();
	}
}
