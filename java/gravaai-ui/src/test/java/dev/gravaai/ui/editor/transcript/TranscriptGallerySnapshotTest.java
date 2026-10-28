package dev.gravaai.ui.editor.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.ui.kit.Theme;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
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

/** UI-gallery snapshots for P3.1: transcript panel and the transcript cut result. */
@ExtendWith(ApplicationExtension.class)
class TranscriptGallerySnapshotTest {

	private static final double WIDTH = 960;
	private static final double HEIGHT = 640;

	private Stage stage;
	private Scene scene;
	private TranscriptPanel panel;

	@Start
	void start(Stage stage) {
		this.stage = stage;
	}

	private static final String GALLERY_EDITOR = """
			{
			  "clipRegions": [{"id":"clip-1","startMs":0,"endMs":12000,"speed":1}],
			  "autoCaptions": [
			    {"id":"cue-1","startMs":500,"endMs":4000,"text":"one um three four",
			     "words":[
			       {"text":"one","startMs":500,"endMs":1000},
			       {"text":"um","startMs":1500,"endMs":1700,"leadingSpace":true},
			       {"text":"three","startMs":2500,"endMs":3000,"leadingSpace":true},
			       {"text":"four","startMs":3500,"endMs":4000,"leadingSpace":true}
			     ]},
			    {"id":"cue-2","startMs":6500,"endMs":11400,
			     "text":"So the plan is simple we ship the panel and then run the measurements",
			     "words":[
			       {"text":"So","startMs":6500,"endMs":6800},
			       {"text":"the","startMs":6850,"endMs":7100,"leadingSpace":true},
			       {"text":"plan","startMs":7150,"endMs":7500,"leadingSpace":true},
			       {"text":"is","startMs":7550,"endMs":7700,"leadingSpace":true},
			       {"text":"simple","startMs":7750,"endMs":8200,"leadingSpace":true},
			       {"text":"we","startMs":8600,"endMs":8800,"leadingSpace":true},
			       {"text":"ship","startMs":8850,"endMs":9200,"leadingSpace":true},
			       {"text":"the","startMs":9250,"endMs":9400,"leadingSpace":true},
			       {"text":"panel","startMs":9450,"endMs":9800,"leadingSpace":true},
			       {"text":"and","startMs":9850,"endMs":10000,"leadingSpace":true},
			       {"text":"then","startMs":10050,"endMs":10300,"leadingSpace":true},
			       {"text":"run","startMs":10350,"endMs":10600,"leadingSpace":true},
			       {"text":"the","startMs":10650,"endMs":10800,"leadingSpace":true},
			       {"text":"measurements","startMs":10850,"endMs":11400,"leadingSpace":true}
			     ]}
			  ],
			  "autoCaptionSettings": {"enabled":true,"language":"en"}
			}
			""";

	@Test
	@DisplayName("writes the deterministic transcript panel and cut snapshots")
	void writesGallerySnapshots(FxRobot robot) throws Exception {
		AtomicReference<Double> playhead = new AtomicReference<>(2_750.0);
		AtomicBoolean playing = new AtomicBoolean(true);
		robot.interact(() -> {
			FakeTranscriptUiService service = new FakeTranscriptUiService();
			service.speakers.put("cue-1", "Ana");
			service.speakers.put("cue-2", "Bruno");
			EditorStore store = new EditorStore(ProjectPersistence.normalizeProjectEditor(
					Json.parseJsonWithByteOrderMark(GALLERY_EDITOR)));
			panel = new TranscriptPanel(store, service, playhead::get, playing::get,
					seconds -> { }, new StoreTranscriptCutController(store),
					store::setAutoCaptions);
			panel.clickWord("cue-1:2", false, false);
			panel.refresh();
			StackPane root = new StackPane(panel);
			root.setAlignment(Pos.TOP_LEFT);
			root.setStyle("-fx-background-color: #121418;");
			scene = new Scene(root, WIDTH, HEIGHT);
			Theme.install(scene);
			stage.setScene(scene);
			stage.show();
		});
		WaitForAsyncUtils.waitForFxEvents();

		Path panelPng = snapshot(robot, "transcript-panel.png");
		assertTrue(Files.isRegularFile(panelPng), "transcript panel snapshot missing");
		BufferedImage panelImage = ImageIO.read(panelPng.toFile());
		assertNotNull(panelImage);
		assertEquals((int) WIDTH, panelImage.getWidth());
		assertEquals((int) HEIGHT, panelImage.getHeight());

		robot.interact(() -> panel.cutSelectedButton().fire());
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			assertTrue(panel.isWordCut("cue-1:2"));
			assertEquals("Cut 1 word", panel.feedbackText());
		});

		Path cutPng = snapshot(robot, "transcript-cut.png");
		assertTrue(Files.isRegularFile(cutPng), "transcript cut snapshot missing");
		BufferedImage cutImage = ImageIO.read(cutPng.toFile());
		assertNotNull(cutImage);
		assertEquals((int) WIDTH, cutImage.getWidth());
		assertEquals((int) HEIGHT, cutImage.getHeight());
	}

	private Path snapshot(FxRobot robot, String fileName) {
		AtomicReference<WritableImage> image = new AtomicReference<>();
		robot.interact(() -> image.set(scene.snapshot(null)));
		assertNotNull(image.get());
		try {
			return writePng(image.get(), fileName);
		} catch (IOException error) {
			throw new AssertionError(error);
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