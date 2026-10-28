package dev.gravaai.ui.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import dev.gravaai.ui.kit.Theme;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Deterministic TestFX harness for the meeting view: fixture service, fixture transcript/notes and
 * the UI-gallery snapshot (java/parity/ui-java/meeting-view.png, Lead request for P1.11b).
 */
@ExtendWith(ApplicationExtension.class)
class MeetingViewTest {

	private static final double WIDTH = 1200;
	private static final double HEIGHT = 760;

	private Stage stage;
	private MeetingFixtures.FakeMeetingUiService service;
	private MeetingView view;
	private Scene scene;

	@Start
	void start(Stage stage) {
		this.stage = stage;
	}

	@Test
	@DisplayName("renders the meeting header, notes pane and job progress")
	void rendersMeetingView(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();
		waitUntil(robot, () -> scene.lookup(".meeting-markdown") != null);

		robot.interact(() -> {
			assertNotNull(scene.lookup(".meeting-markdown"));
			assertNotNull(scene.lookup(".meeting-notes"));
			assertEquals(MeetingFixtures.VIDEO_MEETING, view.mediaPath());
			assertTrue(service.activeJob().isActive());
		});
	}

	@Test
	@DisplayName("writes the deterministic meeting view snapshot for the UI gallery")
	void writesGallerySnapshot(FxRobot robot) throws Exception {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();
		waitUntil(robot, () -> scene.lookup(".meeting-markdown") != null);

		AtomicReference<WritableImage> image = new AtomicReference<>();
		robot.interact(() -> image.set(scene.snapshot(null)));
		assertNotNull(image.get());
		Path target = writePng(image.get(), "meeting-view.png");
		assertTrue(Files.isRegularFile(target), "snapshot missing: " + target);
		BufferedImage buffered = ImageIO.read(target.toFile());
		assertNotNull(buffered);
		assertEquals((int) WIDTH, buffered.getWidth());
		assertEquals((int) HEIGHT, buffered.getHeight());
	}

	@Test
	@DisplayName("the back button notifies the app shell and clears the open meeting")
	void backClosesMeeting(FxRobot robot) {
		AtomicBoolean closed = new AtomicBoolean();
		robot.interact(() -> {
			service = new MeetingFixtures.FakeMeetingUiService();
			view = MeetingView.create(service,
					new MeetingViewActions(() -> closed.set(true), () -> null, null));
			StackPane root = new StackPane(view.ui());
			root.setStyle("-fx-background-color: #121418;");
			root.setAlignment(Pos.TOP_LEFT);
			scene = new Scene(root, WIDTH, HEIGHT);
		Theme.install(scene);
			Theme.install(scene);
			stage.setScene(scene);
			stage.show();
			view.open(MeetingFixtures.VIDEO_MEETING);
		});
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> view.close());
		assertTrue(closed.get());
		assertNull(view.mediaPath());
	}

	private void buildScene() {
		service = new MeetingFixtures.FakeMeetingUiService();
		view = MeetingView.create(service, MeetingViewActions.none());
		StackPane root = new StackPane(view.ui());
		root.setStyle("-fx-background-color: #121418;");
		root.setAlignment(Pos.TOP_LEFT);
		scene = new Scene(root, WIDTH, HEIGHT);
		Theme.install(scene);
		stage.setScene(scene);
		stage.show();
		view.open(MeetingFixtures.VIDEO_MEETING);
	}

	private static void waitUntil(FxRobot robot, java.util.function.BooleanSupplier condition) {
		try {
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {
				AtomicBoolean ready = new AtomicBoolean();
				robot.interact(() -> ready.set(condition.getAsBoolean()));
				return ready.get();
			});
		} catch (java.util.concurrent.TimeoutException error) {
			throw new AssertionError("timed out waiting for the meeting view", error);
		}
	}

	private static Path writePng(WritableImage image, String fileName) throws IOException {
		BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
		PixelReader reader = image.getPixelReader();
		for (int y = 0; y < buffered.getHeight(); y++) {
			for (int x = 0; x < buffered.getWidth(); x++) {
				buffered.setRGB(x, y, reader.getArgb(x, y));
			}
		}
		Path target = Path.of(System.getProperty("user.dir")).resolve("..").resolve("parity")
				.resolve("ui-java").resolve(fileName).normalize();
		Files.createDirectories(target.getParent());
		ImageIO.write(buffered, "png", target.toFile());
		return target;
	}
}
