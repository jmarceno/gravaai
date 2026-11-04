package dev.gravaai.services.elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.graalvm.polyglot.Engine;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

import dev.gravaai.core.project.ElementAsset;
import dev.gravaai.core.project.ElementComponent;
import dev.gravaai.core.project.ElementMotion;
import dev.gravaai.core.project.SceneElement;
import dev.gravaai.media.compose.anim.MotionEvaluator;
import dev.gravaai.media.gl.SkiaGlSurface;
import dev.gravaai.services.extensions.canvas.Canvas2D;

/**
 * Renders the elements lane with component modules on GraalJS (the Remotion "component" model,
 * minus the browser): every active element is drawn by its component's {@code render(ctx, …)} as
 * a pure function of the frame, into the compositor's Skija canvas.
 *
 * <p>Geometry: components draw in design pixels of a 1920-wide frame, so an element looks the
 * same in a 640 px {@code frames} capture and a 4K export; {@code width}/{@code height} are the
 * element box in those units and the canvas is clipped to the box.
 *
 * <p>Threads: each render thread gets its own contexts (a GraalJS context must never be entered
 * from two threads), so an export never waits for the preview. Errors are drawn as a red box with
 * the message, reported to the session's {@link ElementDiagnostics}, and remembered so an export
 * can fail instead of silently dropping the element.
 */
public final class JsElementRuntime implements AutoCloseable {

	/** Width of the design space components draw in. */
	public static final double DESIGN_WIDTH = 1920;

	private static final System.Logger LOG = System.getLogger(JsElementRuntime.class.getName());

	private final Engine engine = CompiledComponent.newEngine();
	private final ElementHost host = new ElementHost();
	private final ElementDiagnostics diagnostics;
	private final ThreadLocal<Map<String, Slot>> slots = ThreadLocal.withInitial(HashMap::new);
	private final List<CompiledComponent> all = new CopyOnWriteArrayList<>();
	private final List<ElementDiagnostics.ElementError> failures = new CopyOnWriteArrayList<>();
	private volatile boolean closed;

	/** A compiled component, or the compile error for that exact source. */
	private record Slot(String source, Optional<CompiledComponent> compiled,
			Optional<ComponentException> error) {}

	public JsElementRuntime(ElementDiagnostics diagnostics) {
		this.diagnostics = diagnostics;
	}

	/** What one render call draws. {@code space} empty draws both spaces (raster probes). */
	public record Request(List<SceneElement> elements, List<ElementComponent> components,
			List<ElementAsset> assets, Optional<SkiaGlSurface> gpu, Optional<SceneElement.Space> space) {

		public Request {
			elements = List.copyOf(elements);
			components = List.copyOf(components);
			assets = List.copyOf(assets);
		}

		public static Request of(List<SceneElement> elements, List<ElementComponent> components) {
			return new Request(elements, components, List.of(), Optional.empty(), Optional.empty());
		}
	}

	/**
	 * Draws every element active at {@code timeMs} in draw order.
	 *
	 * @param canvas the compositor canvas (top-left origin, output pixels)
	 * @param outputWidth output frame width
	 * @param outputHeight output frame height
	 * @param timeMs exact timeline time
	 * @param elements the elements lane
	 * @param components the project's components (built-ins resolve by name)
	 */
	public void render(Canvas canvas, int outputWidth, int outputHeight, double timeMs,
			List<SceneElement> elements, List<ElementComponent> components) {
		render(canvas, outputWidth, outputHeight, timeMs, Request.of(elements, components));
	}

	/** Draws the active elements of {@code request} (filtered by its space when set). */
	public void render(Canvas canvas, int outputWidth, int outputHeight, double timeMs,
			Request request) {
		if (closed || request.elements().isEmpty()) {
			return;
		}
		List<SceneElement> active = new ArrayList<>();
		for (SceneElement element : request.elements()) {
			if (element.isActiveAt(timeMs)
					&& request.space().map(space -> space == element.space()).orElse(true)) {
				active.add(element);
			}
		}
		if (active.isEmpty()) {
			return;
		}
		active.sort(SceneElement.DRAW_ORDER);
		host.enter(new ElementHost.Scope(request.assets(), request.gpu()));
		try {
			for (SceneElement element : active) {
				long started = System.nanoTime();
				renderElement(canvas, outputWidth, outputHeight, timeMs, element, request.components());
				diagnostics.recordTiming(element.id(), System.nanoTime() - started);
			}
		} finally {
			host.exit();
		}
	}

