package dev.gravaai.app.editor;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.compose.CompositorFrameAtTime;
import dev.gravaai.media.compose.ProjectFrameInputFactory;
import dev.gravaai.media.compose.RgbaFrame;

/**
 * Diagnostics for the P2 "real projects side by side" gate: opens each project through
 * {@link EditorSession} and renders the requested times with the exporter's inputs, at the size
 * of the Electron capture next to it.
 *
 * <p>{@code GRAVAAI_PROJECT_COMPARE=<dir>} with {@code plan.tsv} (name, comma-separated seconds),
 * {@code projects/<name>.gravaai} and {@code electron/<name>/<ms>.png}; writes
 * {@code java/<name>/<ms>.png}.</p>
 */
class RealProjectCompareDumpTest {

	@Test
	@Timeout(900)
	@DisplayName("renders real projects at the Electron capture times")
	void dumpRealProjects() throws Exception {
		String root = System.getenv("GRAVAAI_PROJECT_COMPARE");
		Assumptions.assumeTrue(root != null && !root.isBlank(),
				"set GRAVAAI_PROJECT_COMPARE to a comparison directory");
		Path dir = Path.of(root);
		// Bundled wallpapers, not public/: the Electron tree is removed in P3.6.
		Path wallpapers = Path.of("..", "gravaai-ui", "src", "main", "resources", "ui", "editor")
				.toAbsolutePath().normalize();
		for (String line : Files.readAllLines(dir.resolve("plan.tsv"))) {
			if (line.isBlank()) {
				continue;
			}
			String[] parts = line.split("\t");
			String name = parts[0];
			try (EditorSession session = EditorSession.open(
					dir.resolve("projects").resolve(name + ".gravaai"))) {
				for (String time : parts[1].split(",")) {
					double seconds = Double.parseDouble(time);
					long ms = Math.round(seconds * 1000);
					BufferedImage reference = ImageIO.read(dir.resolve("electron").resolve(name)
							.resolve(ms + ".png").toFile());
					try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(
							session.store().state(), Path.of(session.videoPath()),
							session.webcamPath(), CursorTelemetryLoader.load(session.videoPath()),
							0, 0, wallpapers, 60);
							CompositorFrameAtTime renderer = new CompositorFrameAtTime(
									reference.getWidth(), reference.getHeight(), factory)) {
						RgbaFrame frame = renderer.frameAtTime(seconds);
						Assumptions.assumeTrue(frame != null, "GL context unavailable");
						Path out = dir.resolve("java").resolve(name).resolve(ms + ".png");
						Files.createDirectories(out.getParent());
						ImageIO.write(toImage(frame), "png", out.toFile());
						System.out.println("REAL-PROJECT " + name + "@" + ms + " -> " + out);
					}
				}
			}
		}
	}

	private static BufferedImage toImage(RgbaFrame frame) {
		BufferedImage image = new BufferedImage(frame.width(), frame.height(),
				BufferedImage.TYPE_INT_RGB);
		ByteBuffer pixels = frame.pixelsTopFirstRgba().duplicate();
		pixels.position(0);
		for (int y = 0; y < frame.height(); y++) {
			for (int x = 0; x < frame.width(); x++) {
				int r = pixels.get() & 0xFF;
				int g = pixels.get() & 0xFF;
				int b = pixels.get() & 0xFF;
				pixels.get();
				image.setRGB(x, y, (r << 16) | (g << 8) | b);
			}
		}
		return image;
	}
}
