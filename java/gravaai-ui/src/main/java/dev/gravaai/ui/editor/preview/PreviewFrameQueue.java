package dev.gravaai.ui.editor.preview;

import java.nio.ByteBuffer;

import dev.gravaai.media.compose.Background;
import dev.gravaai.media.compose.FrameInput;
import dev.gravaai.media.compose.RgbaFrame;

/**
 * Non-blocking hand-off between the playback engine thread (which owns decoder buffers only until
 * its next decode) and the GL preview thread (plan §5.9/P2.11 first-preview).
 *
 * <p>{@link #submit(FrameInput)} copies the source and webcam frames into queue-owned direct
 * buffers before returning, so the engine can reuse its {@link RgbaFrame}s immediately and its
 * {@code setMedia}/{@code onFrame} path never waits for the compositor. The GL thread polls the
 * latest copy ({@link #pollLatest()}); two slots (one being drawn, one being filled) make the
 * latest-wins coalescing safe without locking the producer behind a draw.
 *
 * <p>Only one producer (the engine's render thread) and one consumer (the {@code gl-preview}
 * thread) are expected, matching {@code PlaybackController.onFrame}.
 */
public final class PreviewFrameQueue {

	private final Object lock = new Object();
	private final Slot[] slots = {new Slot(), new Slot()};
	private Slot rendering;
	private Slot pending;
	private long submittedCount;
	private long drawnCount;

	/** Copies a frame and returns immediately; never blocks on the GL thread. */
	public void submit(FrameInput input) {
		if (input == null) {
			return;
		}
		synchronized (lock) {
			Slot target = slots[0] == rendering ? slots[1] : slots[0];
			target.copy(input);
			pending = target;
			submittedCount++;
		}
	}

	/** The latest submitted copy, or null when nothing new; call {@link #finishRendering()} after. */
	public FrameInput pollLatest() {
		synchronized (lock) {
			if (pending == null) {
				return null;
			}
			rendering = pending;
			pending = null;
			return rendering.frame();
		}
	}

	/** Marks the polled frame as drawn so its slot can be reused. */
	public void finishRendering() {
		synchronized (lock) {
			if (rendering != null) {
				rendering = null;
				drawnCount++;
			}
		}
	}

	/** True when a submitted frame waits for the GL thread (tests/diagnostics). */
	public boolean hasPending() {
		synchronized (lock) {
			return pending != null;
		}
	}

	/** Frames copied by the producer (tests/diagnostics). */
	public long submittedCount() {
		synchronized (lock) {
			return submittedCount;
		}
	}

	/** Frames polled by the GL thread (tests/diagnostics). */
	public long drawnCount() {
		synchronized (lock) {
			return drawnCount;
		}
	}

	/** One reusable frame buffer pair (source + webcam). */
	private static final class Slot {
		private ByteBuffer sourcePixels;
		private ByteBuffer webcamPixels;
		private ByteBuffer wallpaperPixels;
		private RgbaFrame sourceFrame;
		private RgbaFrame webcamFrame;
		private FrameInput frame;

		void copy(FrameInput input) {
			sourceFrame = copy(input.sourceFrame(), true);
			webcamFrame = copy(input.webcamFrame(), false);
			Background background = input.background();
			if (background instanceof Background.VideoFrame video && video.frame() != null) {
				// The wallpaper decoder reuses its buffer; the GL thread needs a stable copy.
				RgbaFrame frame = video.frame();
				int needed = frame.width() * frame.height() * 4;
				if (wallpaperPixels == null || wallpaperPixels.capacity() < needed) {
					wallpaperPixels = ByteBuffer.allocateDirect(needed);
				}
				background = new Background.VideoFrame(
						copyInto(frame, wallpaperPixels, needed));
			}
			frame = new FrameInput(input.frameIndex(), input.timeSeconds(), sourceFrame, webcamFrame,
					input.cursorSamples(), background, input.backgroundBlur(), input.layout(),
					input.zoom(), input.style(), input.cursor(), input.webcam(),
					input.annotations(), input.captions(), input.keystrokes(), input.fade(),
					input.scene(), input.sourceTimeSeconds());
		}

		private RgbaFrame copy(RgbaFrame frame, boolean source) {
			if (frame == null) {
				return null;
			}
			int width = frame.width();
			int height = frame.height();
			int needed = width * height * 4;
			ByteBuffer target = source ? sourcePixels : webcamPixels;
			if (target == null || target.capacity() < needed) {
				target = ByteBuffer.allocateDirect(needed);
				if (source) {
					sourcePixels = target;
				} else {
					webcamPixels = target;
				}
			}
			target.clear();
			ByteBuffer pixels = frame.pixelsTopFirstRgba().duplicate();
			pixels.position(0);
			pixels.limit(Math.min(pixels.capacity(), needed));
			target.put(pixels);
			target.position(0);
			target.limit(target.capacity());
			return new RgbaFrame(width, height, target);
		}

		private static RgbaFrame copyInto(RgbaFrame frame, ByteBuffer target, int needed) {
			target.clear();
			ByteBuffer pixels = frame.pixelsTopFirstRgba().duplicate();
			pixels.position(0);
			pixels.limit(Math.min(pixels.capacity(), needed));
			target.put(pixels);
			target.position(0);
			target.limit(needed);
			return new RgbaFrame(frame.width(), frame.height(), target);
		}

		FrameInput frame() {
			return frame;
		}
	}
}
