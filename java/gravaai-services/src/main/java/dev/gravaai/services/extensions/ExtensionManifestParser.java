package dev.gravaai.services.extensions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Validates {@code gravaai-extension.json} against the contract in {@code EXTENSIONS.md}.
 *
 * <p>The Electron extension host is not part of this checkout (see the task report), so validation
 * follows the documented field table: required fields must be present with the documented types,
 * {@code version} is strict semver, {@code main} is a relative {@code .js} path inside the
 * extension directory, {@code homepage} is HTTPS and the permissions form a closed set. Unknown
 * top-level fields are tolerated so a newer manifest still loads.
 */
public final class ExtensionManifestParser {

	private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
	private static final Pattern SEMVER = Pattern.compile(
			"(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
					+ "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
					+ "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
	private static final int MAX_ID_LENGTH = 128;
	private static final int MAX_NAME_LENGTH = 200;
	private static final int MAX_DESCRIPTION_LENGTH = 2000;
	private static final int MAX_TEXT_LENGTH = 2000;

	private ExtensionManifestParser() {}

	/** Reads and validates {@code <root>/gravaai-extension.json}. */
	public static ExtensionManifest parse(Path root) {
		Path manifestFile = root.resolve(ExtensionManifest.FILE_NAME);
		String text;
		try {
			text = Files.readString(manifestFile, StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new ExtensionManifestException(
					"Could not read " + ExtensionManifest.FILE_NAME + ": " + error.getMessage(), error);
		}
		JsonNode node;
		try {
			node = Json.parseJsonWithByteOrderMark(text);
		} catch (RuntimeException error) {
			throw new ExtensionManifestException(
					ExtensionManifest.FILE_NAME + " is not valid JSON: " + error.getMessage(), error);
		}
		return parse(node, root);
	}

	/** Validates an already-parsed manifest; {@code root} is used for path checks. */
	public static ExtensionManifest parse(JsonNode node, Path root) {
		if (node == null || !node.isObject()) {
			throw new ExtensionManifestException(ExtensionManifest.FILE_NAME + " must be a JSON object");
		}

		String id = requiredString(node, "id").trim();
		if (!ID.matcher(id).matches()) {
			throw invalid("id", "must be 1-" + MAX_ID_LENGTH
					+ " characters of letters, digits, '.', '_' or '-' and start with a letter or digit");
		}
		if (id.contains("..")) {
			throw invalid("id", "must not contain '..'");
		}

		String name = requiredString(node, "name").trim();
		if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
			throw invalid("name", "must be 1-" + MAX_NAME_LENGTH + " characters");
		}

		String version = requiredString(node, "version").trim();
		if (!SEMVER.matcher(version).matches()) {
			throw invalid("version", "must be a strict semantic version such as 1.0.0");
		}

		String description = requiredString(node, "description").trim();
		if (description.isEmpty() || description.length() > MAX_DESCRIPTION_LENGTH) {
			throw invalid("description", "must be 1-" + MAX_DESCRIPTION_LENGTH + " characters");
		}

		String author = optionalString(node, "author");
		String license = optionalString(node, "license");
		String engine = optionalString(node, "engine");

		String homepage = optionalString(node, "homepage");
		if (homepage != null && !homepage.startsWith("https://")) {
			throw invalid("homepage", "must be an https:// URL");
		}

		String icon = optionalString(node, "icon");
		if (icon != null) {
			validateRelativePath(icon, "icon");
			if (!icon.toLowerCase(Locale.ROOT).endsWith(".png")) {
				throw invalid("icon", "must be a relative PNG path");
			}
		}

		List<String> screenshots = screenshots(node);

		String main = requiredString(node, "main").trim();
		validateRelativePath(main, "main");
		if (!main.toLowerCase(Locale.ROOT).endsWith(".js")) {
			throw invalid("main", "must be a relative .js file");
		}
		if (root != null) {
			Path mainPath = root.resolve(main).normalize();
			if (!mainPath.startsWith(root.normalize())) {
				throw invalid("main", "must stay inside the extension directory");
			}
		}

		Set<ExtensionPermission> permissions = permissions(node);

		JsonNode contributes = node.get("contributes");
		if (contributes != null && contributes.isNull()) {
			contributes = null;
		}
		if (contributes != null && !contributes.isObject()) {
			throw invalid("contributes", "must be a JSON object");
		}

		return new ExtensionManifest(
				id, name, version, description, author, homepage, license, engine, icon, screenshots,
				main, permissions, contributes);
	}

	private static Set<ExtensionPermission> permissions(JsonNode node) {
		JsonNode raw = node.get("permissions");
		if (raw == null || raw.isNull()) {
			throw invalid("permissions", "is required");
		}
		if (!raw.isArray()) {
			throw invalid("permissions", "must be an array of permission strings");
		}
		Set<ExtensionPermission> permissions = new LinkedHashSet<>();
		for (JsonNode entry : raw) {
			if (entry == null || !entry.isString()) {
				throw invalid("permissions", "must contain only strings");
			}
			String wire = entry.stringValue();
			ExtensionPermission permission = ExtensionPermission.fromWire(wire);
			if (permission == null) {
				throw invalid("permissions", "unknown permission \"" + wire + "\" (allowed: "
						+ ExtensionPermission.wireValues() + ")");
			}
			permissions.add(permission);
		}
		return permissions;
	}

	private static List<String> screenshots(JsonNode node) {
		JsonNode raw = node.get("screenshots");
		if (raw == null || raw.isNull()) {
			return List.of();
		}
		if (!raw.isArray()) {
			throw invalid("screenshots", "must be an array of relative paths");
		}
		List<String> screenshots = new ArrayList<>();
		for (JsonNode entry : raw) {
			if (entry == null || !entry.isString()) {
				throw invalid("screenshots", "must contain only strings");
			}
			String screenshot = entry.stringValue();
			validateRelativePath(screenshot, "screenshots");
			screenshots.add(screenshot);
		}
		return screenshots;
	}

	private static String requiredString(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isString()) {
			throw invalid(field, "is required and must be a string");
		}
		String text = value.stringValue();
		if (text.length() > MAX_TEXT_LENGTH) {
			throw invalid(field, "must be at most " + MAX_TEXT_LENGTH + " characters");
		}
		return text;
	}

	private static String optionalString(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isString()) {
			throw invalid(field, "must be a string");
		}
		String text = value.stringValue();
		if (text.isBlank()) {
			return null;
		}
		if (text.length() > MAX_TEXT_LENGTH) {
			throw invalid(field, "must be at most " + MAX_TEXT_LENGTH + " characters");
		}
		return text;
	}

	private static void validateRelativePath(String value, String field) {
		if (value.isBlank()) {
			throw invalid(field, "must not be empty");
		}
		Path path;
		try {
			path = Path.of(value);
		} catch (RuntimeException error) {
			throw invalid(field, "must be a relative path");
		}
		if (path.isAbsolute()) {
			throw invalid(field, "must be a relative path");
		}
		for (Path part : path) {
			if (part.toString().equals("..")) {
				throw invalid(field, "must not contain '..'");
			}
		}
	}

	private static ExtensionManifestException invalid(String field, String reason) {
		return new ExtensionManifestException(ExtensionManifest.FILE_NAME + ": \"" + field + "\" " + reason);
	}
}
