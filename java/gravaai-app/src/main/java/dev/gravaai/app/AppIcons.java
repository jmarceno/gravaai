package dev.gravaai.app;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.storage.UserData;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.image.Image;

/**
 * Packaged image assets for the platform layer (plan §5.5 tray icons).
 *
 * <p>The tray D-Bus code takes a file path, so each resource is extracted once into the user data
 * directory and reused after that. Resources live in the UI jar ({@code app-icons/...}).
 */
public final class AppIcons {

	private final Path directory;

	public AppIcons(UserData userData) {
		this.directory = userData.dir().resolve("icons");
	}

	/** Extracts {@code app-icons/<name>} when it exists; null when this build does not ship it. */
	public @Nullable Path loadOrNull(String name) {
		if (getClass().getResource("/app-icons/" + name) == null) {
			return null;
		}
		return load(name);
	}

	/**
	 * Loads a packaged icon directly as a JavaFX image without touching the extraction cache.
	 *
	 * <p>Window decoration/taskbar icons must be set before the stage is shown, so this path stays
	 * read-only and tolerant of a build that does not ship an optional size. Invalid or missing
	 * resources return {@code null} instead of preventing the app from starting.
	 */
	public @Nullable Image loadImageOrNull(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String resource = "/app-icons/" + name;
		try (InputStream stream = AppIcons.class.getResourceAsStream(resource)) {
			if (stream == null) {
				return null;
			}
			Image image = new Image(stream);
			return image.isError() ? null : image;
		} catch (IOException | RuntimeException error) {
			return null;
		}
	}

	/** Loads all available icons from a preferred list, in the order supplied. */
	public List<Image> loadImages(String... names) {
		List<Image> images = new ArrayList<>();
		if (names != null) {
			for (String name : names) {
				Image image = loadImageOrNull(name);
				if (image != null) {
					images.add(image);
				}
			}
		}
		return List.copyOf(images);
	}

	/** Extracts {@code app-icons/<name>} and returns the cached file. */
	public Path load(String name) {
		Path target = directory.resolve(name);
		if (Files.isRegularFile(target)) {
			return target;
		}
		String resource = "/app-icons/" + name;
		try (InputStream stream = AppIcons.class.getResourceAsStream(resource)) {
			if (stream == null) {
				throw new IllegalStateException("Missing packaged icon resource: " + resource);
			}
			Files.createDirectories(directory);
			Path temporary = Files.createTempFile(directory, name, ".tmp");
			Files.copy(stream, temporary, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException error) {
				Files.deleteIfExists(temporary);
				if (!Files.isRegularFile(target)) {
					throw error;
				}
			}
		} catch (IOException error) {
			throw new UncheckedIOException("Could not extract the packaged icon " + name, error);
		}
		return target;
	}
}
