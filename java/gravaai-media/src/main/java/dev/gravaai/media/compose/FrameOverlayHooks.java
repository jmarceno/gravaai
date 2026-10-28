package dev.gravaai.media.compose;

import io.github.humbleui.skija.Canvas;

/**
 * The compositor seam for {@code EXTENSIONS.md} "Render Pipeline" hooks (plan §5.18): the editor
 * preview and the exporter install their extensions' render hooks here and the {@link Compositor}
 * dispatches them at the documented stages of a frame.
 *
 * <p>This type lives in {@code gravaai-media} so the media layer never depends on
 * {@code gravaai-services}; the extension host binds to it through a small adapter
 * ({@code dev.gravaai.services.extensions.ExtensionFrameOverlayHooks}) that wraps the Skija
 * {@link Canvas} in the extension canvas and builds the {@code RenderHookContext}.</p>
 *
 * <p>Transform semantics (ported from the phase table): {@link Phase#POST_VIDEO},
 * {@link Phase#POST_ZOOM} and {@link Phase#POST_CURSOR} are dispatched with the camera/scene
 * transform already applied, so their drawing follows zoom and motion exactly like the built-in
 * layers; {@link Phase#POST_WEBCAM}, {@link Phase#POST_ANNOTATIONS} and {@link Phase#FINAL} are
 * dispatched after the compositor restored the canvas transform. {@link Phase#BACKGROUND} is
 * reserved and never dispatched.</p>
 *
 * <p>The default {@link #NONE} instance keeps a frame byte-identical to a compositor without the
 * seam: the compositor short-circuits every phase when the installed hooks are {@code NONE}.</p>
 */
@FunctionalInterface
public interface FrameOverlayHooks {

	/** No hooks: the compositor skips the hook dispatch entirely. */
	FrameOverlayHooks NONE = (phase, canvas, frame) -> {};

	/**
	 * Draws one phase of one frame into the compositor's canvas.
	 *
	 * @param phase the pipeline phase being dispatched; never {@link Phase#BACKGROUND}
	 * @param canvas the compositor's Skija canvas (top-left origin, like Canvas 2D)
	 * @param frame the frame being composited
	 */
	void render(Phase phase, Canvas canvas, FrameInfo frame);

	/** Render pipeline phases of {@code EXTENSIONS.md} ("Render Pipeline"). */
	enum Phase {
		BACKGROUND("background", false),
		POST_VIDEO("post-video", true),
		POST_ZOOM("post-zoom", true),
		POST_CURSOR("post-cursor", true),
		POST_WEBCAM("post-webcam", true),
		POST_ANNOTATIONS("post-annotations", true),
		FINAL("final", true);

		private final String wire;
		private final boolean dispatched;

		Phase(String wire, boolean dispatched) {
			this.wire = wire;
			this.dispatched = dispatched;
		}

		/** The phase name of the manifest/JS API ({@code api.registerRenderHook(phase, …)}). */
		public String wire() {
			return wire;
		}

		/** False only for the reserved {@code background} phase. */
		public boolean dispatched() {
			return dispatched;
		}

		/** Resolves a {@link #wire()} name, or null when it is not a phase. */
		public static Phase fromWire(String value) {
			if (value == null) {
				return null;
			}
			for (Phase phase : values()) {
				if (phase.wire.equals(value)) {
					return phase;
				}
			}
			return null;
		}
	}

	/**
	 * Per-frame context passed to every phase.
	 *
	 * @param width output width in pixels
	 * @param height output height in pixels
	 * @param timeMs the timeline time of the frame in milliseconds
	 */
	record FrameInfo(int width, int height, long timeMs) {

		public FrameInfo {
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException(
						"invalid frame size: " + width + "x" + height);
			}
		}
	}
}
