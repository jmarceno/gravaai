package dev.gravaai.services.extensions;

import org.graalvm.polyglot.HostAccess;

import dev.gravaai.services.extensions.canvas.Canvas2D;

/**
 * The object passed to {@code api.registerRenderHook(phase, (ctx) => …)} (the
 * {@code RenderHookContext} of {@code EXTENSIONS.md}). Increment 1 exposes the always-available
 * fields; the optional scene/cursor/color-query fields land with the compositor seam.
 */
public final class RenderHookContext {

	private final double width;
	private final double height;
	private final double timeMs;
	private final double durationMs;
	private final Canvas2D ctx;

	public RenderHookContext(Canvas2D ctx, double width, double height, double timeMs, double durationMs) {
		this.ctx = ctx;
		this.width = width;
		this.height = height;
		this.timeMs = timeMs;
		this.durationMs = durationMs;
	}

	@HostAccess.Export
	public double getWidth() {
		return width;
	}

	@HostAccess.Export
	public double getHeight() {
		return height;
	}

	@HostAccess.Export
	public double getTimeMs() {
		return timeMs;
	}

	@HostAccess.Export
	public double getDurationMs() {
		return durationMs;
	}

	@HostAccess.Export
	public Canvas2D getCtx() {
		return ctx;
	}
}
