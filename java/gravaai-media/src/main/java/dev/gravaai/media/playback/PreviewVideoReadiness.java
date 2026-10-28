package dev.gravaai.media.playback;

import java.util.Objects;

/**
 * First-frame readiness for the preview panel, port of
 * {@code src/components/video-editor/videoPlayback/usePreviewVideoReady.ts} (plan §5.9).
 *
 * <p>The preview must not tear down on transient readiness drops during boundary seeks: readiness
 * becomes true when a frame is available and stays true through {@code seeked} drops; only a new
 * media source, {@code emptied} or a media error resets it. The UI adapter maps media events
 * ({@code loadeddata}/{@code canplay}/{@code seeked}/{@code emptied}/{@code error}) onto this state
 * machine; with {@link PlaybackEngine} the first frame arrives through {@code onFrame} before
 * {@code setMedia} returns, so the panel can also treat the first {@link PlaybackFrame} as ready.
 */
public final class PreviewVideoReadiness {

	/** {@code HTMLMediaElement.HAVE_CURRENT_DATA}. */
	public static final int HAVE_CURRENT_DATA = 2;

	/** One media snapshot the UI reads from its video surface. */
	public record MediaState(int readyState, int width, int height) {

		public MediaState {
			if (readyState < 0) {
				throw new IllegalArgumentException("readyState must not be negative");
			}
			if (width < 0 || height < 0) {
				throw new IllegalArgumentException("dimensions must not be negative");
			}
		}

		/** A decodable frame is available ({@code HAVE_CURRENT_DATA} and known dimensions). */
		public boolean hasFrame() {
			return readyState >= HAVE_CURRENT_DATA && width > 0 && height > 0;
		}
	}

	/** Receives every ready/not-ready evaluation, like the React {@code setReady} calls. */
	@FunctionalInterface
	public interface Listener {
		void onReadyChanged(boolean ready);
	}

	private final Listener listener;
	private boolean attached;
	private boolean ready;
	private MediaState state = new MediaState(0, 0, 0);

	public PreviewVideoReadiness(Listener listener) {
		this.listener = Objects.requireNonNull(listener, "listener");
	}

	public boolean ready() {
		return ready;
	}

	/** Subscribes to a new media source: resets readiness, then re-evaluates the current state. */
	public void attach(MediaState state) {
		this.state = Objects.requireNonNull(state, "state");
		this.attached = true;
		reset();
		update();
	}

	/** Removes the subscription (React effect cleanup): later events are ignored. */
	public void detach() {
		attached = false;
	}

	/** Records a new media snapshot without evaluating; the next event evaluates it. */
	public void onMediaState(MediaState state) {
		this.state = Objects.requireNonNull(state, "state");
	}

	/** {@code loadeddata}/{@code canplay}/{@code seeked}. */
	public void onLoadedData() {
		update();
	}

	public void onCanPlay() {
		update();
	}

	public void onSeeked() {
		update();
	}

	/** {@code emptied}: a new source is loading, the preview must not show a stale frame. */
	public void onEmptied() {
		reset();
	}

	/** {@code error}: tear the preview down. */
	public void onError() {
		reset();
	}

	private void update() {
		if (!attached) {
			return;
		}
		if (state.hasFrame()) {
			ready = true;
			listener.onReadyChanged(true);
		}
		// Transient readiness drops during seeks keep the last frame: no false here.
	}

	private void reset() {
		if (!attached) {
			return;
		}
		ready = false;
		listener.onReadyChanged(false);
	}
}
