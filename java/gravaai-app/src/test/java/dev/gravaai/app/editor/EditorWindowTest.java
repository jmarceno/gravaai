package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import dev.gravaai.ui.AppFonts;
import javafx.application.Platform;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

/** Task-21 increment 1: a fixture project opens a real editor window with preview + timeline. */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
@ExtendWith(ApplicationExtension.class)
class EditorWindowTest {

	private static final String PROJECT = """
			{"version":2,"projectId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
			  "videoPath": "missing-recording.mp4",
			 "createdAt":1700000000000,"updatedAt":1700000000000,"projectName":"Editor window fixture",
			 "editor":{"wallpaper":"/wallpapers/tahoe-light.jpg","shadowIntensity":0.67,
			 "backgroundBlur":0,"aspectRatio":"16:9","borderRadius":0,
			 "padding":{"top":20,"bottom":20,"left":20,"right":20,"linked":true},
			 "clipRegions":[{"id":"clip-1","startMs":0,"endMs":6000,"speed":1}]}}
			""";

	@Start
	void start(Stage testFxStage) {
		AppFonts.load();
	}

	@Test
	@DisplayName("opens a fixture project in a real editor window with preview and timeline")
	void opensEditorWindow() throws Exception {
		Path directory = Files.createTempDirectory("gravaai-editor-window");
		Path project = directory.resolve("Editor window fixture.gravaai");
		Files.writeString(project, PROJECT);
		CompletableFuture<Path> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				EditorWindow window = EditorWindow.create(project, EditorWindow.Services.bare());
				window.show();
				assertNotNull(window.shell());
				assertNotNull(window.timeline());
				assertNotNull(window.shell().preview());
				assertEquals("Editor window fixture", window.session().displayName());
				Thread.sleep(1200);
				Path snapshot = writeSnapshot(window, "editor-window.png");
				window.stage().close();
				result.complete(snapshot);
			} catch (Exception failure) {
				result.completeExceptionally(failure);
			}
		});
		Path snapshot = result.get(90, TimeUnit.SECONDS);
		BufferedImage image = ImageIO.read(snapshot.toFile());
		assertNotNull(image);
		assertEquals(1440, image.getWidth());
		assertEquals(900, image.getHeight());
		assertTrue(Files.isRegularFile(snapshot));
	}

	private static Path writeSnapshot(EditorWindow window, String fileName) throws IOException {
		WritableImage image = window.scene().snapshot(null);
		BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
		PixelReader reader = image.getPixelReader();
		for (int y = 0; y < buffered.getHeight(); y++) {
			for (int x = 0; x < buffered.getWidth(); x++) {
				buffered.setRGB(x, y, reader.getArgb(x, y));
			}
		}
		Path target = Path.of("..", "parity", "ui-java").resolve(fileName);
		Files.createDirectories(target.toAbsolutePath().getParent());
		ImageIO.write(buffered, "png", target.toFile());
		return target;
	}
}
