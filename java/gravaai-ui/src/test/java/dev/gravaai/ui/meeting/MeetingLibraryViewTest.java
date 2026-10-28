package dev.gravaai.ui.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import dev.gravaai.ui.kit.Theme;
import javafx.geometry.Pos;
import javafx.scene.Scene;
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

/** Deterministic snapshot + rendering test for the meetings library. */
@ExtendWith(ApplicationExtension.class)
class MeetingLibraryViewTest {

	private static final double WIDTH = 1200;
	private static final double HEIGHT = 760;

	private Stage stage;
	private Scene scene;
	private MeetingLibraryView view;
	private MeetingFixtures.FakeMeetingUiService service;

	@Start
	void start(Stage stage) {
		this.stage = stage;
	}

	@Test
	@DisplayName("lists the fixture meetings with their badges and writes the snapshot")
	void rendersLibrary(FxRobot robot) throws Exception {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();
		waitForLibrary(robot);

		robot.interact(() -> {
			assertTrue(labelsContain("Design review"), "meeting title missing");
			assertTrue(labelsContain("Weekly sync"), "audio meeting title missing");
			assertTrue(labelsContain("Notes ready"), "notes badge missing");
			assertTrue(labelsContain("Video"), "video badge missing");
			AtomicReference<WritableImage> image = new AtomicReference<>();
			image.set(scene.snapshot(null));
			try {
				Path target = MeetingSnapshots.write(image.get(), "meeting-library.png");
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
	@DisplayName("clicking Open reports the meeting path to the host")
	void openReportsPath(FxRobot robot) {
		AtomicReference<Path> opened = new AtomicReference<>();
		AtomicBoolean closed = new AtomicBoolean();
		robot.interact(() -> {
			service = new MeetingFixtures.FakeMeetingUiService();
			view = new MeetingLibraryView(service, new MeetingViewActions(() -> closed.set(true),
					() -> null, null), opened::set);
			StackPane root = new StackPane(view);
			root.setStyle("-fx-background-color: #121418;");
			root.setAlignment(Pos.TOP_LEFT);
			scene = new Scene(root, WIDTH, HEIGHT);
			Theme.install(scene);
			stage.setScene(scene);
			stage.show();
			view.refresh();
		});
		WaitForAsyncUtils.waitForFxEvents();
		waitForLibrary(robot);
		robot.interact(() -> {
			javafx.scene.Node firstOpen = null;
			for (javafx.scene.Node node : scene.getRoot().lookupAll(".button")) {
				if (node instanceof javafx.scene.control.Button button
						&& "Open".equals(button.getText())) {
					firstOpen = button;
					break;
				}
			}
			assertNotNull(firstOpen);
			((javafx.scene.control.Button) firstOpen).fire();
		});
		assertEquals(MeetingFixtures.VIDEO_MEETING, opened.get());
	}

	private void buildScene() {
		service = new MeetingFixtures.FakeMeetingUiService();
		view = new MeetingLibraryView(service, MeetingViewActions.none(), path -> { });
		StackPane root = new StackPane(view);
		root.setStyle("-fx-background-color: #121418;");
		root.setAlignment(Pos.TOP_LEFT);
		scene = new Scene(root, WIDTH, HEIGHT);
		Theme.install(scene);
		stage.setScene(scene);
		stage.show();
		view.refresh();
	}

	private boolean labelsContain(String text) {
		for (javafx.scene.Node node : scene.getRoot().lookupAll(".label")) {
			if (node instanceof javafx.scene.control.Label label && label.getText() != null
					&& label.getText().contains(text)) {
				return true;
			}
		}
		return false;
	}

	private void waitForLibrary(FxRobot robot) {
		try {
			WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS, () -> {
				java.util.concurrent.atomic.AtomicBoolean ready =
						new java.util.concurrent.atomic.AtomicBoolean();
				robot.interact(() -> {
					for (javafx.scene.Node node : scene.getRoot().lookupAll(".label")) {
						if (node instanceof javafx.scene.control.Label label
								&& "Design review".equals(label.getText())) {
							ready.set(true);
						}
					}
				});
				return ready.get();
			});
		} catch (java.util.concurrent.TimeoutException error) {
			throw new AssertionError("meetings library did not render", error);
		}
	}
}
