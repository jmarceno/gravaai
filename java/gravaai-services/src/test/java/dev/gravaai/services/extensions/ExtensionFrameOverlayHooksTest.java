package dev.gravaai.services.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.services.extensions.canvas.Canvas2D;

/**
 * The services-side bridge from the extension host to the media render-hook seam (plan §5.18):
 * the adapter wraps the compositor canvas in {@link Canvas2D}, builds the documented
 * {@code RenderHookContext}, maps the phase names of {@code EXTENSIONS.md}, isolates broken
 * extensions and keeps the compositor's save stack intact.
 */
class ExtensionFrameOverlayHooksTest {

	private static final FrameOverlayHooks.FrameInfo FRAME =
			new FrameOverlayHooks.FrameInfo(64, 64, 0);

	@Test
	@DisplayName("renders a registered hook through the compositor canvas seam")
	void rendersHooks(@TempDir Path temp) throws Exception {
		Path extensions = temp.resolve("extensions");
		ExtensionTestSupport.installExample(extensions);
		try (ExtensionHost host = new ExtensionHost(extensions)) {
			assertEquals(1, host.enableAll().size(), "the example bundle must be discovered");
			assertEquals(1, host.activeCount(), "the example bundle must activate");
			assertEquals(1, host.renderHooks().count(RenderHookPhase.FINAL),
					"the example bundle registers one final hook");
			ExtensionFrameOverlayHooks hooks = new ExtensionFrameOverlayHooks(host);
			try (RasterCanvas raster = new RasterCanvas(200, 100)) {
				hooks.render(FrameOverlayHooks.Phase.FINAL, raster.skijaCanvas(),
						new FrameOverlayHooks.FrameInfo(200, 100, 0));
				assertEquals(0xFF2563EB, raster.argb(14, 14),
						"the hello-overlay banner must be drawn on the compositor canvas");
				assertEquals(0x00000000, raster.argb(150, 80),
						"the hook must not paint outside the banner");
			}
		}
	}

	@Test
	@DisplayName("passes the documented RenderHookContext fields, including durationMs")
	void renderHookContextFields(@TempDir Path temp) throws Exception {
		Path extensions = temp.resolve("extensions");
		ExtensionTestSupport.writeExtension(extensions, "duration.extension",
				ExtensionTestSupport.manifest("com.example.duration", "[\"render\"]"),
				"""
						export function activate(api) {
						  api.registerRenderHook("final", (ctx) => {
						    ctx.ctx.fillStyle = "#00ff00";
						    ctx.ctx.fillRect(0, 0, ctx.durationMs / 100, 2);
						    ctx.ctx.fillRect(0, 4, ctx.width - 60, 1);
						    if (ctx.timeMs === 1500) {
						      ctx.ctx.fillRect(0, 8, 2, 2);
						    }
						  });
						}
						export function deactivate() {}
						""");
		try (ExtensionHost host = new ExtensionHost(extensions)) {
			host.enableAll();
			ExtensionFrameOverlayHooks hooks = new ExtensionFrameOverlayHooks(host, () -> 1600);
			try (RasterCanvas raster = new RasterCanvas(64, 64)) {
				hooks.render(FrameOverlayHooks.Phase.FINAL, raster.skijaCanvas(),
						new FrameOverlayHooks.FrameInfo(64, 64, 1500));
				assertEquals(255, raster.green(15, 0), "durationMs 1600 must fill 16px");
				assertTrue(raster.transparent(17, 0), "durationMs must not fill beyond 16px");
				assertEquals(255, raster.green(2, 4),
						"width must be the compositor width (64 - 60 = 4px)");
				assertTrue(raster.transparent(8, 4),
						"the width-derived rect must stop at the compositor width");
				assertEquals(255, raster.green(1, 9), "timeMs must be the frame time");
				assertTrue(raster.transparent(1, 11),
						"a different timeMs must not draw the conditional pixel");
			}
		}
	}

