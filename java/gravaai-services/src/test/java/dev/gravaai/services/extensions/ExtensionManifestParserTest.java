package dev.gravaai.services.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for {@code gravaai-extension.json} validation (fields and permission model of
 * {@code EXTENSIONS.md}). The Electron host implementation is not in this checkout, so these are
 * the documented rules; every rejection names the field.
 */
class ExtensionManifestParserTest {

	@TempDir
	Path tempDir;

	private ExtensionManifest parse(String json) throws IOException {
		Path root = Files.createDirectory(tempDir.resolve("ext-" + System.nanoTime()));
		Files.writeString(root.resolve(ExtensionManifest.FILE_NAME), json);
		return ExtensionManifestParser.parse(root);
	}

	private String error(String json) {
		ExtensionManifestException failure = assertThrows(ExtensionManifestException.class, () -> parse(json));
		return failure.getMessage();
	}

	private static String fullManifest() {
		return """
				{
				  "id": "com.example.full",
				  "name": "Full Extension",
				  "version": "1.2.3",
				  "description": "Every optional field",
				  "author": "Gravaai",
				  "homepage": "https://example.com/ext",
				  "license": "MIT",
				  "engine": "1.4.0",
				  "icon": "assets/icon.png",
				  "screenshots": ["assets/one.png", "assets/two.png"],
				  "main": "index.js",
				  "permissions": ["render", "assets", "render"],
				  "contributes": {"frames": [{"id": "frame"}]}
				}
				""";
	}

	@Test
	@DisplayName("parse accepts a manifest with every documented field")
	void parsesFullManifest() throws IOException {
		ExtensionManifest manifest = parse(fullManifest());
		assertEquals("com.example.full", manifest.id());
		assertEquals("Full Extension", manifest.name());
		assertEquals("1.2.3", manifest.version());
		assertEquals("Every optional field", manifest.description());
		assertEquals("Gravaai", manifest.author());
		assertEquals("https://example.com/ext", manifest.homepage());
		assertEquals("MIT", manifest.license());
		assertEquals("1.4.0", manifest.engine());
		assertEquals("assets/icon.png", manifest.icon());
		assertEquals(List.of("assets/one.png", "assets/two.png"), manifest.screenshots());
		assertEquals("index.js", manifest.main());
		assertEquals(2, manifest.permissions().size(), "duplicate permissions are deduplicated");
		assertTrue(manifest.hasPermission(ExtensionPermission.RENDER));
		assertTrue(manifest.hasPermission(ExtensionPermission.ASSETS));
		assertNotNull(manifest.contributes());
		assertEquals("frame", manifest.contributes().get("frames").get(0).get("id").stringValue());
	}

	@Test
	@DisplayName("parse accepts a minimal manifest and empty optional values")
	void parsesMinimalManifest() throws IOException {
		ExtensionManifest manifest = parse(ExtensionTestSupport.manifest("com.example.min", "[]"));
		assertNull(manifest.author());
		assertNull(manifest.homepage());
		assertNull(manifest.license());
		assertNull(manifest.engine());
		assertNull(manifest.icon());
		assertEquals(List.of(), manifest.screenshots());
		assertTrue(manifest.permissions().isEmpty());
		assertNull(manifest.contributes());
	}

	@Test
	@DisplayName("parse tolerates unknown top-level fields")
	void toleratesUnknownFields() throws IOException {
		ExtensionManifest manifest = parse("""
				{
				  "id": "com.example.extra",
				  "name": "Extra",
				  "version": "1.0.0",
				  "description": "Newer manifest",
				  "main": "index.js",
				  "permissions": [],
				  "futureField": {"nested": true}
				}
				""");
		assertEquals("com.example.extra", manifest.id());
	}

	@Test
	@DisplayName("parse requires the documented fields")
	void requiresDocumentedFields() {
		for (String field : List.of("id", "name", "version", "description", "main", "permissions")) {
			tools.jackson.databind.JsonNode node = dev.gravaai.core.json.Json.MAPPER.readTree(fullManifest());
			((tools.jackson.databind.node.ObjectNode) node).remove(field);
			assertTrue(error(dev.gravaai.core.json.Json.stringify(node)).contains(field),
					field + ": " + error(dev.gravaai.core.json.Json.stringify(node)));
		}
	}

	@Test
	@DisplayName("parse validates ids")
	void validatesIds() {
		assertTrue(error(ExtensionTestSupport.manifest("has space", "[]")).contains("id"));
		assertTrue(error(ExtensionTestSupport.manifest("../evil", "[]")).contains("id"));
		assertTrue(error(ExtensionTestSupport.manifest(".hidden", "[]")).contains("id"));
		assertTrue(error(ExtensionTestSupport.manifest("a".repeat(129), "[]")).contains("id"));
	}

