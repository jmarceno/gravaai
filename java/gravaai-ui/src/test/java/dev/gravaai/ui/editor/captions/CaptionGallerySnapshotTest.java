package dev.gravaai.ui.editor.captions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.project.AutoCaptionSettings;
import dev.gravaai.ui.editor.timeline.StoreTimelineCommands;
import dev.gravaai.ui.editor.timeline.StoreTimelineDataSource;
import dev.gravaai.ui.editor.timeline.TimelineView;
import dev.gravaai.ui.kit.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** UI-gallery snapshot for captions: timeline strip + style preview + caption list editor. */
@ExtendWith(ApplicationExtension.class)
class CaptionGallerySnapshotTest {

	private static final double WIDTH = 1200;
	private static final double HEIGHT = 760;

	private Stage stage;
	private Scene scene;
	private TimelineView timeline;
	private CaptionListPanel panel;
	private CaptionStylePreview preview;

	@Start
	void start(Stage stage) {
		this.stage = stage;
	}

	@Test
	@DisplayName("writes the deterministic caption gallery snapshot")
	void writesGallerySnapshot(FxRobot robot) throws Exception {
		robot.interact(() -> {
			EditorStore store = CaptionFixtures.store();
			StoreTimelineDataSource dataSource = new StoreTimelineDataSource(store, () -> 6_000,
					() -> 1_500, null);
			StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });
			timeline = new TimelineView(dataSource, commands);
			timeline.setPrefHeight(260);
			timeline.setMinHeight(220);
			panel = new CaptionListPanel(store, () -> 1_500);
			panel.setPrefWidth(360);
			panel.setMaxWidth(360);
			preview = new CaptionStylePreview();
			preview.setSettings(AutoCaptionSettings.defaults());
			preview.setCues(store.state().autoCaptions());
			preview.setCurrentTimeMs(1_500);

			store.setSelectedCaptionId("cap-a");
			panel.refresh();
			timeline.refreshData();
			timeline.selectCaption("cap-a");

			HBox bottom = new HBox(16, preview, panel);
			HBox.setHgrow(preview, Priority.ALWAYS);
			VBox root = new VBox(16, timeline, bottom);
			root.setPadding(new Insets(16));
			root.setStyle("-fx-background-color: #121418;");
			root.setAlignment(Pos.TOP_LEFT);
			VBox.setVgrow(bottom, Priority.ALWAYS);
			scene = new Scene(root, WIDTH, HEIGHT);
			Theme.install(scene);
			stage.setScene(scene);
			stage.show();
		});
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals("Hello Hello", preview.activeText());
			assertTrue(panel.isVisible());
			assertNotNull(timeline.captionBounds("cap-a").orElse(null));
		});

		AtomicReference<Path> targetRef = new AtomicReference<>();
		robot.interact(() -> {
			try {
				targetRef.set(writePng(scene.snapshot(null), "timeline-captions.png"));
			} catch (IOException error) {
				throw new IllegalStateException(error);
			}
		});
		Path target = targetRef.get();
		assertTrue(Files.isRegularFile(target), "snapshot missing: " + target);
		BufferedImage buffered = ImageIO.read(target.toFile());
		assertNotNull(buffered);
		assertEquals((int) WIDTH, buffered.getWidth());
		assertEquals((int) HEIGHT, buffered.getHeight());
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