	@Test
	@DisplayName("a throwing hook is skipped and leaves the canvas untouched")
	void brokenHookIsSkipped(@TempDir Path temp) throws Exception {
		Path extensions = temp.resolve("extensions");
		ExtensionTestSupport.writeExtension(extensions, "broken.extension",
				ExtensionTestSupport.manifest("com.example.broken", "[\"render\"]"),
				"""
						export function activate(api) {
						  api.registerRenderHook("final", () => {
						    throw new Error("boom");
						  });
						}
						export function deactivate() {}
						""");
		try (ExtensionHost host = new ExtensionHost(extensions)) {
			host.enableAll();
			ExtensionFrameOverlayHooks hooks = new ExtensionFrameOverlayHooks(host);
			try (RasterCanvas raster = new RasterCanvas(32, 32)) {
				// The registry logs and skips the failure; the adapter must not rethrow it.
				hooks.render(FrameOverlayHooks.Phase.FINAL, raster.skijaCanvas(),
						new FrameOverlayHooks.FrameInfo(32, 32, 0));
				assertTrue(raster.transparent(8, 8), "a failed hook must not draw anything");
			}
		}
	}

	@Test
	@DisplayName("an unbalanced canvas save in a hook is unwound after the hook")
	void unbalancedSaveIsUnwound(@TempDir Path temp) throws Exception {
		Path extensions = temp.resolve("extensions");
		ExtensionTestSupport.writeExtension(extensions, "leaky.extension",
				ExtensionTestSupport.manifest("com.example.leaky", "[\"render\"]"),
				"""
						export function activate(api) {
						  api.registerRenderHook("final", (ctx) => {
						    ctx.ctx.save();
						    ctx.ctx.fillStyle = "#ff0000";
						    ctx.ctx.fillRect(0, 0, 8, 8);
						  });
						}
						export function deactivate() {}
						""");
		try (ExtensionHost host = new ExtensionHost(extensions)) {
			host.enableAll();
			ExtensionFrameOverlayHooks hooks = new ExtensionFrameOverlayHooks(host);
			try (RasterCanvas raster = new RasterCanvas(32, 32)) {
				int saveCount = raster.skijaCanvas().getSaveCount();
				hooks.render(FrameOverlayHooks.Phase.FINAL, raster.skijaCanvas(),
						new FrameOverlayHooks.FrameInfo(32, 32, 0));
				assertEquals(saveCount, raster.skijaCanvas().getSaveCount(),
						"the compositor save stack must be balanced after a hook");
				assertEquals(0xFFFF0000, raster.argb(4, 4), "the hook drawing still applies");
			}
		}
	}

