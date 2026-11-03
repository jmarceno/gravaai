package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.EditorDefaults;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;

class ProjectEditorInputsTest {

	@DisplayName("unknown bundled wallpapers fall back to the default wallpaper")
	@Test
	void unknownBundledWallpaperFallsBackToDefault() throws Exception {
		Path root = ProjectFixture.wallpaperRoot();
		byte[] expected = Files.readAllBytes(root.resolve("wallpapers/morning-tide.jpg"));
		for (String wallpaper : new String[] {
				"/wallpapers/does-not-exist.jpg", "", "/tmp/no-such-gravaai-wallpaper.jpg"}) {
			ProjectEditorState editor = editorWith(wallpaper);
			Background background = ProjectEditorInputs.background(editor, root);
			assertInstanceOf(Background.Image.class, background, wallpaper);
			assertArrayEquals(expected,
					((Background.Image) background).encodedImage(), wallpaper);
		}
	}

	private static ProjectEditorState editorWith(String wallpaper) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(
				"{\"wallpaper\":\"" + wallpaper + "\"}"));
	}

	@DisplayName("the default path is the renamed first wallpaper")
	@Test
	void defaultPathIsTheRenamedWallpaper() {
		assertEquals("morning-tide.jpg", EditorDefaults.DEFAULT_WALLPAPER_PATH.substring(
				EditorDefaults.DEFAULT_WALLPAPER_PATH.lastIndexOf('/') + 1));
	}
}
