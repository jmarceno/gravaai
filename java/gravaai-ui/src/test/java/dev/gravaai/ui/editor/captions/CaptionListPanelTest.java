package dev.gravaai.ui.editor.captions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.ui.kit.Theme;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** CaptionListPanel over the real {@link EditorStore} (state-mutating caption editing cases). */
@ExtendWith(ApplicationExtension.class)
class CaptionListPanelTest {

	private Stage stage;
	private EditorStore store;
	private CaptionListPanel panel;
	private final AtomicReference<Double> playhead = new AtomicReference<>(0.0);

	@Start
	void start(Stage stage) {
		this.stage = stage;
	}

	@Test
	@DisplayName("editing the caption text updates the store word-aware")
	void editsTextWordAware(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			store.setSelectedCaptionId("cap-a");
			panel.refresh();
			panel.textEditor().setText("Hi");
			panel.commitTextDraft();
		});
		WaitForAsyncUtils.waitForFxEvents();

		CaptionCue cue = store.state().autoCaptions().get(0);
		assertEquals("Hi", cue.text());
		assertNotNull(cue.words());
		assertEquals(1, cue.words().size());
		assertEquals("Hi", cue.words().get(0).text());
		assertEquals(1_000, cue.words().get(0).startMs());
		assertEquals(2_000, cue.words().get(0).endMs());
	}

	@Test
	@DisplayName("retimes the selected cue from the timecode fields")
	void retimesFromTimecodeFields(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			store.setSelectedCaptionId("cap-b");
			panel.refresh();
			panel.startEditor().setText("0:02.000");
			panel.endEditor().setText("0:03.200");
			panel.commitTimingDraft();
		});
		WaitForAsyncUtils.waitForFxEvents();

		CaptionCue cue = store.state().autoCaptions().get(1);
		assertEquals(2_000, cue.startMs());
		assertEquals(3_200, cue.endMs());
	}

	@Test
	@DisplayName("splits the selected cue at the playhead")
	void splitsAtPlayhead(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			playhead.set(1_500.0);
			store.setSelectedCaptionId("cap-a");
			panel.refresh();
			panel.splitAction().fire();
		});
		WaitForAsyncUtils.waitForFxEvents();

		assertEquals(3, store.state().autoCaptions().size());
		assertEquals("Hello", store.state().autoCaptions().get(0).text());
		assertEquals("Hello", store.state().autoCaptions().get(1).text());
		assertEquals("cap-a", store.state().autoCaptions().get(0).id());
	}

	@Test
	@DisplayName("merges the selected cue with the next one")
	void mergesWithNextCue(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			store.setSelectedCaptionId("cap-a");
			panel.refresh();
			panel.mergeAction().fire();
		});
		WaitForAsyncUtils.waitForFxEvents();

		assertEquals(1, store.state().autoCaptions().size());
		CaptionCue merged = store.state().autoCaptions().get(0);
		assertEquals("cap-a", merged.id());
		assertEquals(1_000, merged.startMs());
		assertEquals(3_000, merged.endMs());
		assertEquals("Hello Hello Second caption", merged.text());
	}

	@Test
	@DisplayName("deletes the selected cue and hides the panel")
	void deletesSelectedCue(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			store.setSelectedCaptionId("cap-b");
			panel.refresh();
			panel.deleteAction().fire();
		});
		WaitForAsyncUtils.waitForFxEvents();

		assertEquals(1, store.state().autoCaptions().size());
		assertEquals("cap-a", store.state().autoCaptions().get(0).id());
		assertTrue(store.selectedCaptionId() == null || !panel.isVisible());
	}

	@Test
	@DisplayName("follows the store caption selection in both directions")
	void followsStoreSelection(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			store.setSelectedCaptionId("cap-a");
			panel.refresh();
		});
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			assertTrue(panel.isVisible());
			assertEquals("Hello Hello", panel.textEditor().getText());
		});

		robot.interact(() -> {
			store.setSelectedCaptionId("cap-b");
			panel.refresh();
		});
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> assertEquals("Second caption", panel.textEditor().getText()));

		robot.interact(() -> {
			store.setSelectedCaptionId(null);
			panel.refresh();
		});
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> assertFalse(panel.isVisible()));
	}

	private void buildScene() {
		store = CaptionFixtures.store();
		panel = new CaptionListPanel(store, playhead::get);
		StackPane root = new StackPane(panel);
		root.setStyle("-fx-background-color: #121418;");
		root.setAlignment(Pos.TOP_LEFT);
		Scene scene = new Scene(root, 520, 420);
		Theme.install(scene);
		stage.setScene(scene);
		stage.show();
		panel.refresh();
	}
}