	@Test
	@DisplayName("parse requires a strict semantic version")
	void validatesVersion() {
		for (String version : List.of("1.0", "v1.0.0", "1.0.0.0", "01.0.0", "1.0.0-")) {
			String json = fullManifest().replace("\"version\": \"1.2.3\"", "\"version\": \"" + version + "\"");
			assertTrue(error(json).contains("version"), version);
		}
	}

	@Test
	@DisplayName("parse rejects blank names and descriptions")
	void rejectsBlankText() {
		assertTrue(error(ExtensionTestSupport.manifest("com.example.x", "[]")
						.replace("\"name\": \"Test Extension\"", "\"name\": \"   \""))
				.contains("name"));
		assertTrue(error(ExtensionTestSupport.manifest("com.example.x", "[]")
						.replace("\"description\": \"A test extension\"", "\"description\": \" \""))
				.contains("description"));
	}

	@Test
	@DisplayName("parse enforces the closed permission set")
	void validatesPermissions() {
		String unknown = error(ExtensionTestSupport.manifest("com.example.x", "[\"render\", \"telepathy\"]"));
		assertTrue(unknown.contains("unknown permission"), unknown);
		assertTrue(unknown.contains("render"), "the error lists the allowed permissions: " + unknown);
		assertTrue(error(ExtensionTestSupport.manifest("com.example.x", "\"render\"")).contains("permissions"));
		assertTrue(error(ExtensionTestSupport.manifest("com.example.x", "[1]")).contains("permissions"));
		assertTrue(error(ExtensionTestSupport.manifest("com.example.x", "null")).contains("permissions"));
	}

	@Test
	@DisplayName("parse keeps main a relative .js path inside the extension directory")
	void validatesMain() {
		assertTrue(error(ExtensionTestSupport.manifest("com.example.x", "[]")
						.replace("\"main\": \"index.js\"", "\"main\": \"/index.js\""))
				.contains("main"));
		assertTrue(error(ExtensionTestSupport.manifest("com.example.x", "[]")
						.replace("\"main\": \"index.js\"", "\"main\": \"../index.js\""))
				.contains("main"));
		assertTrue(error(ExtensionTestSupport.manifest("com.example.x", "[]")
						.replace("\"main\": \"index.js\"", "\"main\": \"index.ts\""))
				.contains("main"));
	}

	@Test
	@DisplayName("parse validates optional urls, icons and screenshots")
	void validatesOptionalFields() throws IOException {
		assertTrue(error(fullManifest().replace("https://example.com/ext", "http://example.com/ext"))
				.contains("homepage"));
		assertTrue(error(fullManifest().replace("assets/icon.png", "assets/icon.svg")).contains("icon"));
		assertTrue(error(fullManifest().replace("assets/icon.png", "../icon.png")).contains("icon"));
		assertTrue(error(fullManifest().replace("\"screenshots\": [\"assets/one.png\", \"assets/two.png\"]",
						"\"screenshots\": \"assets/one.png\""))
				.contains("screenshots"));
		assertTrue(error(fullManifest().replace("\"contributes\": {\"frames\": [{\"id\": \"frame\"}]}",
						"\"contributes\": []"))
				.contains("contributes"));

		ExtensionManifest manifest = parse(fullManifest().replace("https://example.com/ext", "https://x.dev")
				.replace("assets/icon.png", "icon.png"));
		assertEquals("https://x.dev", manifest.homepage());
		assertEquals("icon.png", manifest.icon());
	}

	@Test
	@DisplayName("parse reports a missing or invalid manifest file")
	void manifestFileErrors() throws IOException {
		Path missing = Files.createDirectory(tempDir.resolve("missing"));
		assertTrue(assertThrows(ExtensionManifestException.class, () -> ExtensionManifestParser.parse(missing))
				.getMessage()
				.contains("Could not read"));

		Path corrupt = Files.createDirectory(tempDir.resolve("corrupt"));
		Files.writeString(corrupt.resolve(ExtensionManifest.FILE_NAME), "{ not json");
		assertTrue(assertThrows(ExtensionManifestException.class, () -> ExtensionManifestParser.parse(corrupt))
				.getMessage()
				.contains("not valid JSON"));
	}

	@Test
	@DisplayName("parse rejects a non-object manifest")
	void rejectsNonObject() {
		assertTrue(error("[]").contains("JSON object"));
		assertTrue(error("\"text\"").contains("JSON object"));
	}

	@Test
	@DisplayName("permissions keep manifest order and are a closed enum")
	void permissionWires() {
		assertEquals("render", ExtensionPermission.RENDER.wire());
		assertEquals("export", ExtensionPermission.EXPORT.wire());
		assertEquals(7, ExtensionPermission.values().length);
		assertTrue(ExtensionPermission.wireValues().contains("assets"));
		assertFalse(ExtensionTestSupport.manifest("com.example.x", "[]").isEmpty());
	}
}