	/** Every failure since this runtime was created, in order (exports fail on the first). */
	public List<ElementDiagnostics.ElementError> failures() {
		return List.copyOf(failures);
	}

	/** Compiles (or reuses) {@code component} on the calling thread and returns its exports. */
	public ComponentInfo describe(ElementComponent component) {
		Slot slot = slot(component);
		if (slot.error().isPresent()) {
			throw slot.error().get();
		}
		return slot.compiled().orElseThrow().info();
	}

	private void renderElement(Canvas canvas, int outputWidth, int outputHeight, double timeMs,
			SceneElement element, List<ElementComponent> components) {
		double scale = outputWidth / DESIGN_WIDTH;
		float left = (float) (element.box().x() / 100.0 * outputWidth);
		float top = (float) (element.box().y() / 100.0 * outputHeight);
		float boxWidth = (float) (element.box().width() / 100.0 * outputWidth);
		float boxHeight = (float) (element.box().height() / 100.0 * outputHeight);
		Optional<ElementComponent> component = BuiltinComponents.resolve(element.component(),
				components);
		int saveCount = canvas.save();
		try {
			if (component.isEmpty()) {
				throw new ComponentException("unknown component \"" + element.component() + "\"");
			}
			Slot slot = slot(component.get());
			if (slot.error().isPresent()) {
				throw slot.error().get();
			}
			CompiledComponent compiled = slot.compiled().orElseThrow();
			double fps = compiled.info().fps();
			ElementMotion.Blur blur = element.blur();
			double frameMs = 1000.0 / fps;
			int samples = blur.enabled() && moving(element, timeMs, blur.shutter() * frameMs)
					? blur.samples() : 1;
			for (int sample = 0; sample < samples; sample++) {
				// Oldest sample first; the newest (the real frame) draws last at full strength.
				double behind = samples == 1 ? 0
						: blur.shutter() * frameMs * (samples - 1 - sample) / (samples - 1);
				double sampleMs = Math.max(element.startMs(), timeMs - behind);
				double strength = sample == samples - 1 ? 1 : 0.55 * (sample + 1) / samples;
				drawSample(canvas, outputWidth, outputHeight, sampleMs, element, compiled, scale,
						left, top, boxWidth, boxHeight, strength);
			}
		} catch (ComponentException error) {
			canvas.restoreToCount(saveCount);
			saveCount = canvas.save();
			canvas.translate(left, top);
			drawError(canvas, boxWidth, boxHeight, element, error, scale);
			ElementDiagnostics.ElementError record = new ElementDiagnostics.ElementError(
					element.id(), timeMs, error.describe(), error.line(),
					ElementDiagnostics.fingerprint(element, components));
			failures.add(record);
			diagnostics.report(record);
		} catch (RuntimeException error) {
			canvas.restoreToCount(saveCount);
			saveCount = canvas.save();
			canvas.translate(left, top);
			ComponentException wrapped = new ComponentException(String.valueOf(error));
			drawError(canvas, boxWidth, boxHeight, element, wrapped, scale);
			ElementDiagnostics.ElementError record = new ElementDiagnostics.ElementError(
					element.id(), timeMs, wrapped.describe(), 0,
					ElementDiagnostics.fingerprint(element, components));
			failures.add(record);
			diagnostics.report(record);
			LOG.log(System.Logger.Level.WARNING, "[elements] " + element.id() + ": " + error);
		} finally {
			canvas.restoreToCount(saveCount);
		}
	}

	/**
	 * Whether the trail is worth drawing: an element with host motion only blurs while that motion
	 * changes its pose within the shutter window, so a resting element costs one draw, not
	 * {@code samples}. Without host motion the component's own animation is what blurs.
	 */
	private static boolean moving(SceneElement element, double timeMs, double shutterMs) {
		if (element.motion().isEmpty()) {
			return true;
		}
		double now = timeMs - element.startMs();
		double before = Math.max(0, now - shutterMs);
		return !MotionEvaluator.evaluate(element.motion(), now, element.durationMs())
				.equals(MotionEvaluator.evaluate(element.motion(), before, element.durationMs()));
	}

