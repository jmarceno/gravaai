package dev.gravaai.core.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A file embedded in the project for components (plan §5.2 "Assets"): an image, a Lottie
 * animation, a font or a glTF model. The bytes travel with the project (base64 in the editor
 * JSON) so a project renders the same on another machine, and are decoded once per
 * {@link #hash()} by the element runtime.
 *
 * @param name the handle components use ({@code image("logo")}); letters, digits, - _ .
 * @param kind what the bytes are
 * @param data base64 of the file
 * @param hash SHA-256 hex of the decoded bytes (decode-cache key)
 */
public record ElementAsset(String name, Kind kind, String data, String hash) {

	/** Largest embedded asset (decoded bytes). */
	public static final int MAX_BYTES = 24 * 1024 * 1024;

	public enum Kind {
		IMAGE, LOTTIE, FONT, MODEL;

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Optional<Kind> fromWire(String value) {
			for (Kind kind : values()) {
				if (kind.name().equalsIgnoreCase(value)) {
					return Optional.of(kind);
				}
			}
			return Optional.empty();
		}

		/** Guesses the kind from a file name. */
		public static Optional<Kind> forFileName(String fileName) {
			String lower = fileName.toLowerCase(Locale.ROOT);
			if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
					|| lower.endsWith(".webp") || lower.endsWith(".gif")) {
				return Optional.of(IMAGE);
			}
			if (lower.endsWith(".json") || lower.endsWith(".lottie.json")) {
				return Optional.of(LOTTIE);
			}
			if (lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".woff2")
					|| lower.endsWith(".woff")) {
				return Optional.of(FONT);
			}
			if (lower.endsWith(".glb") || lower.endsWith(".gltf")) {
				return Optional.of(MODEL);
			}
			return Optional.empty();
		}
	}

	public ElementAsset {
		if (!isValidName(name)) {
			throw new IllegalArgumentException("invalid asset name \"" + name
					+ "\": use 1-64 letters, digits, '-', '_' or '.'");
		}
	}

	/** Builds an asset from raw bytes (hash computed here). */
	public static ElementAsset of(String name, Kind kind, byte[] bytes) {
		if (bytes.length == 0) {
			throw new IllegalArgumentException("asset \"" + name + "\" is empty");
		}
		if (bytes.length > MAX_BYTES) {
			throw new IllegalArgumentException("asset \"" + name + "\" is " + bytes.length / 1024
					+ " KB; the limit is " + MAX_BYTES / (1024 * 1024) + " MB");
		}
		return new ElementAsset(name, kind, Base64.getEncoder().encodeToString(bytes), sha256(bytes));
	}

	public byte[] bytes() {
		return Base64.getDecoder().decode(data);
	}

	/** Decoded size in bytes (without decoding). */
	public long size() {
		int padding = data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0;
		return (long) data.length() * 3 / 4 - padding;
	}

	public static boolean isValidName(String name) {
		return name.matches("[A-Za-z0-9_.-]{1,64}");
	}

	public static Optional<ElementAsset> find(List<ElementAsset> assets, String name) {
		return assets.stream().filter(asset -> asset.name().equals(name)).findFirst();
	}

	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}

	/** Hash of a string (used for SkSL sources). */
	public static String sha256(String text) {
		return sha256(text.getBytes(StandardCharsets.UTF_8));
	}
}
