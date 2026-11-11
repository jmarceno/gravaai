package dev.gravaai.ui.editor.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.TranscriptCut;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.ui.editor.timeline.core.Span;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transcript panel's one-click actions end to end over the real store: the preview, the single
 * undoable apply, and the timeline spans that make the pending cut visible before and after.
 */
class TranscriptPanelCutTest {

	@BeforeAll
	static void startToolkit() {
		try {
			Platform.startup(() -> { });
		} catch (IllegalStateException alreadyRunning) {
			// Another test class in this JVM started the toolkit first.
		}
	}

	/**
	 * Runs the body on the FX thread. The panel refreshes itself through {@code Platform.runLater}
	 * on every store change, so driving its buttons from another thread interleaves two rebuilds
	 * of the word list.
	 */
	private static void onFx(Runnable body) throws Exception {
		java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.atomic.AtomicReference<RuntimeException> failure =
				new java.util.concurrent.atomic.AtomicReference<>();
		Platform.runLater(() -> {
			try {
				body.run();
			} catch (RuntimeException error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		done.await();
		RuntimeException error = failure.get();
		if (error != null) {
			throw error;
		}
	}

	private final FakeTranscriptUiService service = new FakeTranscriptUiService();
	private final List<List<Span>> previewPushes = new ArrayList<>();

	private TranscriptPanel panel(EditorStore store) {
		AtomicReference<String> language = new AtomicReference<>();
		TranscriptPanel panel = new TranscriptPanel(store, service, () -> 0.0, () -> false,
				seconds -> { }, null, cues -> { });
		panel.setCutPreviewSink(previewPushes::add);
		return panel;
	}

	private static List<ClipRegion> clips(EditorStore store) {
		return store.state().clipRegions();
	}

	@Test
	@DisplayName("Remove filler words previews the cut, then applies it as one undoable step")
	void removeFillersFromThePanel() throws Exception {
		onFx(() -> {
		EditorStore store = TranscriptFixtures.store();
		TranscriptPanel panel = panel(store);

		panel.removeFillersButton().fire();

		TranscriptCutPlan pending = panel.pendingCut();
		assertNotNull(pending, "clicking the action must prepare a batch");
		assertEquals(List.of(new TranscriptCut.SourceRange(1_500, 1_950)), pending.ranges());
		assertTrue(panel.isPreviewVisible(), "the panel must show what it will cut");
		assertEquals(List.of(new Span(1_500, 1_950)), previewPushes.get(previewPushes.size() - 1),
				"the timeline must show the pending cut");
		assertEquals(1, clips(store).size(), "previewing must not edit the project yet");

		panel.applyPreviewButton().fire();

		assertNull(panel.pendingCut());
		assertEquals(List.of(), panel.pushedCutPreview(), "applying clears the preview");
		assertEquals(2, clips(store).size(), "the filler is cut out of the clip sequence");
		assertTrue(panel.isWordCut("cue-1:1"), "the cut word is struck through in the transcript");
		assertEquals(1, store.history().past().size(), "the whole batch is one history step");

		assertTrue(store.undo());
		assertEquals(1, clips(store).size(), "one undo restores every cut of the batch");
		panel.refresh();
		assertFalse(panel.isWordCut("cue-1:1"));
		});
	}

	@Test
	@DisplayName("Cancel discards the batch without touching the project")
	void cancelKeepsTheProject() throws Exception {
		onFx(() -> {
		EditorStore store = TranscriptFixtures.store();
		TranscriptPanel panel = panel(store);

		panel.removeFillersButton().fire();
		panel.cancelPreviewButton().fire();

		assertNull(panel.pendingCut());
		assertFalse(panel.isPreviewVisible());
		assertEquals(List.of(), panel.pushedCutPreview());
		assertEquals(1, clips(store).size());
		assertTrue(store.history().past().isEmpty());
		});
	}

	@Test
	@DisplayName("Shorten pauses uses the typed rule, previews it and applies it in one undo")
	void shortenPausesFromThePanel() throws Exception {
		onFx(() -> {
		EditorStore store = TranscriptFixtures.store();
		TranscriptPanel panel = panel(store);
		panel.setPauseRule(0.5, 0.2);

		panel.shortenPausesButton().fire();

		TranscriptCutPlan pending = panel.pendingCut();
		assertNotNull(pending);
		assertEquals(List.of(new TranscriptCut.SourceRange(1_200, 1_500),
				new TranscriptCut.SourceRange(1_900, 2_500),
				new TranscriptCut.SourceRange(3_200, 3_500)), pending.ranges());
		assertTrue(panel.isPreviewVisible());
		assertEquals(3, previewPushes.get(previewPushes.size() - 1).size(),
				"one timeline band per pause");

		panel.applyPreviewButton().fire();

		assertEquals(4, clips(store).size(), "three pauses split the single clip into four");
		assertEquals(1, store.history().past().size());
		assertTrue(store.undo());
		assertEquals(1, clips(store).size());
		});
	}

	@Test
	@DisplayName("an invalid pause rule is refused with a message and no preview")
	void rejectsAnInvalidPauseRule() throws Exception {
		onFx(() -> {
		EditorStore store = TranscriptFixtures.store();
		TranscriptPanel panel = panel(store);

		panel.setPauseRule(0.5, 0.9);
		panel.shortenPausesButton().fire();

		assertNull(panel.pendingCut());
		assertFalse(panel.isPreviewVisible());
		assertTrue(panel.feedbackText().contains("smaller than the minimum"),
				"the panel must say why nothing happened");
		assertEquals(1, clips(store).size());
		});
	}

	@Test
	@DisplayName("a batch with nothing left to cut explains itself instead of previewing")
	void explainsAnEmptyBatch() throws Exception {
		onFx(() -> {
		EditorStore store = TranscriptFixtures.store();
		TranscriptPanel panel = panel(store);
		panel.setPauseRule(10.0, 0.25);

		panel.shortenPausesButton().fire();

		assertNull(panel.pendingCut());
		assertFalse(panel.isPreviewVisible());
		assertFalse(panel.feedbackText().isBlank());
		});
	}

	@Test
	@DisplayName("shortening pauses still works after removing fillers split the clip")
	void composesTheTwoActions() throws Exception {
		onFx(() -> {
		EditorStore store = TranscriptFixtures.store();
		TranscriptPanel panel = panel(store);

		panel.removeFillersButton().fire();
		panel.setPauseRule(0.5, 0.2);
		panel.shortenPausesButton().fire();
		int cutsAfterFillers = panel.pendingCut() == null ? 0 : panel.pendingCut().ranges().size();
		panel.cancelPreviewButton().fire();
		panel.applyPreviewButton(); // no pending batch: a no-op, not an edit

		assertEquals(1, cutsAfterFillers,
				"one -> three is the only silence over 500ms once um is gone (550ms)");
		assertEquals(1, clips(store).size(), "the cancelled batch must not edit anything");
		});
	}

	@Test
	@DisplayName("disposing the panel clears a pending timeline preview")
	void disposeClearsThePreview() throws Exception {
		onFx(() -> {
		EditorStore store = TranscriptFixtures.store();
		TranscriptPanel panel = panel(store);
		panel.removeFillersButton().fire();
		assertFalse(panel.pushedCutPreview().isEmpty());

		panel.dispose();

		assertEquals(List.of(), panel.pushedCutPreview(),
				"leaving the section must not leave bands painted on the timeline");
		});
	}
}