	/** One draw of the element at {@code sampleMs}: host motion around the box, then the component. */
	private void drawSample(Canvas canvas, int outputWidth, int outputHeight, double sampleMs,
			SceneElement element, CompiledComponent compiled, double scale, float left, float top,
			float boxWidth, float boxHeight, double strength) {
		double localMs = sampleMs - element.startMs();
		MotionEvaluator.Pose pose = MotionEvaluator.evaluate(element.motion(), localMs,
				element.durationMs());
		double opacity = pose.opacity() * strength;
		if (opacity <= 0.001) {
			return;
		}
		int saved = opacity < 0.999
				? canvas.saveLayerAlpha(null, (int) Math.round(opacity * 255))
				: canvas.save();
		try {
			if (!pose.isRest()) {
				float centerX = left + boxWidth / 2 + (float) (pose.translateX() / 100.0 * outputWidth);
				float centerY = top + boxHeight / 2 + (float) (pose.translateY() / 100.0 * outputHeight);
				canvas.translate(centerX, centerY);
				canvas.rotate((float) pose.rotate());
				canvas.scale((float) pose.scale(), (float) pose.scale());
				canvas.translate(-boxWidth / 2, -boxHeight / 2);
			} else {
				canvas.translate(left, top);
			}
			canvas.clipRect(Rect.makeWH(boxWidth, boxHeight));
			double fps = compiled.info().fps();
			double frame = localMs * fps / 1000.0;
			double durationInFrames = element.durationMs() * fps / 1000.0;
			canvas.scale((float) scale, (float) scale);
			try (Canvas2D ctx = new Canvas2D(canvas, boxWidth / scale, boxHeight / scale)) {
				ctx.setFontResolver(host.fonts());
				compiled.render(ctx, frame, durationInFrames, sampleMs, boxWidth / scale,
						boxHeight / scale, element.props(), element.id());
			}
		} finally {
			canvas.restoreToCount(saved);
		}
	}

	private Slot slot(ElementComponent component) {
		Map<String, Slot> threadSlots = slots.get();
		Slot slot = threadSlots.get(component.name());
		if (slot != null && slot.source().equals(component.source())
				&& slot.compiled().map(CompiledComponent::usable).orElse(true)) {
			return slot;
		}
		if (slot != null) {
			slot.compiled().ifPresent(old -> {
				all.remove(old);
				old.close();
			});
		}
		Slot next;
		try {
			CompiledComponent compiled = CompiledComponent.compile(engine, component.name(),
					component.source(), host);
			all.add(compiled);
			next = new Slot(component.source(), Optional.of(compiled), Optional.empty());
		} catch (ComponentException error) {
			next = new Slot(component.source(), Optional.empty(), Optional.of(error));
		}
		threadSlots.put(component.name(), next);
		return next;
	}

	/** Remotion-style error overlay: a red box with the element id and the message. */
	private static void drawError(Canvas canvas, float width, float height, SceneElement element,
			ComponentException error, double scale) {
		try (Paint fill = new Paint(); Paint stroke = new Paint(); Paint text = new Paint()) {
			fill.setColor(0xB0B00020);
			canvas.drawRect(Rect.makeWH(width, height), fill);
			stroke.setColor(0xFFFF4060);
			stroke.setMode(PaintMode.STROKE);
			stroke.setStrokeWidth((float) Math.max(2, 4 * scale));
			canvas.drawRect(Rect.makeWH(width, height), stroke);
			text.setColor(0xFFFFFFFF);
			float size = (float) Math.max(10, 26 * scale);
			Typeface typeface = FontMgr.getDefault().matchFamilyStyle("sans-serif", FontStyle.BOLD);
			try (Font font = typeface == null ? new Font() : new Font(typeface, size)) {
				font.setSize(size);
				canvas.save();
				canvas.clipRect(Rect.makeWH(width, height));
				float y = size * 1.4f;
				canvas.drawString("Element " + element.id() + " (" + element.component() + ")",
						size * 0.6f, y, font, text);
				for (String line : wrap(error.describe(), Math.max(10, (int) (width / (size * 0.55f))))) {
					y += size * 1.25f;
					if (y > height) {
						break;
					}
					canvas.drawString(line, size * 0.6f, y, font, text);
				}
				canvas.restore();
			}
		}
	}

	private static List<String> wrap(String message, int columns) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : message.split("\\s+")) {
			if (current.length() > 0 && current.length() + word.length() + 1 > columns) {
				lines.add(current.toString());
				current.setLength(0);
			}
			if (current.length() > 0) {
				current.append(' ');
			}
			current.append(word);
		}
		if (current.length() > 0) {
			lines.add(current.toString());
		}
		return lines;
	}

	@Override
	public void close() {
		closed = true;
		for (CompiledComponent compiled : all) {
			compiled.close();
		}
		all.clear();
		try {
			engine.close(true);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.DEBUG, "[elements] engine close: " + error);
		}
	}
}
