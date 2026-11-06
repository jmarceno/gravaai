package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Project v2 load/save with unknown-field preservation (plan §5.11, P2.2 acceptance gate).
 *
 * <p>Round-trip fixtures are read-only copies of the real projects in the user's
 * {@code ~/.config/Gravaai/recordings/Projects/}; a load → normalize → save cycle must be
 * semantically identical (key order aside).
 */
class ProjectDocumentTest {

	private static Stream<String> realProjects() {
		return Stream.of("Untitled Project 1.gravaai", "UnboundMark - Board Demo 01.gravaai");
	}

	private static String fixture(String name) throws IOException {
		try (InputStream input = ProjectDocumentTest.class.getResourceAsStream("/project/" + name)) {
			assertNotNull(input, "missing fixture " + name);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@ParameterizedTest(name = "round-trips the real project {0} semantically unchanged")
	@MethodSource("realProjects")
	@DisplayName("round-trips a real project semantically unchanged")
	void roundTripsRealProjects(String name) throws IOException {
		String contents = fixture(name);
		JsonNode original = Json.parseJsonWithByteOrderMark(contents);

		ProjectDocument document = ProjectDocument.load(projectFile(name, contents));
		ObjectNode saved = document.toJson();

		// The canonical save always carries keystrokeElements now; these fixtures predate the
		// editable Keystrokes track, so an absent field means the same empty list.
		JsonNode expected = original.deepCopy();
		if (expected.get("editor") instanceof ObjectNode editor && !editor.has("keystrokeElements")) {
			editor.set("keystrokeElements", Json.nodeFactory().arrayNode());
		}
		// Same for the Camera layout track.
		if (expected.get("editor") instanceof ObjectNode editor
				&& !editor.has("cameraLayoutSegments")) {
			editor.set("cameraLayoutSegments", Json.nodeFactory().arrayNode());
		}
		// Same for the click sound setting introduced with the bundled sounds: the canonical
		// save always carries the normalised default.
		if (expected.get("editor") instanceof ObjectNode editor
				&& !editor.has("clickSound")) {
			editor.set("clickSound",
					ProjectDocument.writeClickSound(ClickSoundSettings.DEFAULT));
		}
		// And the key sound setting: same canonical default on save.
		if (expected.get("editor") instanceof ObjectNode editor
				&& !editor.has("keySound")) {
			editor.set("keySound", ProjectDocument.writeKeySound(KeySoundSettings.DEFAULT));
		}
		// And the webcam background effect: the canonical save always carries the default (none) for
		// projects that predate it, the legacy boolean mirror, the slow-path switch, and the mask
		// edge controls.
		if (expected.get("editor") instanceof ObjectNode editor
				&& editor.get("webcam") instanceof ObjectNode webcam) {
			if (!webcam.has("backgroundEffect")) {
				webcam.put("backgroundEffect", WebcamOverlaySettings.DEFAULT_WEBCAM_BACKGROUND_EFFECT
						.wire());
			}
			if (!webcam.has("backgroundBlur")) {
				webcam.put("backgroundBlur", false);
			}
			if (!webcam.has("highQualityMaskWhilePlaying")) {
				webcam.put("highQualityMaskWhilePlaying",
						WebcamOverlaySettings.DEFAULT_WEBCAM_HIGH_QUALITY_MASK_WHILE_PLAYING);
			}
			if (!webcam.has("maskPrecision")) {
				webcam.put("maskPrecision",
						WebcamOverlaySettings.DEFAULT_WEBCAM_MASK_PRECISION);
			}
			if (!webcam.has("maskOffset")) {
				webcam.put("maskOffset", WebcamOverlaySettings.DEFAULT_WEBCAM_MASK_OFFSET);
			}
		}

		assertEquals(2, saved.get("version").intValue());
		assertSemanticallyEqual(expected, saved, "$");
	}

	@Test
	@DisplayName("preserves unknown project and editor fields while dropping retired preferences")
	void preservesUnknownFields() throws IOException {
		JsonNode project = Json.parseJsonWithByteOrderMark("""
				{"version":2,"projectId":"p1","videoPath":"/tmp/clip.mp4","futureRoot":{"keep":true},
				"editor":{"wallpaper":"#123456","futureSetting":{"nested":[1,2]},
				"exportQuality":"high","zoomMotionBlurTuning":{"panVelocityThreshold":3},
				"webcam":{"enabled":false,"sourcePath":"/tmp/cam.mp4","futureWebcam":"keep"}}}
				""");
		Path file = writeTemp("unknown-fields.gravaai", project);

		ProjectDocument document = ProjectDocument.load(file);
		ObjectNode saved = document.toJson();
		JsonNode editor = saved.get("editor");

		assertTrue(saved.get("futureRoot").get("keep").booleanValue());
		assertEquals(1, editor.get("futureSetting").get("nested").get(0).intValue());
		assertFalse(editor.has("exportQuality"));
		assertFalse(editor.has("zoomMotionBlurTuning"));
		assertEquals("#123456", editor.get("wallpaper").asString());
		assertEquals("/tmp/cam.mp4", editor.get("webcam").get("sourcePath").asString());
		// The typed webcam rewrite carries the effect mode (defaulting to none for old projects)
		// plus the legacy boolean mirror a previous build still reads.
		assertEquals("none", editor.get("webcam").get("backgroundEffect").asString());
		assertFalse(editor.get("webcam").get("backgroundBlur").booleanValue());
		// And the slow-path switch plus mask edge controls (defaulting to the shipped shape).
		assertFalse(editor.get("webcam").get("highQualityMaskWhilePlaying").booleanValue());
		assertEquals(WebcamOverlaySettings.DEFAULT_WEBCAM_MASK_PRECISION,
				editor.get("webcam").get("maskPrecision").doubleValue(), 1e-9);
		assertEquals(WebcamOverlaySettings.DEFAULT_WEBCAM_MASK_OFFSET,
				editor.get("webcam").get("maskOffset").doubleValue(), 1e-9);
	}

	@Test
	@DisplayName("an old project's blur boolean saves as the blur effect and keeps the legacy mirror")
	void migratesLegacyBlurOnSave() throws IOException {
		JsonNode project = Json.parseJsonWithByteOrderMark("""
				{"version":2,"projectId":"p1","videoPath":"/tmp/clip.mp4",
				"editor":{"webcam":{"enabled":true,"backgroundBlur":true}}}
				""");
		ProjectDocument document = ProjectDocument.load(writeTemp("legacy-blur.gravaai", project));
		JsonNode webcam = document.toJson().get("editor").get("webcam");

		assertEquals("blur", webcam.get("backgroundEffect").asString());
		assertTrue(webcam.get("backgroundBlur").booleanValue());
	}

	@Test
	@DisplayName("a remove-effect project saves a false legacy boolean (no equivalent upstream)")
	void removeEffectWritesFalseLegacyBoolean() throws IOException {
		JsonNode project = Json.parseJsonWithByteOrderMark("""
				{"version":2,"projectId":"p1","videoPath":"/tmp/clip.mp4",
				"editor":{"webcam":{"enabled":true,"backgroundEffect":"remove"}}}
				""");
		ProjectDocument document = ProjectDocument.load(writeTemp("remove.gravaai", project));
		JsonNode webcam = document.toJson().get("editor").get("webcam");

		assertEquals("remove", webcam.get("backgroundEffect").asString());
		assertFalse(webcam.get("backgroundBlur").booleanValue());
	}

	@Test
	@DisplayName("rewrites the typed fields after an editor change")
	void rewritesTypedFields() throws IOException {
		String contents = fixture("Untitled Project 1.gravaai");
		ProjectDocument document = ProjectDocument.load(projectFile("a.gravaai", contents));

		ProjectEditorState edited = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("{\"wallpaper\":\"#000000\",\"aspectRatio\":\"9:16\"}"));
		ProjectDocument updated = document.withEditor(edited).withVideoPath("/tmp/other.mp4");
		ObjectNode saved = updated.toJson();

		assertEquals("#000000", saved.get("editor").get("wallpaper").asString());
		assertEquals("9:16", saved.get("editor").get("aspectRatio").asString());
		assertEquals("/tmp/other.mp4", saved.get("videoPath").asString());
		assertEquals(document.projectId(), saved.get("projectId").asString());
		assertEquals(2, saved.get("version").intValue());
	}

	@Test
	@DisplayName("rejects a file that is not a project")
	void rejectsInvalidProjects(@TempDir Path directory) throws IOException {
		Path invalid = directory.resolve("bad.gravaai");
		Files.writeString(invalid, "{\"hello\":true}", StandardCharsets.UTF_8);
		assertThrows(IllegalArgumentException.class, () -> ProjectDocument.load(invalid));

		assertThrows(IOException.class, () -> ProjectDocument.load(directory.resolve("missing.gravaai")));
	}

	@Test
	@DisplayName("writes a parseable project file through the save helper")
	void writesParseableFile(@TempDir Path directory) throws IOException {
		ProjectDocument document = ProjectDocument.load(projectFile("u.gravaai",
				fixture("Untitled Project 1.gravaai")));
		Path target = directory.resolve("saved.gravaai");

		document.write(target);

		JsonNode written = Json.parseJsonWithByteOrderMark(Files.readString(target, StandardCharsets.UTF_8));
		assertSemanticallyEqual(document.toJson(), written, "$");
		assertFalse(Files.readString(target, StandardCharsets.UTF_8).endsWith("\n"));
	}

	private static Path projectFile(String name, String contents) throws IOException {
		Path directory = Files.createTempDirectory("gravaai-project-");
		Path file = directory.resolve(name);
		Files.writeString(file, contents, StandardCharsets.UTF_8);
		return file;
	}

	private static Path writeTemp(String name, JsonNode contents) throws IOException {
		Path directory = Files.createTempDirectory("gravaai-project-");
		Path file = directory.resolve(name);
		Files.writeString(file, Json.stringify(contents), StandardCharsets.UTF_8);
		return file;
	}

	/** Semantic JSON equality: object key order is ignored, numbers compare by value. */
	private static void assertSemanticallyEqual(JsonNode expected, JsonNode actual, String path) {
		if (expected.isObject() && actual.isObject()) {
			java.util.Set<String> expectedKeys = new java.util.TreeSet<>();
			expected.properties().forEach(entry -> expectedKeys.add(entry.getKey()));
			java.util.Set<String> actualKeys = new java.util.TreeSet<>();
			actual.properties().forEach(entry -> actualKeys.add(entry.getKey()));
			assertEquals(expectedKeys, actualKeys, path + " keys");
			for (String key : expectedKeys) {
				assertSemanticallyEqual(expected.get(key), actual.get(key), path + "." + key);
			}
			return;
		}
		if (expected.isArray() && actual.isArray()) {
			assertEquals(expected.size(), actual.size(), path + " length");
			for (int index = 0; index < expected.size(); index++) {
				assertSemanticallyEqual(expected.get(index), actual.get(index), path + "[" + index + "]");
			}
			return;
		}
		if (expected.isNumber() && actual.isNumber()) {
			assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()),
					path + " number " + expected + " != " + actual);
			return;
		}
		assertTrue(expected.equals(actual), path + " expected " + expected + " but was " + actual);
	}
}