	@Test
	@DisplayName("adapters sharing a host serialise hook execution (no concurrent Graal context entry)")
	void adaptersSerialiseOnTheHost(@TempDir Path temp) throws Exception {
		Path extensions = temp.resolve("extensions");
		ExtensionTestSupport.writeExtension(extensions, "slow.extension",
				ExtensionTestSupport.manifest("com.example.slow", "[\"render\"]"),
				"""
						export function activate(api) {
						  api.registerRenderHook("final", (ctx) => {
						    ctx.ctx.fillStyle = "#00ff00";
						    ctx.ctx.fillRect(0, 0, ctx.durationMs / 100, 1);
						  });
						}
						export function deactivate() {}
						""");
		try (ExtensionHost host = new ExtensionHost(extensions)) {
			host.enableAll();
			AtomicInteger inFlight = new AtomicInteger();
			AtomicBoolean overlapped = new AtomicBoolean();
			DoubleSupplier probe = () -> {
				if (inFlight.incrementAndGet() > 1) {
					overlapped.set(true);
				}
				try {
					Thread.sleep(2);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				inFlight.decrementAndGet();
				return 1000;
			};
			ExtensionFrameOverlayHooks reviewAdapter = new ExtensionFrameOverlayHooks(host, probe);
			ExtensionFrameOverlayHooks exportAdapter = new ExtensionFrameOverlayHooks(host, probe);
			AtomicBoolean reviewDrew = new AtomicBoolean();
			AtomicBoolean exportDrew = new AtomicBoolean();
			Thread review = new Thread(() -> {
				try (RasterCanvas raster = new RasterCanvas(32, 32)) {
					reviewAdapter.render(FrameOverlayHooks.Phase.FINAL, raster.skijaCanvas(),
							new FrameOverlayHooks.FrameInfo(32, 32, 0));
					reviewDrew.set(raster.green(5, 0) == 255);
				}
			});
			Thread export = new Thread(() -> {
				try (RasterCanvas raster = new RasterCanvas(32, 32)) {
					exportAdapter.render(FrameOverlayHooks.Phase.FINAL, raster.skijaCanvas(),
							new FrameOverlayHooks.FrameInfo(32, 32, 0));
					exportDrew.set(raster.green(5, 0) == 255);
				}
			});
			review.start();
			export.start();
			review.join(30_000);
			export.join(30_000);
			assertFalse(review.isAlive() || export.isAlive(), "hook rendering timed out");
			assertTrue(reviewDrew.get(), "the preview adapter must draw");
			assertTrue(exportDrew.get(), "the export adapter must draw");
			assertFalse(overlapped.get(),
					"two adapters entered the extension host concurrently");
		}
	}

	@Test
	@DisplayName("the reserved background phase is never dispatched")
	void backgroundIsReserved(@TempDir Path temp) throws Exception {
		Path extensions = temp.resolve("extensions");
		ExtensionTestSupport.writeExtension(extensions, "background.extension",
				ExtensionTestSupport.manifest("com.example.background", "[\"render\"]"),
				"""
						export function activate(api) {
						  api.registerRenderHook("background", (ctx) => {
						    ctx.ctx.fillStyle = "#ff0000";
						    ctx.ctx.fillRect(0, 0, 8, 8);
						  });
						}
						export function deactivate() {}
						""");
		try (ExtensionHost host = new ExtensionHost(extensions)) {
			host.enableAll();
			ExtensionFrameOverlayHooks hooks = new ExtensionFrameOverlayHooks(host);
			try (RasterCanvas raster = new RasterCanvas(32, 32)) {
				hooks.render(FrameOverlayHooks.Phase.BACKGROUND, raster.skijaCanvas(),
						new FrameOverlayHooks.FrameInfo(32, 32, 0));
				assertTrue(raster.transparent(4, 4), "background hooks must never run");
			}
		}
	}

	@Test
	@DisplayName("no active extension means no drawing, so frames stay unchanged")
	void noExtensionsIsANoOp(@TempDir Path temp) throws Exception {
		try (ExtensionHost host = new ExtensionHost(temp.resolve("extensions"))) {
			assertEquals(0, host.enableAll().size());
			ExtensionFrameOverlayHooks hooks = new ExtensionFrameOverlayHooks(host);
			try (RasterCanvas raster = new RasterCanvas(32, 32)) {
				for (FrameOverlayHooks.Phase phase : FrameOverlayHooks.Phase.values()) {
					hooks.render(phase, raster.skijaCanvas(),
							new FrameOverlayHooks.FrameInfo(32, 32, 0));
				}
				for (int x = 0; x < 32; x += 4) {
					for (int y = 0; y < 32; y += 4) {
						assertTrue(raster.transparent(x, y), "no hooks may draw pixels");
					}
				}
			}
		}
	}

	@Test
	@DisplayName("the media phase enum mirrors the extension RenderHookPhase wires")
	void phaseWiresMatchExt() {
		for (RenderHookPhase phase : RenderHookPhase.values()) {
			FrameOverlayHooks.Phase media = FrameOverlayHooks.Phase.fromWire(phase.wire());
			assertNotNull(media, "missing media phase for wire " + phase.wire());
			assertEquals(phase.wire(), media.wire());
			assertEquals(phase.dispatched(), media.dispatched(),
					"dispatch flag differs for " + phase.wire());
		}
		assertFalse(FrameOverlayHooks.Phase.fromWire("background").dispatched());
		assertEquals(null, FrameOverlayHooks.Phase.fromWire("nope"));
	}
}
