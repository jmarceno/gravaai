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

import dev.gravaai.core.project.ElementComponent;
import dev.gravaai.core.project.SceneElement;
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
		if (closed || elements.isEmpty()) {
			return;
		}
		List<SceneElement> active = new ArrayList<>();
		for (SceneElement element : elements) {
			if (element.isActiveAt(timeMs)) {
				active.add(element);
			}
		}
		active.sort(SceneElement.DRAW_ORDER);
		for (SceneElement element : active) {
			renderElement(canvas, outputWidth, outputHeight, timeMs, element, components);
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
			canvas.translate(left, top);
			canvas.clipRect(Rect.makeWH(boxWidth, boxHeight));
			if (component.isEmpty()) {
				throw new ComponentException("unknown component \"" + element.component() + "\"");
			}
			Slot slot = slot(component.get());
			if (slot.error().isPresent()) {
				throw slot.error().get();
			}
			CompiledComponent compiled = slot.compiled().orElseThrow();
			double fps = compiled.info().fps();
			double localMs = timeMs - element.startMs();
			double frame = localMs * fps / 1000.0;
			double durationInFrames = element.durationMs() * fps / 1000.0;
			canvas.scale((float) scale, (float) scale);
			try (Canvas2D ctx = new Canvas2D(canvas, boxWidth / scale, boxHeight / scale)) {
				compiled.render(ctx, frame, durationInFrames, timeMs, boxWidth / scale,
						boxHeight / scale, element.props(), element.id());
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
					component.source());
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
