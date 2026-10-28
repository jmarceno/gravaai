package dev.gravaai.services.extensions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Helpers for installing test extensions and reading fixture resources. */
final class ExtensionTestSupport {

	private ExtensionTestSupport() {}

	static String resource(String path) {
		try (InputStream input = ExtensionTestSupport.class.getResourceAsStream(path)) {
			if (input == null) {
				throw new IllegalStateException("Missing test resource " + path);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new IllegalStateException(error);
		}
	}

	/** The repository root, located by walking up from the Maven module directory. */
	static Path repoRoot() {
		Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		while (directory != null) {
			if (Files.isDirectory(directory.resolve("extension-examples"))) {
				return directory;
			}
			directory = directory.getParent();
		}
		throw new IllegalStateException(
				"cannot locate extension-examples/ from " + System.getProperty("user.dir"));
	}

	/**
	 * Copies the runnable repo example {@code extension-examples/hello-overlay} into
	 * {@code extensionsDir}, exactly like a user installing the bundle.
	 */
	static Path installExample(Path extensionsDir) throws IOException {
		Path source = repoRoot().resolve("extension-examples").resolve("hello-overlay");
		Path root = Files.createDirectories(extensionsDir.resolve("hello-overlay"));
		for (String file : List.of(ExtensionManifest.FILE_NAME, "index.js")) {
			if (!Files.isRegularFile(source.resolve(file))) {
				throw new IllegalStateException("missing example bundle file " + source.resolve(file));
			}
			Files.copy(source.resolve(file), root.resolve(file),
					StandardCopyOption.REPLACE_EXISTING);
		}
		return root;
	}

	/** Copies the sample extension fixture into {@code extensionsDir}. */
	static Path installSample(Path extensionsDir) throws IOException {
		Path root = Files.createDirectories(extensionsDir.resolve("sample.extension"));
		Files.writeString(
				root.resolve(ExtensionManifest.FILE_NAME),
				resource("/extensions/sample.extension/gravaai-extension.json"));
		Files.writeString(root.resolve("index.js"), resource("/extensions/sample.extension/index.js"));
		return root;
	}

	/** Writes one extension directory; {@code indexJs} null means "no entry point file". */
	static Path writeExtension(Path extensionsDir, String directory, String manifestJson, String indexJs)
			throws IOException {
		Path root = Files.createDirectories(extensionsDir.resolve(directory));
		Files.writeString(root.resolve(ExtensionManifest.FILE_NAME), manifestJson);
		if (indexJs != null) {
			Files.writeString(root.resolve("index.js"), indexJs);
		}
		return root;
	}

	/** A manifest with every required field and the given permissions JSON array. */
	static String manifest(String id, String permissionsJson) {
		return """
				{
				  "id": "%s",
				  "name": "Test Extension",
				  "version": "1.0.0",
				  "description": "A test extension",
				  "main": "index.js",
				  "permissions": %s
				}
				""".formatted(id, permissionsJson);
	}
}
