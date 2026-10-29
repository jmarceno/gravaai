package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Port of {@code electron/ipc/project/legacyProfilePaths.test.ts}. */
class LegacyProfilePathsTest {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
	private static final Path APP_DATA = Path.of("/home/user/.config");
	private static final Path LEGACY = APP_DATA.resolve("Grava AI");
	private static final Path CURRENT = APP_DATA.resolve("Gravaai");
	private static final Path LEGACY_DEV = APP_DATA.resolve("GravaAI-dev");
	private static final Path CURRENT_DEV = APP_DATA.resolve("Gravaai-dev");
	private static final List<LegacyProfilePaths.Rename> RENAMES = List.of(
			new LegacyProfilePaths.Rename(LEGACY, CURRENT),
			new LegacyProfilePaths.Rename(LEGACY_DEV, CURRENT_DEV));

	@Test
	@DisplayName("remaps every missing media path under a legacy profile")
	void remapsEveryMissingMediaPath() {
		ObjectNode project = object(
				"version", 1,
				"videoPath", LEGACY.resolve("recordings/recording-1.mp4").toString(),
				"editor", object(
						"wallpaper", "/wallpapers/tahoe-light.jpg",
						"webcam", object(
								"enabled", true,
								"sourcePath", LEGACY.resolve("recordings/recording-1-webcam.mp4").toString()),
						"audioRegions", array(
								object("id", "a1", "audioPath", LEGACY.resolve("recordings/music.ogg").toString()),
								object("id", "a2", "audioPath", "/elsewhere/voice.ogg")),
						"audioTracks", array(
								object("sourcePath", LEGACY.resolve("recordings/recording-1.mic.webm").toString()))));

		Predicate<Path> exists = existsIn(
				CURRENT.resolve("recordings/recording-1.mp4"),
				CURRENT.resolve("recordings/recording-1-webcam.mp4"),
				CURRENT.resolve("recordings/music.ogg"),
				CURRENT.resolve("recordings/recording-1.mic.webm"));

		JsonNode result = LegacyProfilePaths.remapLegacyProfilePaths(project, RENAMES, exists);

		ObjectNode expected = object(
				"version", 1,
				"videoPath", CURRENT.resolve("recordings/recording-1.mp4").toString(),
				"editor", object(
						"wallpaper", "/wallpapers/tahoe-light.jpg",
						"webcam", object(
								"enabled", true,
								"sourcePath", CURRENT.resolve("recordings/recording-1-webcam.mp4").toString()),
						"audioRegions", array(
								object("id", "a1", "audioPath", CURRENT.resolve("recordings/music.ogg").toString()),
								object("id", "a2", "audioPath", "/elsewhere/voice.ogg")),
						"audioTracks", array(
								object("sourcePath", CURRENT.resolve("recordings/recording-1.mic.webm").toString()))));
		assertEquals(expected, result);
		assertEquals(LEGACY.resolve("recordings/recording-1.mp4").toString(),
				project.get("videoPath").asString());
	}

	@Test
	@DisplayName("maps the dev profile to the dev profile")
	void mapsDevProfileToDevProfile() {
		ObjectNode project = object(
				"videoPath", LEGACY_DEV.resolve("recordings/a.mp4").toString(),
				"editor", object());

		JsonNode result = LegacyProfilePaths.remapLegacyProfilePaths(
				project, RENAMES, existsIn(CURRENT_DEV.resolve("recordings/a.mp4")));

		assertEquals(CURRENT_DEV.resolve("recordings/a.mp4").toString(), result.get("videoPath").asString());
	}

	@Test
	@DisplayName("keeps paths that still exist, have no replacement, or sit outside legacy profiles")
	void keepsUnchangedPaths() {
		Path presentPath = LEGACY.resolve("recordings/still-here.mp4");
		String present = presentPath.toString();
		ObjectNode project = object(
				"videoPath", present,
				"editor", object(
						"webcam", object("sourcePath", LEGACY.resolve("recordings/gone-webcam.mp4").toString()),
						"audioRegions", array(
								object("audioPath", Path.of(LEGACY + "-other").resolve("music.ogg").toString()))));

		JsonNode result = LegacyProfilePaths.remapLegacyProfilePaths(
				project, RENAMES,
				existsIn(
						presentPath,
						CURRENT.resolve("recordings/still-here.mp4"),
						Path.of(CURRENT + "-other").resolve("music.ogg")));

		assertSame(project, result);
	}

	@Test
	@DisplayName("passes through non-project values")
	void passesThroughNonProjectValues() {
		assertNull(LegacyProfilePaths.remapLegacyProfilePaths(null, RENAMES));
		JsonNode array = array(NODES.numberNode(1));
		assertSame(array, LegacyProfilePaths.remapLegacyProfilePaths(array, RENAMES));
		ObjectNode noEditor = object("videoPath", 7);
		assertSame(noEditor, LegacyProfilePaths.remapLegacyProfilePaths(noEditor, RENAMES));
	}

	@Test
	@DisplayName("lists both historical profile renames for an app-data directory")
	void listsProfileDirRenames() {
		List<LegacyProfilePaths.Rename> renames = LegacyProfilePaths.profileDirRenames(APP_DATA);
		assertEquals(2, renames.size());
		assertEquals(new LegacyProfilePaths.Rename(LEGACY, CURRENT), renames.get(0));
		assertEquals(new LegacyProfilePaths.Rename(LEGACY_DEV, CURRENT_DEV), renames.get(1));
	}

	private static Predicate<Path> existsIn(Path... files) {
		Set<Path> known = new HashSet<>(Arrays.asList(files));
		return known::contains;
	}

	private static ObjectNode object(Object... keyValues) {
		ObjectNode node = NODES.objectNode();
		for (int index = 0; index < keyValues.length; index += 2) {
			String key = (String) keyValues[index];
			Object value = keyValues[index + 1];
			if (value instanceof String text) {
				node.put(key, text);
			} else if (value instanceof Integer number) {
				node.put(key, number.intValue());
			} else if (value instanceof Boolean flag) {
				node.put(key, flag.booleanValue());
			} else if (value instanceof JsonNode child) {
				node.set(key, child);
			} else {
				throw new IllegalArgumentException("Unsupported fixture value: " + value);
			}
		}
		return node;
	}

	private static ArrayNode array(JsonNode... values) {
		ArrayNode array = NODES.arrayNode();
		for (JsonNode value : values) {
			array.add(value);
		}
		return array;
	}
}
