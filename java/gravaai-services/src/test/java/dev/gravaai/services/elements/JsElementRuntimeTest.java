package dev.gravaai.services.elements;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ImageInfo;

import dev.gravaai.core.project.ElementComponent;
import dev.gravaai.core.project.SceneElement;

class JsElementRuntimeTest {

	private static final int W = 192;
	private static final int H = 108;

	private static ElementComponent component(String source) {
		return new ElementComponent("test", source, "");
	}

	private static SceneElement element(String component, long endMs) {
		return new SceneElement("element-1", component, 0, endMs, SceneElement.Box.FULL,
				SceneElement.noProps(), 0);
	}

	private static byte[] render(JsElementRuntime runtime, SceneElement element,
			List<ElementComponent> components, double timeMs) {
		try (Bitmap bitmap = new Bitmap()) {
			bitmap.allocPixels(ImageInfo.makeN32(W, H, ColorAlphaType.PREMUL));
			try (Canvas canvas = new Canvas(bitmap)) {
				canvas.clear(0);
				runtime.render(canvas, W, H, timeMs, List.of(element), components);
			}
			return bitmap.readPixels();
		}
	}

	private static long opaquePixels(byte[] rgba) {
		long count = 0;
		for (int index = 3; index < rgba.length; index += 4) {
			if ((rgba[index] & 0xFF) > 0) {
				count++;
			}
		}
		return count;
	}

	@Test
	void builtinTitleCardDrawsAndAnimates() {
		try (JsElementRuntime runtime = new JsElementRuntime(new ElementDiagnostics())) {
			SceneElement title = element("gravaai/title-card", 3000);
			byte[] start = render(runtime, title, List.of(), 0);
			byte[] middle = render(runtime, title, List.of(), 1500);
			assertTrue(runtime.failures().isEmpty(), () -> runtime.failures().toString());
			assertTrue(opaquePixels(middle) > 500, "title card visible mid-way");
			assertTrue(opaquePixels(start) < opaquePixels(middle), "pops in from small");
		}
	}

	@Test
	void everyBuiltinValidates() {
		for (ElementComponent builtin : BuiltinComponents.all()) {
			ComponentInfo info = ComponentValidator.validate(builtin);
			assertTrue(info.durationInFrames() > 0, builtin.name());
			assertFalse(info.description().isEmpty(), builtin.name());
		}
	}

	@Test
	void kernelGlobalsMatchJava() {
		String source = """
				export function render(ctx, { frame, width, height }) {
				  const v = interpolate(frame, [0, 10], [0, 100], { extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic) });
				  const s = spring({ frame, fps: 30 });
				  ctx.fillStyle = 'rgb(' + Math.round(v * 2.55) + ',' + Math.round(Math.min(1, s) * 255) + ',0)';
				  ctx.fillRect(0, 0, width, height);
				}
				""";
		try (JsElementRuntime runtime = new JsElementRuntime(new ElementDiagnostics())) {
			byte[] pixels = render(runtime, element("test", 1000), List.of(component(source)),
					1000.0 * 5 / 30);
			assertTrue(runtime.failures().isEmpty(), () -> runtime.failures().toString());
			double expected = dev.gravaai.media.compose.anim.Interpolation.interpolate(5,
					new double[] {0, 10}, new double[] {0, 100}, new dev.gravaai.media.compose.anim
							.Interpolation.Options(dev.gravaai.media.compose.anim.Easing.out(
									dev.gravaai.media.compose.anim.Easing.CUBIC),
									dev.gravaai.media.compose.anim.Interpolation.Extrapolation.EXTEND,
									dev.gravaai.media.compose.anim.Interpolation.Extrapolation.CLAMP));
			int red = pixels[2] & 0xFF; // N32 on little-endian is BGRA
			assertEquals(Math.round(expected * 2.55), red, 1);
		}
	}

	@Test
	void throwingComponentDrawsErrorAndReportsLine() {
		String source = "export function render(ctx) {\n  const x = 1;\n  throw new Error('boom');\n}\n";
		ElementDiagnostics diagnostics = new ElementDiagnostics();
		try (JsElementRuntime runtime = new JsElementRuntime(diagnostics)) {
			byte[] pixels = render(runtime, element("test", 1000), List.of(component(source)), 0);
			assertEquals(1, runtime.failures().size());
			assertTrue(runtime.failures().getFirst().message().contains("boom"));
			assertEquals(3, runtime.failures().getFirst().line());
			assertTrue(opaquePixels(pixels) > W * H / 2, "error box covers the element");
		}
	}

