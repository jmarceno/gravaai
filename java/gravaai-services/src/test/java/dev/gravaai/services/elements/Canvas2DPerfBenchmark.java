package dev.gravaai.services.elements;

import org.junit.jupiter.api.Test;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ImageInfo;

import dev.gravaai.services.extensions.canvas.Canvas2D;

/** Manual: the Java-only cost of the Canvas2D calls a particle component makes. */
class Canvas2DPerfBenchmark {
	@Test
	void measure() {
		if (!Boolean.getBoolean("gravaai.perf")) {
			return;
		}
		try (Bitmap bitmap = new Bitmap()) {
			bitmap.allocPixels(ImageInfo.makeN32(1920, 1080, ColorAlphaType.PREMUL));
			try (Canvas canvas = new Canvas(bitmap)) {
				for (int round = 0; round < 3; round++) {
					long start = System.nanoTime();
					for (int frame = 0; frame < 240; frame++) {
						try (Canvas2D ctx = new Canvas2D(canvas, 1920, 1080)) {
							for (int i = 0; i < 200; i++) {
								ctx.save();
								ctx.translate(i, i);
								ctx.rotate(0.1);
								ctx.setFillStyle("#ffd23f");
								ctx.fillRect(-8, -4, 16, 8);
								ctx.restore();
							}
						}
					}
					System.out.printf("PERF java-canvas round %d: %.3f ms/frame%n", round,
							(System.nanoTime() - start) / 1e6 / 240);
				}
			}
		}
	}
}
