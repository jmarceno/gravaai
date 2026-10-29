package dev.gravaai.services.extensions.canvas;

import org.graalvm.polyglot.HostAccess;

import io.github.humbleui.skija.Image;

/**
 * An image the extension canvas can draw ({@code drawImage}). Extensions get them from
 * {@code api.resolveAsset(...)} (later increment); tests and the compositor can build one from
 * encoded PNG/JPEG bytes.
 */
public final class CanvasImage implements AutoCloseable {

	private final Image image;

	private CanvasImage(Image image) {
		this.image = image;
	}

	/** Decodes a PNG/JPEG/WebP image; throws when the bytes are not a decodable image. */
	public static CanvasImage fromEncoded(byte[] encoded) {
		if (encoded == null || encoded.length == 0) {
			throw new IllegalArgumentException("Empty image data");
		}
		Image image = Image.makeDeferredFromEncodedBytes(encoded);
		if (image == null) {
			throw new IllegalArgumentException("Could not decode the image");
		}
		return new CanvasImage(image);
	}

	/** Wraps an already decoded Skija image; ownership moves to the returned object. */
	public static CanvasImage of(Image image) {
		return new CanvasImage(image);
	}

	@HostAccess.Export
	public int getWidth() {
		return image.getWidth();
	}

	@HostAccess.Export
	public int getHeight() {
		return image.getHeight();
	}

	Image skiaImage() {
		return image;
	}

	@Override
	public void close() {
		image.close();
	}
}