	@Test
	void endlessLoopHitsTheStatementLimit() {
		String source = "export function render(ctx) { while (true) {} }";
		try (JsElementRuntime runtime = new JsElementRuntime(new ElementDiagnostics())) {
			long started = System.nanoTime();
			render(runtime, element("test", 1000), List.of(component(source)), 0);
			assertEquals(1, runtime.failures().size());
			assertTrue(runtime.failures().getFirst().message().contains("too long"),
					runtime.failures().getFirst().message());
			assertTrue((System.nanoTime() - started) / 1e9 < 30, "bounded");
		}
	}

	@Test
	void mathRandomAndDateAreFrozenPerFrame() {
		String source = """
				export function render(ctx, { width, height }) {
				  ctx.fillStyle = 'rgb(' + Math.floor(Math.random() * 255) + ',' + (Date.now() % 255) + ',' + Math.floor(random('x') * 255) + ')';
				  ctx.fillRect(0, 0, width, height);
				}
				""";
		try (JsElementRuntime first = new JsElementRuntime(new ElementDiagnostics());
				JsElementRuntime second = new JsElementRuntime(new ElementDiagnostics())) {
			List<ElementComponent> components = List.of(component(source));
			byte[] a = render(first, element("test", 1000), components, 400);
			render(first, element("test", 1000), components, 100);
			byte[] b = render(second, element("test", 1000), components, 400);
			assertArrayEquals(a, b);
		}
	}

	@Test
	void validatorRejectsHiddenState() {
		String source = "let calls = 0;\nexport function render(ctx, { width, height }) {\n"
				+ "  calls++;\n  if (calls % 2) { ctx.fillStyle = 'red'; ctx.fillRect(0, 0, width, height); }\n}\n";
		ComponentException error = assertThrows(ComponentException.class,
				() -> ComponentValidator.validate(component(source)));
		assertTrue(error.getMessage().contains("deterministic"), error.getMessage());
	}

	@Test
	void validatorReportsSyntaxErrorsWithLine() {
		ComponentException error = assertThrows(ComponentException.class,
				() -> ComponentValidator.validate(component("export function render(ctx) {\n  ctx.fillRect(0, 0,\n}\n")));
		assertTrue(error.line() > 0, error.describe());
	}

	@Test
	void validatorRequiresRender() {
		ComponentException error = assertThrows(ComponentException.class,
				() -> ComponentValidator.validate(component("export const fps = 30;")));
		assertTrue(error.getMessage().contains("render"));
	}

	@Test
	void describeReadsExports() {
		ComponentInfo info = ComponentValidator.describe(component("""
				export const fps = 60;
				export const durationInFrames = 120;
				export const defaultProps = { title: 'x' };
				export const propsSchema = { title: 'string' };
				export function render() {}
				"""));
		assertEquals(60, info.fps());
		assertEquals(2000, info.defaultDurationMs());
		assertEquals("x", info.defaultProps().get("title").asString());
	}

	@Test
	void canvasAdditionsWork() {
		String source = """
				export function render(ctx, { width, height }) {
				  ctx.font = 'bold 40px sans-serif';
				  const m = ctx.measureText('Hello');
				  if (!(m.width > 40)) throw new Error('measureText width ' + m.width);
				  ctx.fillStyle = '#fff';
				  ctx.beginPath(); ctx.roundRect(10, 10, 100, 60, [12]); ctx.fill();
				  ctx.beginPath(); ctx.ellipse(width / 2, height / 2, 80, 40, 0.3, 0, Math.PI * 2); ctx.fill();
				  ctx.setLineDash([10, 5]); ctx.lineDashOffset = 2; ctx.strokeStyle = 'red';
				  ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(width, height); ctx.stroke();
				  ctx.filter = 'blur(4px)'; ctx.fillRect(0, 0, 20, 20);
				  if (ctx.getLineDash().length !== 2) throw new Error('dash');
				}
				""";
		try (JsElementRuntime runtime = new JsElementRuntime(new ElementDiagnostics())) {
			render(runtime, element("test", 1000), List.of(component(source)), 0);
			assertTrue(runtime.failures().isEmpty(), () -> runtime.failures().toString());
		}
	}
}
