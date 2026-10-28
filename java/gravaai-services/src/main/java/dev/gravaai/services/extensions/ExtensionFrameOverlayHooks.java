package dev.gravaai.services.extensions;

import java.util.function.DoubleSupplier;

import io.github.humbleui.skija.Canvas;

import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.services.extensions.canvas.Canvas2D;

/**
 * Binds the extension host to the compositor's render-hook seam (plan §5.18): the adapter the
 * preview and the exporter install with {@code Compositor.setOverlayHooks(...)}.
 *
 * <p>For every dispatched phase it wraps the compositor's Skija {@link Canvas} in a
 * {@link Canvas2D} and runs the extensions' hooks through
 * {@link ExtensionHost#render(RenderHookPhase, RenderHookContext)} with the documented
 * {@code RenderHookContext} fields (width, height, timeMs, durationMs, ctx). The media layer stays
 * independent of {@code gravaai-services}; this class is the only bridge.</p>
 *
 * <p>Isolation: a broken extension must not break preview/export. The registry already logs and
 * skips failing hooks; this adapter additionally guards the canvas save stack (an unbalanced
 * {@code ctx.save()} is unwound after the hook) and never lets an exception escape to the
 * compositor. {@link #render} synchronizes on the {@link ExtensionHost}, so every adapter of the
 * process (one preview per editor window plus the exporter) serialises hook execution: the preview
 * GL thread and the export GL thread can never enter the same GraalJS context concurrently.</p>
 */
public final class ExtensionFrameOverlayHooks implements FrameOverlayHooks {

	private static final System.Logger LOG =
			System.getLogger(ExtensionFrameOverlayHooks.class.getName());

	private final ExtensionHost host;
	private final DoubleSupplier durationMs;

	public ExtensionFrameOverlayHooks(ExtensionHost host) {
		this(host, () -> 0.0);
	}

	/**
	 * @param host the extension host owning the render hook registrations
	 * @param durationMs the timeline duration for {@code RenderHookContext.durationMs}; null means
	 *        0 (unknown)
	 */
	public ExtensionFrameOverlayHooks(ExtensionHost host, DoubleSupplier durationMs) {
		if (host == null) {
			throw new IllegalArgumentException("host must not be null");
		}
		this.host = host;
		this.durationMs = durationMs;
	}

	/** The bound extension host. */
	public ExtensionHost host() {
		return host;
	}

	@Override
	public void render(Phase phase, Canvas canvas, FrameInfo frame) {
		if (canvas == null || frame == null) {
			return;
		}
		// Same phase names as EXTENSIONS.md; background stays reserved and is never dispatched.
		RenderHookPhase wire = RenderHookPhase.fromWire(phase.wire());
		if (wire == null || !wire.dispatched()) {
			return;
		}
		synchronized (host) {
			if (host.renderHooks().count(wire) == 0) {
				return;
			}
			double duration = durationMs == null ? 0.0 : durationMs.getAsDouble();
			int saveCount = canvas.getSaveCount();
			try (Canvas2D ctx = new Canvas2D(canvas, frame.width(), frame.height())) {
				host.render(wire, new RenderHookContext(ctx, frame.width(), frame.height(),
						frame.timeMs(), duration));
			} catch (RuntimeException error) {
				// The registry skips individual failing hooks; this keeps a broken adapter from
				// breaking preview/export.
				LOG.log(System.Logger.Level.WARNING,
						"[extensions] " + phase.wire() + " hook dispatch failed: " + error);
			} finally {
				// An extension that saved without restoring must not corrupt the compositor's
				// save stack for the following pipeline stages.
				if (canvas.getSaveCount() > saveCount) {
					canvas.restoreToCount(saveCount);
				}
			}
		}
	}
}
