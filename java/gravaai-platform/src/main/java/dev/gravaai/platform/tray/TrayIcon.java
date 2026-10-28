package dev.gravaai.platform.tray;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * Tray icon in ARGB32 network byte order, the form the StatusNotifierItem {@code IconPixmap}
 * property expects (plan §5.5).
 *
 * <p>Electron builds its tray image from {@code icons/icons/png/32x32.png} and the recording
 * variant {@code public/rec-button.png}; callers load those (or any PNG) with {@link #load(Path)}
 * and switch via {@code Tray.setRecording(...)}.
 */
public record TrayIcon(int width, int height, byte[] argb) {

	public TrayIcon {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("icon dimensions must be positive: " + width + "x" + height);
		}
		Objects.requireNonNull(argb, "argb");
		if (argb.length != width * height * 4) {
			throw new IllegalArgumentException("expected " + (width * height * 4) + " ARGB bytes, got " + argb.length);
		}
		argb = argb.clone();
	}

	/** Loads a PNG (or any image ImageIO understands) and converts it to ARGB32. */
	public static TrayIcon load(Path png) throws IOException {
		Objects.requireNonNull(png, "png");
		BufferedImage source = ImageIO.read(png.toFile());
		if (source == null) {
			throw new IOException("not a readable image: " + png);
		}
		// getRGB() converts straight to non-premultiplied ARGB without the premultiplication
		// rounding a Graphics2D draw into TYPE_INT_ARGB would introduce for low alpha values.
		int width = source.getWidth();
		int height = source.getHeight();
		int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
		return fromArgb(width, height, pixels);
	}

	/** Builds a tray icon from Java {@code TYPE_INT_ARGB} pixels. */
	public static TrayIcon fromArgb(int width, int height, int[] pixels) {
		Objects.requireNonNull(pixels, "pixels");
		if (pixels.length != width * height) {
			throw new IllegalArgumentException("expected " + (width * height) + " pixels, got " + pixels.length);
		}
		byte[] argb = new byte[pixels.length * 4];
		for (int index = 0; index < pixels.length; index++) {
			int pixel = pixels[index];
			argb[index * 4] = (byte) (pixel >>> 24);
			argb[index * 4 + 1] = (byte) (pixel >>> 16);
			argb[index * 4 + 2] = (byte) (pixel >>> 8);
			argb[index * 4 + 3] = (byte) pixel;
		}
		return new TrayIcon(width, height, argb);
	}

	/** A copy of the ARGB32 bytes. */
	public byte[] argbCopy() {
		return argb.clone();
	}

	/** The StatusNotifierItem pixmap struct for this icon. */
	public IconPixmap toPixmap() {
		return new IconPixmap(width, height, argb);
	}

	/** Writes the icon as a PNG (used by tests and the platform smoke). */
	public void save(Path png) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int index = 0; index < width * height; index++) {
			int alpha = argb[index * 4] & 0xff;
			int red = argb[index * 4 + 1] & 0xff;
			int green = argb[index * 4 + 2] & 0xff;
			int blue = argb[index * 4 + 3] & 0xff;
			image.setRGB(index % width, index / width, (alpha << 24) | (red << 16) | (green << 8) | blue);
		}
		Files.createDirectories(png.toAbsolutePath().getParent());
		ImageIO.write(image, "png", png.toFile());
	}
}
