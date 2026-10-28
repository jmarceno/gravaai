package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The committed fixture projects load through the real project model
 * ({@code ProjectDocument}/{@code ProjectEditorState}) and map to the compositor inputs.
 */
class ProjectFixtureTest {

	@Test
	@DisplayName("loads the wallpaper/blur fixture through the project model")
	void loadsWallpaperBlurFixture() throws Exception {
		ProjectFixture.Loaded loaded = ProjectFixture.load("01-wallpaper-blur");
		assertTrue(Files.isRegularFile(loaded.projectFile()));
		assertTrue(Files.isRegularFile(loaded.videoPath()),
				"fixture video missing: " + loaded.videoPath());

		Background background = ProjectFixture.background(loaded.editor());
		assertInstanceOf(Background.Image.class, background,
				"wallpaper should resolve to an image background");
		assertEquals(new LayoutPadding(24, 24, 24, 24, true),
				ProjectFixture.layout(loaded.editor()).padding());
		assertEquals(LayoutCrop.FULL, ProjectFixture.layout(loaded.editor()).crop());
		assertEquals(new RenderTargetSize(1280, 720),
				ProjectFixture.outputSize(loaded.editor(), 1280));
	}

	@Test
	@DisplayName("loads the padding/crop/9:16 aspect fixture")
	void loadsPaddingCropFixture() throws Exception {
		ProjectFixture.Loaded loaded = ProjectFixture.load("02-padding-crop-aspect");
		LayoutSpec layout = ProjectFixture.layout(loaded.editor());
		assertEquals(new LayoutPadding(12, 96, 48, 100, false), layout.padding());
		assertEquals(new LayoutCrop(0.08, 0.12, 0.84, 0.76), layout.crop());
		assertEquals(new RenderTargetSize(1280, 2276),
				ProjectFixture.outputSize(loaded.editor(), 1280));
		assertInstanceOf(Background.Image.class,
				ProjectFixture.background(loaded.editor()));
	}

	@Test
	@DisplayName("a missing fixture project is reported as an IOException")
	void missingProjectIsAnError() {
		assertThrows(IOException.class, () -> ProjectFixture.load("does-not-exist"));
	}
}
