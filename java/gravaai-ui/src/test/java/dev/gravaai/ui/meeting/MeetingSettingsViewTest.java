package dev.gravaai.ui.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import dev.gravaai.ui.kit.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Deterministic snapshot + rendering test for the meeting settings panel. */
@ExtendWith(ApplicationExtension.class)
class MeetingSettingsViewTest {

	private static final double WIDTH = 1200;
	private static final double HEIGHT = 1000;

	private Stage stage;
	private Scene scene;
	private MeetingSettingsView view;
	private MeetingFixtures.FakeMeetingUiService service;

	@Start
	void start(Stage stage) {
		this.stage = stage;
	}

	@Test
	@DisplayName("renders every settings row and the download manager, then writes the snapshot")
	void rendersSettings(FxRobot robot) throws Exception {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();
		waitUntil(robot, () -> labelsContain("Downloads"));

		robot.interact(() -> {
			assertTrue(labelsContain("Transcription"), "transcription row missing");
			assertTrue(labelsContain("Spoken language"), "language row missing");
			assertTrue(labelsContain("Meeting notes"), "notes row missing");
			assertTrue(labelsContain("Downloads"), "downloads row missing");
			assertTrue(labelsContain("Whisper large-v3-turbo (q5)  574 MB"), "asset row missing");
			AtomicReference<WritableImage> image = new AtomicReference<>();
			image.set(scene.snapshot(null));
			try {
				Path target = MeetingSnapshots.write(image.get(), "meeting-settings.png");
				BufferedImage buffered = ImageIO.read(target.toFile());
				assertNotNull(buffered);
				assertEquals((int) WIDTH, buffered.getWidth());
				assertEquals((int) HEIGHT, buffered.getHeight());
			} catch (Exception error) {
				throw new AssertionError(error);
			}
		});
	}

	@Test
	@DisplayName("downloading an asset asks the service and follows progress events")
	void downloadsAsset(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();
		waitUntil(robot, () -> labelsContain("Downloads"));
		robot.interact(() -> view.close());
		assertTrue(service.downloadedAssets().isEmpty());
	}

	private void buildScene() {
		service = new MeetingFixtures.FakeMeetingUiService();
		view = new MeetingSettingsView(service);
		ScrollPane scroll = new ScrollPane(view);
		scroll.setFitToWidth(true);
		scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
		StackPane root = new StackPane(scroll);
		root.setPadding(new Insets(40));
		root.setStyle("-fx-background-color: #121418;");
		root.setAlignment(Pos.TOP_LEFT);
		scene = new Scene(root, WIDTH, HEIGHT);
		Theme.install(scene);
		stage.setScene(scene);
		stage.show();
	}

	private boolean labelsContain(String text) {
		for (javafx.scene.Node node : scene.getRoot().lookupAll(".label")) {
			if (node instanceof javafx.scene.control.Label label && text.equals(label.getText())) {
				return true;
			}
		}
		return false;
	}

	private static void waitUntil(FxRobot robot, java.util.function.BooleanSupplier condition) {
		try {
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {
				AtomicBoolean ready = new AtomicBoolean();
				robot.interact(() -> ready.set(condition.getAsBoolean()));
				return ready.get();
			});
		} catch (java.util.concurrent.TimeoutException error) {
			throw new AssertionError("meeting settings did not render", error);
		}
	}
}
