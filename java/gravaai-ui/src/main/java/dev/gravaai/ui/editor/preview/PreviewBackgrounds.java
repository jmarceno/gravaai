package dev.gravaai.ui.editor.preview;

import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.gravaai.media.compose.Background;
import dev.gravaai.ui.editor.EditorWallpapers;

/**
 * Resolves the project wallpaper string to a compositor {@link Background} for the preview
 * (plan §5.8 stage 1, §5.12 P2.5).
 *
 * <p>Bundled wallpapers are loaded from the 1280x720 preview copies under
 * {@code resources/ui/editor/wallpapers/previews/} and handed to the compositor as encoded image
 * bytes; colours and the editor's fixed gradient list are mapped from the JavaFX
 * {@link Paint} that {@link EditorWallpapers#paintFor(String, Color)} already parses. Instances are
 * cached per wallpaper string because the compositor's background cache keys on object identity.
 *
 * <p>Colours and gradients go through the media layer's {@code Background.fromWallpaper} (the same
 * parser as the export renderer); plain files are read as encoded image bytes.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class PreviewBackgrounds {

	private static final String PREVIEW_ROOT = "/ui/editor/wallpapers/previews/";
	private static final Map<String, Background> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, java.util.Optional<java.nio.file.Path>> VIDEO_PATHS =
			new ConcurrentHashMap<>();

	@Nullable private PreviewBackgrounds() {
	}

	/** The compositor background for a project wallpaper value; never null. */
	public static @Nullable Background of(String wallpaper) {
		String key = wallpaper == null ? "" : wallpaper.trim();
		return CACHE.computeIfAbsent(key, PreviewBackgrounds::resolve);
	}

	private static @Nullable Background resolve(String value) {
		if (value.isEmpty()) {
			return Background.black();
		}
		if (EditorWallpapers.isVideoWallpaper(value) && !EditorWallpapers.isBundledWallpaper(value)) {
			// A custom video has no poster; the preview decodes it per frame (videoPath).
			return Background.black();
		}
		if (EditorWallpapers.isBundledWallpaper(value)) {
			byte[] bytes = loadPreviewImage(value);
			return bytes == null ? Background.black() : new Background.Image(bytes);
		}
		try {
			// Colours and the editor's gradient list: media owns the CSS parser (public since
			// media-native's API update), so the preview and export resolve wallpapers identically.
			return Background.fromWallpaper(value);
		} catch (IllegalArgumentException imageSource) {
			byte[] bytes = loadFileImage(value);
			return bytes == null ? Background.black() : new Background.Image(bytes);
		}
	}

	private static @Nullable byte[] loadPreviewImage(String wallpaper) {
		String name = wallpaper.substring(wallpaper.lastIndexOf('/') + 1);
		if (EditorWallpapers.isVideoWallpaper(name)) {
			name = name.replaceFirst("\\.[^.]+$", ".jpg");
		}
		try (InputStream in = PreviewBackgrounds.class.getResourceAsStream(PREVIEW_ROOT + name)) {
			return in == null ? null : in.readAllBytes();
		} catch (Exception error) {
			return null;
		}
	}

	/**
	 * The file to decode for a video wallpaper, or null for other wallpapers. Bundled videos live
	 * inside the application image, so they are extracted once to a temp file FFmpeg can open.
	 */
	public static java.nio.file.@Nullable Path videoPath(String wallpaper) {
		if (!EditorWallpapers.isVideoWallpaper(wallpaper)) {
			return null;
		}
		return VIDEO_PATHS.computeIfAbsent(wallpaper.trim(), PreviewBackgrounds::resolveVideo)
				.orElse(null);
	}

	private static java.util.@Nullable Optional<java.nio.file.Path> resolveVideo(String value) {
		try {
			if (EditorWallpapers.isBundledWallpaper(value)) {
				String name = value.substring(value.lastIndexOf('/') + 1);
				try (InputStream in = PreviewBackgrounds.class.getResourceAsStream(
						"/ui/editor/wallpapers/" + name)) {
					if (in == null) {
						return java.util.Optional.empty();
					}
					java.nio.file.Path target = java.nio.file.Files.createTempFile(
							"gravaai-wallpaper-", "-" + name);
					target.toFile().deleteOnExit();
					java.nio.file.Files.copy(in, target,
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					return java.util.Optional.of(target);
				}
			}
			java.nio.file.Path path = value.startsWith("file:")
					? java.nio.file.Path.of(java.net.URI.create(value))
					: java.nio.file.Path.of(value);
			return java.nio.file.Files.isRegularFile(path) ? java.util.Optional.of(path)
					: java.util.Optional.empty();
		} catch (Exception unreadable) {
			return java.util.Optional.empty();
		}
	}

	/** User wallpapers: an absolute path or {@code file://} URL the compositor cannot resolve. */
	private static @Nullable byte[] loadFileImage(String value) {
		try {
			java.nio.file.Path path = value.startsWith("file:")
					? java.nio.file.Path.of(java.net.URI.create(value))
					: java.nio.file.Path.of(value);
			return java.nio.file.Files.isRegularFile(path) ? java.nio.file.Files.readAllBytes(path)
					: null;
		} catch (Exception unreadable) {
			return null;
		}
	}
}