package dev.gravaai.services.elements;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ImageInfo;

import dev.gravaai.core.project.ElementComponent;
import dev.gravaai.core.project.SceneElement;

/**
 * Plan risk R1 gate: a 1080p title card and a 200-particle component, per element-frame.
 * Manual: {@code mvn -pl gravaai-services test -Dtest=ElementPerfBenchmark -Dgravaai.perf=true}.
 */
class ElementPerfBenchmark {

	static final String PARTICLES = """
			export const durationInFrames = 120;
			export function render(ctx, { frame, fps, width, height }) {
			  const t = frame / fps;
			  for (let i = 0; i < 200; i++) {
			    const a = random('a' + i) * Math.PI * 2;
			    const s = 300 + random('s' + i) * 700;
			    ctx.save();
			    ctx.translate(width / 2 + Math.cos(a) * s * t, height / 2 + Math.sin(a) * s * t + 600 * t * t);
			    ctx.rotate(t * 4 + i);
			    ctx.fillStyle = i % 2 ? '#ffd23f' : '#5aa9ff';
			    ctx.fillRect(-8, -4, 16, 8);
			    ctx.restore();
			  }
			}
			""";

	@Test
	void measure() {
		if (!Boolean.getBoolean("gravaai.perf")) {
			return;
		}
		List<ElementComponent> components = List.of(new ElementComponent("particles", PARTICLES, ""),
				new ElementComponent("empty", "export function render(ctx) {}", ""),
				new ElementComponent("mathonly", "export function render(ctx, { frame }) { let s = 0; for (let i = 0; i < 200; i++) { const a = random('a' + i) * 6.28; s += Math.cos(a) * frame + Math.sin(a); } if (s === 1.5) ctx.fillRect(0,0,1,1); }", ""),
				new ElementComponent("callsonly", "export function render(ctx) { for (let i = 0; i < 200; i++) { ctx.save(); ctx.translate(i, i); ctx.rotate(0.1); ctx.fillStyle = '#ffd23f'; ctx.fillRect(-8, -4, 16, 8); ctx.restore(); } }", ""));
		SceneElement mathonly = new SceneElement("element-4", "mathonly", 0, 4000, SceneElement.Box.FULL, SceneElement.noProps(), 0);
		SceneElement callsonly = new SceneElement("element-5", "callsonly", 0, 4000, SceneElement.Box.FULL, SceneElement.noProps(), 0);
		SceneElement empty = new SceneElement("element-3", "empty", 0, 4000, SceneElement.Box.FULL,
				SceneElement.noProps(), 0);
		SceneElement title = new SceneElement("element-1", "gravaai/title-card", 0, 4000,
				SceneElement.Box.FULL, SceneElement.noProps(), 0);
		SceneElement particles = new SceneElement("element-2", "particles", 0, 4000,
				SceneElement.Box.FULL, SceneElement.noProps(), 0);
		try (JsElementRuntime runtime = new JsElementRuntime(new ElementDiagnostics());
				Bitmap bitmap = new Bitmap()) {
			bitmap.allocPixels(ImageInfo.makeN32(1920, 1080, ColorAlphaType.PREMUL));
			try (Canvas canvas = new Canvas(bitmap)) {
				for (SceneElement element : List.of(empty, mathonly, callsonly, title, particles)) {
					for (int round = 0; round < 3; round++) {
						long start = System.nanoTime();
						int frames = 240;
						for (int frame = 0; frame < frames; frame++) {
							canvas.clear(0);
							runtime.render(canvas, 1920, 1080, frame * 1000.0 / 60, List.of(element), components);
						}
						double ms = (System.nanoTime() - start) / 1e6 / frames;
						System.out.printf("PERF %s round %d: %.3f ms/frame%n", element.component(), round, ms);
					}
				}
			}
		}
	}
}
