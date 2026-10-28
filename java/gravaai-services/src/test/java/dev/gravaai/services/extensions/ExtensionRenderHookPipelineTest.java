package dev.gravaai.services.extensions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.media.compose.Background;
import dev.gravaai.media.compose.Compositor;
import dev.gravaai.media.compose.CompositorExportFrameProvider;
import dev.gravaai.media.compose.CompositorFrameAtTime;
import dev.gravaai.media.compose.FrameInput;
import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;

/**
 * End-to-end P3.3 acceptance: the repo example bundle
 * {@code extension-examples/hello-overlay} is loaded by {@link ExtensionHost}, its render hook is
 * bound to the compositor through {@link ExtensionFrameOverlayHooks}, and the drawing appears in
 * the composited frame of both the preview path ({@link Compositor#render}) and the export path
 * ({@link CompositorExportFrameProvider}) — the same compositor and phase order.
 */
class ExtensionRenderHookPipelineTest {

	private static final int WIDTH = 200;
	private static final int HEIGHT = 100;
	private static final int FRAME_RATE = 30;
	private static final Background BACKGROUND = Background.black();
	private static final RgbaFrame SOURCE = solid(100);

	private static RgbaFrame solid(int value) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
			buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(WIDTH, HEIGHT, buffer);
	}

	@Test
	@Timeout(120)
	@DisplayName("the example extension's final hook draws in the editor preview composite")
	void exampleExtensionDrawsInPreviewComposite(@TempDir Path temp) throws Exception {
		try (ExtensionHost host = hostWithExample(temp)) {
			ExtensionFrameOverlayHooks hooks = new ExtensionFrameOverlayHooks(host);
			ByteBuffer baseline = renderPreviewFrame(null);
			ByteBuffer hooked = renderPreviewFrame(hooks);
			assertPixel(baseline, 14, 14, 100, 100, 100, "preview baseline");
			assertPixel(hooked, 14, 14, 0x25, 0x63, 0xEB, "preview hello-overlay banner");
			assertFalse(Arrays.equals(bytes(baseline), bytes(hooked)),
					"the hook must change the composited preview frame");
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("the example extension's final hook draws in the export composite")
	void exampleExtensionDrawsInExportComposite(@TempDir Path temp) throws Exception {
		try (ExtensionHost host = hostWithExample(temp)) {
			ExtensionFrameOverlayHooks hooks = new ExtensionFrameOverlayHooks(host);
			ByteBuffer withoutHooks = renderExportFrame(null);
			ByteBuffer withHooks = renderExportFrame(hooks);
			assertPixel(withoutHooks, 14, 14, 100, 100, 100, "export baseline");
			assertPixel(withHooks, 14, 14, 0x25, 0x63, 0xEB, "export hello-overlay banner");
			assertFalse(Arrays.equals(bytes(withoutHooks), bytes(withHooks)),
					"the hook must change the exported frame");
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("no extensions registered leaves preview frames byte-identical")
	void noExtensionsIsByteIdentical(@TempDir Path temp) throws Exception {
		try (ExtensionHost host = new ExtensionHost(temp.resolve("extensions"))) {
			assertEquals(0, host.enableAll().size());
			ExtensionFrameOverlayHooks hooks = new ExtensionFrameOverlayHooks(host);
			assertArrayEquals(bytes(renderPreviewFrame(null)), bytes(renderPreviewFrame(hooks)),
					"an empty extension host must not change a single preview pixel");
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("a broken extension does not stop the working one from drawing")
	void brokenExtensionDoesNotBreakTheFrame(@TempDir Path temp) throws Exception {
		Path extensions = temp.resolve("extensions");
		ExtensionTestSupport.installExample(extensions);
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
			assertEquals(2, host.activeCount(), "both extensions must activate");
			ByteBuffer frame = renderPreviewFrame(new ExtensionFrameOverlayHooks(host));
			assertPixel(frame, 14, 14, 0x25, 0x63, 0xEB,
					"the working extension must still draw after the broken one fails");
		}
	}

	@Test
	@Timeout(60)
	@DisplayName("hook drawing and transform semantics are identical in preview and export phases")
	void previewAndExportUseTheSamePhases(@TempDir Path temp) throws Exception {
		List<FrameOverlayHooks.Phase> previewPhases = new java.util.ArrayList<>();
		List<FrameOverlayHooks.Phase> exportPhases = new java.util.ArrayList<>();
		FrameOverlayHooks recording = (phase, canvas, frame) -> phasesOf(previewPhases, phase);
		renderPreviewFrame(recording);
		FrameOverlayHooks exporting = (phase, canvas, frame) -> phasesOf(exportPhases, phase);
		renderExportFrame(exporting);
		List<FrameOverlayHooks.Phase> expected = List.of(FrameOverlayHooks.Phase.POST_VIDEO,
				FrameOverlayHooks.Phase.POST_ZOOM,
				FrameOverlayHooks.Phase.POST_CURSOR,
				FrameOverlayHooks.Phase.POST_WEBCAM,
				FrameOverlayHooks.Phase.POST_ANNOTATIONS,
				FrameOverlayHooks.Phase.FINAL);
		// Preview is a free-running GL loop, so it may draw more than one frame; every frame must
		// dispatch the same pipeline, and the export path must use exactly that pipeline too.
		assertFalse(previewPhases.isEmpty(), "the preview rendered no frame");
		for (int index = 0; index < previewPhases.size(); index += expected.size()) {
			assertEquals(expected, previewPhases.subList(index, index + expected.size()),
					"preview phase order");
		}
		assertEquals(expected, exportPhases,
				"export must dispatch the same phases in the same order as the preview");
	}

	private static void phasesOf(List<FrameOverlayHooks.Phase> phases,
			FrameOverlayHooks.Phase phase) {
		phases.add(phase);
	}

	private static ExtensionHost hostWithExample(Path temp) throws Exception {
		Path extensions = temp.resolve("extensions");
		ExtensionTestSupport.installExample(extensions);
		ExtensionHost host = new ExtensionHost(extensions);
		host.enableAll();
		assertEquals(1, host.activeCount(), "the example bundle must activate");
		return host;
	}

	/** One frame through the preview path: a {@link Compositor} on the offscreen GL thread. */
	private static ByteBuffer renderPreviewFrame(FrameOverlayHooks hooks) throws Exception {
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(1);
		FrameInput input = FrameInput.ofSource(0, 0.0, SOURCE, BACKGROUND);
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, timeSeconds) -> {
					try (Compositor compositor = new Compositor(WIDTH, HEIGHT)) {
						compositor.setOverlayHooks(hooks);
						compositor.render(surface, input);
					}
				},
				(pixels, width, height, frameIndex) -> {
					ByteBuffer from = pixels.duplicate();
					from.position(0);
					from.limit(width * height * 4);
					snapshot.duplicate().put(from);
					received.countDown();
				}, Duration.ofSeconds(10));
		try {
			preview.start();
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!received.await(15, TimeUnit.SECONDS)) {
				fail("no preview frame read back; failure=" + preview.failure());
			}
		} finally {
			preview.close();
		}
		return snapshot;
	}

	/** One frame through the export path used by {@code ProjectExporter}. */
	private static ByteBuffer renderExportFrame(FrameOverlayHooks hooks) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		try (CompositorExportFrameProvider provider = new CompositorExportFrameProvider(
				WIDTH, HEIGHT, FRAME_RATE,
				time -> FrameInput.ofSource(0, time, SOURCE, BACKGROUND), hooks)) {
			provider.renderFrame(0, 0.0, buffer);
		} catch (IllegalStateException error) {
			Assumptions.abort("GL context unavailable for the export path: " + error.getMessage());
		}
		return buffer;
	}

	private static void assertPixel(ByteBuffer frame, int x, int y, int red, int green, int blue,
			String message) {
		int offset = (y * WIDTH + x) * 4;
		assertEquals(red, Byte.toUnsignedInt(frame.get(offset)), message + " (red)");
		assertEquals(green, Byte.toUnsignedInt(frame.get(offset + 1)), message + " (green)");
		assertEquals(blue, Byte.toUnsignedInt(frame.get(offset + 2)), message + " (blue)");
		assertEquals(255, Byte.toUnsignedInt(frame.get(offset + 3)), message + " (alpha)");
	}

	private static byte[] bytes(ByteBuffer frame) {
		ByteBuffer copy = frame.duplicate();
		copy.position(0);
		copy.limit(WIDTH * HEIGHT * 4);
		byte[] bytes = new byte[WIDTH * HEIGHT * 4];
		copy.get(bytes);
		return bytes;
	}
}
