package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomMode;
import javafx.application.Platform;

/**
 * Rail click clears every timeline selection before switching the inspector section (port of
 * {@code EditorShell.tsx} {@code setActiveSection}, plan P3.5 sweep item).
 *
 * <p>Needs a JavaFX toolkit, so it only runs when {@code DISPLAY} is set like the other UI tests.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class EditorRailSelectionTest {

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			Platform.runLater(() -> { });
		}
	}

	private static void onFx(ThrowingRunnable work) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				work.run();
			} catch (Throwable error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(60, TimeUnit.SECONDS), "FX work timed out");
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static EditorStore storeWithSelections() {
		EditorStore store = new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"clip-1","startMs":0,"endMs":6000,"speed":1}],
						 "zoomRegions":[{"id":"zoom-1","startMs":0,"endMs":1000,
						   "depth":3,"focus":{"cx":0.5,"cy":0.5},"mode":"auto"}],
						 "audioRegions":[{"id":"audio-1","startMs":0,"endMs":1000,"sourcePath":"/tmp/a.wav"}]}
						""")));
		store.setSelectedClipId("clip-1");
		store.setSelectedZoomId("zoom-1");
		store.setSelectedAnnotationId("annotation-x");
		store.setSelectedAudioId("audio-1");
		store.setSelectedCaptionId("caption-x");
		return store;
	}

	private static EditorStore storeWithZoom() {
		return new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"clip-1","startMs":0,"endMs":6000,"speed":1}],
						 "zoomRegions":[{"id":"zoom-1","startMs":0,"endMs":1000,
						   "depth":3,"focus":{"cx":0.5,"cy":0.5},"mode":"auto"}]}
						""")));
	}

	@DisplayName("selecting a timeline zoom switches to the zoom editor and keeps the rail section")
	@Test
	void selectingZoomShowsZoomInspector() throws Exception {
		AtomicReference<EditorShellView> shellRef = new AtomicReference<>();
		onFx(() -> {
			EditorStore store = storeWithZoom();
			EditorShellView shell = new EditorShellView(store, new EditorViewActions() { },
					new FakePlayback());
			shellRef.set(shell);
			shell.inspector();
			store.apply(new ZoomCommands.Select("zoom-1"));
		});
		onFx(() -> {
			EditorShellView shell = shellRef.get();
			try {
				assertEquals(EditorSidebarRail.Section.SCENE, shell.rail().active());
				assertEquals(EditorInspectorPanel.SelectionEditor.ZOOM,
						shell.inspector().selectionEditor());
				ZoomSettingsPanel zoom = shell.inspector().zoomSettingsPanel();
				assertNotNull(zoom);
				assertEquals("3", zoom.amountGroup().value());
				assertEquals("auto", zoom.modeGroup().value());
				assertEquals("1.25x", amountLabel(zoom, 0));
				assertEquals("1.5x", amountLabel(zoom, 1));
				assertEquals("1.8x", amountLabel(zoom, 2));
				assertEquals("2.2x", amountLabel(zoom, 3));
				assertEquals("3.5x", amountLabel(zoom, 4));
				assertEquals("5x", amountLabel(zoom, 5));
			} finally {
				shell.dispose();
			}
		});
	}

	@DisplayName("zoom editor applies amount and mode changes and deletes the region")
	@Test
	void zoomEditorAppliesCommands() throws Exception {
		onFx(() -> {
			EditorStore store = storeWithZoom();
			store.setSelectedZoomId("zoom-1");
			ZoomSettingsPanel zoom = new ZoomSettingsPanel(store);
			zoom.amountGroup().setValue("6");
			assertEquals(ZoomDepth.SIX, store.state().zoomRegions().get(0).depth());
			zoom.refresh();
			zoom.modeGroup().setValue("manual");
			assertEquals(ZoomMode.MANUAL, store.state().zoomRegions().get(0).mode());
			zoom.refresh();
			zoom.deleteButton().fire();
			assertTrue(store.state().zoomRegions().isEmpty());
			assertNull(store.selectedZoomId());
		});
	}

	private static String amountLabel(ZoomSettingsPanel zoom, int index) {
		return ((javafx.scene.control.ToggleButton) zoom.amountGroup().getChildren().get(index))
				.getText();
	}

	@DisplayName("selecting an inspector section clears clip, zoom, annotation, audio and caption selections")
	@Test
	void railClickClearsSelections() throws Exception {
		onFx(() -> {
			EditorStore store = storeWithSelections();
			EditorShellView shell = new EditorShellView(store, new EditorViewActions() { },
					new FakePlayback());
			try {
				assertEquals("clip-1", store.selectedClipId());
				assertEquals("zoom-1", store.selectedZoomId());
				assertEquals("annotation-x", store.selectedAnnotationId());
				assertEquals("audio-1", store.selectedAudioId());
				assertEquals("caption-x", store.selectedCaptionId());

				shell.rail().select(EditorSidebarRail.Section.CURSOR);
				assertNull(store.selectedClipId());
				assertNull(store.selectedZoomId());
				assertNull(store.selectedAnnotationId());
				assertNull(store.selectedAudioId());
				assertNull(store.selectedCaptionId());
				assertTrue(true);
			} finally {
				shell.dispose();
			}
		});
	}

	/** Minimal playback port so the shell can be constructed off the real engine. */
	private static final class FakePlayback implements EditorPlaybackPort {
		private final dev.gravaai.core.event.UiEvents<PlaybackState> changes =
				new dev.gravaai.core.event.UiEvents<>();

		@Override
		public PlaybackState state() {
			return PlaybackState.IDLE;
		}

		@Override
		public dev.gravaai.core.event.UiEvents<PlaybackState> changes() {
			return changes;
		}

		@Override
		public void togglePlayPause() {
		}

		@Override
		public void seek(double seconds) {
		}

		@Override
		public void skip(double deltaSeconds) {
		}

		@Override
		public void setVolume(double volume) {
		}
	}
}
