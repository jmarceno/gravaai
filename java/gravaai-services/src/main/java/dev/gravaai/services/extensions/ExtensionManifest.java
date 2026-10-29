package dev.gravaai.services.extensions;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * A validated {@code gravaai-extension.json} (port of the manifest contract in
 * {@code EXTENSIONS.md}). Optional parts are null when absent; {@code contributes} stays opaque
 * JSON because {@code EXTENSIONS.md} documents it as metadata only.
 *
 * @param screenshots relative paths, in manifest order (empty when absent)
 * @param main relative entry point; validated to stay inside the extension directory
 * @param permissions the requested capabilities in manifest order, deduplicated
 */
public record ExtensionManifest(
		String id,
		String name,
		String version,
		String description,
		String author,
		String homepage,
		String license,
		String engine,
		String icon,
		List<String> screenshots,
		String main,
		Set<ExtensionPermission> permissions,
		@Nullable JsonNode contributes) {

	/** The manifest file name every extension directory must contain. */
	public static final String FILE_NAME = "gravaai-extension.json";

	public ExtensionManifest {
		screenshots = screenshots == null ? List.of() : List.copyOf(screenshots);
		permissions = permissions == null
				? Set.of()
				: java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(permissions));
	}

	public boolean hasPermission(ExtensionPermission permission) {
		return permissions.contains(permission);
	}

	/** The entry point inside the extension root. */
	public Path mainPath(Path root) {
		return root.resolve(main);
	}
}
