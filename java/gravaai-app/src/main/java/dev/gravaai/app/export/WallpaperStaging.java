package dev.gravaai.app.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import dev.gravaai.core.project.EditorDefaults;

/**
 * Stages bundled wallpapers into a temporary root the compositor can read from disk. The
 * requested wallpaper and the configured default are copied when available, so a project that
 * names a wallpaper no longer in the bundle still renders the default instead of black.
 */
public final class WallpaperStaging {

	private static final System.Logger LOG = System.getLogger(WallpaperStaging.class.getName());
	private static final String PREFIX = "/wallpapers/";
	private static final String RESOURCE_ROOT = "/ui/editor/wallpapers/";

	private WallpaperStaging() {
	}

	/** The staging root, or null when no bundled wallpaper could be resolved. */
	public static @Nullable Path stage(@Nullable String wallpaper, String temporaryPrefix) {
		String name = wallpaper != null && wallpaper.startsWith(PREFIX)
				? wallpaper.substring(PREFIX.length()) : null;
		if (name != null && name.isEmpty()) {
			name = null;
		}
		String defaultName = EditorDefaults.DEFAULT_WALLPAPER_PATH.substring(PREFIX.length());
		URL requested = name == null ? null
				: WallpaperStaging.class.getResource(RESOURCE_ROOT + name);
		URL fallback = defaultName.equals(name) ? null
				: WallpaperStaging.class.getResource(RESOURCE_ROOT + defaultName);
		if (requested == null && fallback == null) {
			return null;
		}
		try {
			Path root = Files.createTempDirectory(temporaryPrefix);
			stageOne(root, name, requested);
			stageOne(root, defaultName, fallback);
			return root;
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "wallpaper staging failed: " + error);
			return null;
		}
	}

	private static void stageOne(Path root, @Nullable String name, @Nullable URL resource)
			throws IOException {
		if (name == null || resource == null) {
			return;
		}
		Path target = root.resolve("wallpapers").resolve(name);
		Files.createDirectories(target.getParent());
		try (var stream = resource.openStream()) {
			Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
