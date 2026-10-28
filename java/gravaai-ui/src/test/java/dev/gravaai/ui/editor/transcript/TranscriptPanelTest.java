package dev.gravaai.ui.editor.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.project.CaptionTimeline;
import dev.gravaai.ui.kit.Theme;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Port of the transcript-cut Playwright behaviours over the real EditorStore. */
@ExtendWith(ApplicationExtension.class)
class TranscriptPanelTest {

	private Stage stage;
	private EditorStore store;
	private FakeTranscriptUiService service;
	private TranscriptPanel panel;
	private StoreTranscriptCutController cutController;
	private final AtomicReference<Double> playhead = new AtomicReference<>(0.0);
	private final AtomicBoolean playing = new AtomicBoolean(false);
	private final List<Double> seeks = new ArrayList<>();

	@Start
	void start(Stage stage) {
		this.stage = stage;
	}

	@Test
	@DisplayName("shows the empty state and applies generated captions")
	void generatesCaptions(FxRobot robot) {
		service = new FakeTranscriptUiService();
		service.generationSteps.addAll(List.of(
				new TranscriptUiService.Status(TranscriptUiService.State.EXTRACTING, 0,
						"Extracting audio…", List.of(), null),
				new TranscriptUiService.Status(TranscriptUiService.State.TRANSCRIBING, 40,
						"Transcribing…", List.of(), null),
				new TranscriptUiService.Status(TranscriptUiService.State.PARSING, 100,
						"Saving captions…", List.of(), null),
				new TranscriptUiService.Status(TranscriptUiService.State.DONE, 100,
						"Generated 4 caption cues.", TranscriptFixtures.generatedCues(), null)));
		robot.interact(() -> buildScene(TranscriptFixtures.emptyStore()));

		robot.interact(() -> {
			assertEquals(0, panel.wordCount());
			assertEquals("No transcript yet. Generate captions first.", panel.wordCount() == 0
					? "No transcript yet. Generate captions first." : "");
			panel.generateButton().fire();
		});
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals(1, service.generateCalls);
			assertEquals(1, store.state().autoCaptions().size());
			assertEquals(4, panel.wordCount());
			assertEquals("Generated 4 caption cues.", panel.feedbackText());
		});
	}

	@Test
	@DisplayName("click selects and seeks; Shift ranges; Escape clears the selection")
	void selectsAndSeeksWords(FxRobot robot) {
		robot.interact(() -> buildScene(TranscriptFixtures.store()));
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			panel.clickWord("cue-1:3", false, false);
			assertEquals(Set.of("cue-1:3"), panel.selectedWordKeys());
			assertEquals(3.5, seeks.get(seeks.size() - 1), 0.001);

			panel.clickWord("cue-1:0", false, false);
			panel.clickWord("cue-1:3", true, false);
			assertEquals(4, panel.selectedWordKeys().size());
			assertEquals("Cut selected (4)", panel.cutButtonText());

			panel.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false,
					false, false, false));
			assertTrue(panel.selectedWordKeys().isEmpty());
			assertEquals("Cut selected (0)", panel.cutButtonText());
		});
	}

	@Test
	@DisplayName("marks the word under the playhead while playing")
	void marksActiveWord(FxRobot robot) {
		robot.interact(() -> buildScene(TranscriptFixtures.store()));
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			playhead.set(2_750.0);
			playing.set(true);
			panel.refresh();
			assertEquals("cue-1:2", panel.activeWordKey());
		});
	}

	@Test
	@DisplayName("cutting a transcript word cuts footage and captions; undo restores them")
	void cutsWordAndUndoes(FxRobot robot) {
		robot.interact(() -> buildScene(TranscriptFixtures.store()));
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			panel.clickWord("cue-1:2", false, false);
			panel.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false,
					false, false, false));
		});
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals(2, store.state().clipRegions().size());
			assertEquals(0, store.state().clipRegions().get(0).startMs());
			assertEquals(2_500, store.state().clipRegions().get(0).endMs());
			assertEquals(2_500, store.state().clipRegions().get(1).startMs());
			assertEquals(5_250, store.state().clipRegions().get(1).endMs());
			assertTrue(panel.isWordCut("cue-1:2"));
			assertEquals("Cut 1 word", panel.feedbackText());
			assertEquals(2, CaptionTimeline.projectCaptionCues(store.state().autoCaptions(),
					store.state().clipRegions()).size());

			assertTrue(cutController.undo());
		});
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals(1, store.state().clipRegions().size());
			assertFalse(panel.isWordCut("cue-1:2"));
			assertEquals(1, CaptionTimeline.projectCaptionCues(store.state().autoCaptions(),
					store.state().clipRegions()).size());
		});
	}

	@Test
	@DisplayName("selects filler words in one click and cuts them")
	void cutsFillerWords(FxRobot robot) {
		robot.interact(() -> buildScene(TranscriptFixtures.store()));
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			panel.fillerButton().fire();
			assertEquals(Set.of("cue-1:1"), panel.selectedWordKeys());
			assertEquals("Selected 1 filler word", panel.feedbackText());
			panel.cutSelectedButton().fire();
		});
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals(2, store.state().clipRegions().size());
			assertEquals(1_500, store.state().clipRegions().get(0).endMs());
			assertTrue(panel.isWordCut("cue-1:1"));
		});
	}

	@Test
	@DisplayName("shows caption generation progress, cancellation and errors")
	void showsGenerationStatus(FxRobot robot) {
		robot.interact(() -> buildScene(TranscriptFixtures.emptyStore()));
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> service.setStatus(new TranscriptUiService.Status(
				TranscriptUiService.State.TRANSCRIBING, 40, "Transcribing…", List.of(), null)));
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			assertTrue(panel.isGenerating());
			assertTrue(panel.generationMessageText().contains("40"));
			panel.cancelGenerationButton().fire();
			assertEquals(1, service.cancelCalls);
		});

		robot.interact(() -> service.setStatus(new TranscriptUiService.Status(
				TranscriptUiService.State.ERROR, 0, "Failed to generate auto captions.",
				List.of(), "boom")));
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> assertTrue(panel.feedbackText().contains("boom")));
	}

	@Test
	@DisplayName("shows the paragraph time and speaker display")
	void showsTimeAndSpeaker(FxRobot robot) {
		service = new FakeTranscriptUiService();
		service.speakers.put("cue-1", "Ana");
		robot.interact(() -> buildScene(TranscriptFixtures.store()));
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals(List.of("0:00.500 · Ana"), panel.paragraphHeaderTexts());
			assertEquals(1, panel.paragraphCount());
		});
	}

	private void buildScene(EditorStore editorStore) {
		store = editorStore;
		service = service == null ? new FakeTranscriptUiService() : service;
		if (service.speakers.isEmpty()) {
			service.speakers.put("cue-1", "Ana");
		}
		cutController = new StoreTranscriptCutController(store);
		panel = new TranscriptPanel(store, service, playhead::get, playing::get,
				seconds -> seeks.add(seconds), cutController, store::setAutoCaptions);
		StackPane root = new StackPane(panel);
		root.setAlignment(Pos.TOP_LEFT);
		root.setStyle("-fx-background-color: #121418;");
		Scene scene = new Scene(root, 960, 640);
		Theme.install(scene);
		stage.setScene(scene);
		stage.show();
		panel.refresh();
	}
}
