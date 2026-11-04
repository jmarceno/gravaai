package dev.gravaai.services.elements;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ImageInfo;

import dev.gravaai.core.project.ElementComponent;
import dev.gravaai.core.project.SceneElement;

/**
 * Checks a component before an {@code edit} batch commits it, so an agent finds out about a
 * broken module from the op result rather than from a red box later. A component passes when it
 * compiles, declares valid metadata, renders without throwing at 0 %, 50 % and the last frame of
 * its {@code durationInFrames} (320 px wide, CPU raster), and renders the 50 % frame identically
 * twice in fresh contexts (no hidden state, no clock, no unseeded randomness).
 *
 * <p>{@link #describe} caches exports by component source, so planning an element op does not
 * re-evaluate a module it already knows.
 */
public final class ComponentValidator {

	private static final int WIDTH = 320;
	private static final int HEIGHT = 180;
	private static final int CACHE_SIZE = 64;

	private static final Map<String, ComponentInfo> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, ComponentInfo> eldest) {
			return size() > CACHE_SIZE;
		}
	};

	private ComponentValidator() {}

	/** Validates {@code component} as described above; throws {@link ComponentException}. */
	public static ComponentInfo validate(ElementComponent component) {
		ComponentInfo info;
		byte[] first;
		try (JsElementRuntime runtime = new JsElementRuntime(new ElementDiagnostics())) {
			info = runtime.describe(component);
			double fps = info.fps();
			long durationMs = Math.round(info.durationInFrames() * 1000.0 / fps);
			SceneElement probe = new SceneElement("validate", component.name(), 0,
					Math.max(1, durationMs), SceneElement.Box.FULL, SceneElement.noProps(), 0);
			double lastFrameMs = Math.max(0, (info.durationInFrames() - 1) * 1000.0 / fps);
			for (double timeMs : new double[] {0, durationMs / 2.0, lastFrameMs}) {
				render(runtime, probe, component, timeMs);
			}
			first = render(runtime, probe, component, durationMs / 2.0);
		}
		try (JsElementRuntime fresh = new JsElementRuntime(new ElementDiagnostics())) {
			double fps = info.fps();
			long durationMs = Math.round(info.durationInFrames() * 1000.0 / fps);
			SceneElement probe = new SceneElement("validate", component.name(), 0,
					Math.max(1, durationMs), SceneElement.Box.FULL, SceneElement.noProps(), 0);
			byte[] second = render(fresh, probe, component, durationMs / 2.0);
			if (!Arrays.equals(first, second)) {
				throw new ComponentException("render is not deterministic: the same frame drew "
						+ "different pixels twice. Draw only from frame/props (no module-level "
						+ "state that changes between calls); use random(seed) for randomness");
			}
		}
		synchronized (CACHE) {
			CACHE.put(component.source(), info);
		}
		return info;
	}

	/**
	 * The exports of {@code component}, from the cache when this exact source was seen before.
	 * Throws {@link ComponentException} when it does not compile.
	 */
	public static ComponentInfo describe(ElementComponent component) {
		synchronized (CACHE) {
			ComponentInfo cached = CACHE.get(component.source());
			if (cached != null && cached.name().equals(component.name())) {
				return cached;
			}
		}
		ComponentInfo info;
		try (JsElementRuntime runtime = new JsElementRuntime(new ElementDiagnostics())) {
			info = runtime.describe(component);
		}
		synchronized (CACHE) {
			CACHE.put(component.source(), info);
		}
		return info;
	}

	private static byte[] render(JsElementRuntime runtime, SceneElement probe,
			ElementComponent component, double timeMs) {
		ImageInfo imageInfo = ImageInfo.makeN32(WIDTH, HEIGHT, ColorAlphaType.PREMUL);
		try (Bitmap bitmap = new Bitmap()) {
			bitmap.allocPixels(imageInfo);
			try (Canvas canvas = new Canvas(bitmap)) {
				runtime.render(canvas, WIDTH, HEIGHT, timeMs, List.of(probe), List.of(component));
			}
			runtime.failures().stream().findFirst().ifPresent(error -> {
				throw new ComponentException("frame " + Math.round(timeMs * runtime.describe(
						component).fps() / 1000.0) + ": " + error.message(), error.line(), 0);
			});
			return bitmap.readPixels();
		}
	}
}
