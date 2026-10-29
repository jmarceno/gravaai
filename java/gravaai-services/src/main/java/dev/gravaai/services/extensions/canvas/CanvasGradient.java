package dev.gravaai.services.extensions.canvas;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.HostAccess;

import io.github.humbleui.skija.Shader;

/**
 * A live {@code CanvasGradient} for the extension canvas. Stops are appended by the extension
 * after the gradient is created, so the shader is built from the current stops on every draw, like
 * a browser's gradient object.
 */
public final class CanvasGradient {

	private record Stop(double offset, int color) {}

	private final boolean radial;
	private final double x0;
	private final double y0;
	private final double r0;
	private final double x1;
	private final double y1;
	private final double r1;
	private final List<Stop> stops = new ArrayList<>();

	private CanvasGradient(
			boolean radial, double x0, double y0, double r0, double x1, double y1, double r1) {
		this.radial = radial;
		this.x0 = x0;
		this.y0 = y0;
		this.r0 = r0;
		this.x1 = x1;
		this.y1 = y1;
		this.r1 = r1;
	}

	static CanvasGradient linear(double x0, double y0, double x1, double y1) {
		return new CanvasGradient(false, x0, y0, 0, x1, y1, 0);
	}

	static CanvasGradient radial(double x0, double y0, double r0, double x1, double y1, double r1) {
		return new CanvasGradient(true, x0, y0, r0, x1, y1, r1);
	}

	/** Port of {@code CanvasGradient.addColorStop}; invalid offsets/colours are ignored. */
	@HostAccess.Export
	public void addColorStop(double offset, String color) {
		if (!Double.isFinite(offset)) {
			return;
		}
		Integer parsed = CanvasColor.parse(color);
		if (parsed == null) {
			return;
		}
		stops.add(new Stop(Math.max(0.0, Math.min(1.0, offset)), parsed));
	}

	/** The Skija shader for the current stops, or null when fewer than two are usable. */
	@Nullable Shader shader() {
		if (stops.size() < 2) {
			return null;
		}
		List<Stop> sorted = new ArrayList<>(stops);
		sorted.sort((left, right) -> Double.compare(left.offset(), right.offset()));
		int[] colors = new int[sorted.size()];
		float[] positions = new float[sorted.size()];
		for (int index = 0; index < sorted.size(); index++) {
			Stop stop = sorted.get(index);
			colors[index] = stop.color();
			positions[index] = (float) stop.offset();
		}
		if (radial) {
			return Shader.makeRadialGradient((float) x0, (float) y0, (float) Math.max(0, r0), colors, positions);
		}
		return Shader.makeLinearGradient((float) x0, (float) y0, (float) x1, (float) y1, colors, positions);
	}
}
