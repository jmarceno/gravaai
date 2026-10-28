package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Single-window architecture (plan §5.1): the editor is hosted by the app's main stage and never
 * creates a second OS window; navigation back to the dashboard disposes the editor but leaves the
 * main window showing.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
@ExtendWith(ApplicationExtension.class)
class EditorWindowHostedTest {

	private static final String PROJECT = """
			{"version":2,"projectId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
			  "videoPath": "missing-recording.mp4",
			 "createdAt":1700000000000,"updatedAt":1700000000000,"projectName":"Hosted editor fixture",
			 "editor":{"wallpaper":"/wallpapers/tahoe-light.jpg","shadowIntensity":0.67,
			 "backgroundBlur":0,"aspectRatio":"16:9","borderRadius":0,
			 "padding":{"top":20,"bottom":20,"left":20,"right":20,"linked":true},
			 "clipRegions":[{"id":"clip-1","startMs":0,"endMs":6000,"speed":1}]}}
			""";

	@Start
	void start(Stage testFxStage) {
		dev.gravaai.ui.AppFonts.load();
	}

	@Test
	@DisplayName("hosting the editor swaps the main window scene and creates no second window")
	void hostsEditorInMainWindow() throws Exception {
		Path directory = Files.createTempDirectory("gravaai-hosted-editor");
		Path project = directory.resolve("Hosted editor fixture.gravaai");
		Files.writeString(project, PROJECT);
		CompletableFuture<Void> done = new CompletableFuture<>();
		AtomicInteger crashed = new AtomicInteger();

		Platform.runLater(() -> {
			Stage main = new Stage();
			StackPane dashboard = new StackPane();
			Scene dashboardScene = new Scene(dashboard, 1280, 800);
			main.setScene(dashboardScene);
			main.show();
			try {
				int before = Window.getWindows().size();

				EditorWindow window = EditorWindow.createIn(main, project,
						EditorWindow.Services.bare());
				assertTrue(window.isHosted(), "editor must know it is hosted");
				window.show();
				assertSame(main, window.stage(), "the editor must use the main stage");
				assertSame(window.scene(), main.getScene(), "the main stage shows the editor scene");
				assertEquals(before, Window.getWindows().size(),
						"hosting the editor must not open a second window");

				// Back to Home: the editor releases its resources, the main window stays open.
				window.dispose();
				assertFalse(main.isShowing() == false, "the main window must stay visible");
				main.setScene(dashboardScene);
				assertSame(dashboardScene, main.getScene());
				assertEquals(before, Window.getWindows().size());
				assertNotNull(window.shell());
				main.close();
				done.complete(null);
			} catch (Throwable error) {
				crashed.set(1);
				main.close();
				done.completeExceptionally(error);
			}
		});
		done.get(90, TimeUnit.SECONDS);
		assertEquals(0, crashed.get());
	}
}
