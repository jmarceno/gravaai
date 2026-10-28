package dev.gravaai.ui.meeting;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

/** Writes the deterministic TestFX snapshots into java/parity/ui-java (Lead request). */
final class MeetingSnapshots {

	private MeetingSnapshots() {
	}

	static Path write(WritableImage image, String fileName) throws IOException {
		BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
		PixelReader reader = image.getPixelReader();
		for (int y = 0; y < buffered.getHeight(); y++) {
			for (int x = 0; x < buffered.getWidth(); x++) {
				buffered.setRGB(x, y, reader.getArgb(x, y));
			}
		}
		Path target = Path.of(System.getProperty("user.dir")).resolve("..").resolve("parity")
				.resolve("ui-java").resolve(fileName).normalize();
		Files.createDirectories(target.getParent());
		ImageIO.write(buffered, "png", target.toFile());
		return target;
	}
}
