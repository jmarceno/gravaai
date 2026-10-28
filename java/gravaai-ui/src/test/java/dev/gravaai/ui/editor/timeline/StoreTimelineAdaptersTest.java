package dev.gravaai.ui.editor.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.event.Subscription;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.SourceAudioTrackSettings;
import dev.gravaai.core.project.ZoomFocus;
import dev.gravaai.ui.editor.timeline.core.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * State-mutating behaviour over the real {@link EditorStore} (the Playwright timeline/clip cases
 * that need a store rather than the recording fake).
 */
class StoreTimelineAdaptersTest {

	private static final String FIXTURE = """
			{
			  "clipRegions": [
			    {"id":"clip-1","startMs":0,"endMs":3000,"speed":1},
			    {"id":"clip-2","startMs":3000,"endMs":6000,"speed":1}
			  ],
			  "zoomRegions": [
			    {"id":"zoom-1","startMs":1000,"endMs":2000,"depth":3,"focus":{"cx":0.5,"cy":0.5}}
			  ],
			  "annotationRegions": [
			    {"id":"ann-1","startMs":1000,"endMs":2000,"type":"text","content":"Note"}
			  ],
			  "audioRegions": [
			    {"id":"aud-1","startMs":500,"endMs":1500,"audioPath":"/tmp/a.wav","volume":1}
			  ]
			}
			""";

	private static EditorStore store() {
		return new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark(FIXTURE)));
	}

	@Test
	@DisplayName("exposes the store state, duration, playhead and waveform port")
	void exposesReadModel() {
		EditorStore store = store();
		StoreTimelineDataSource source = new StoreTimelineDataSource(store, () -> 6000, () -> 1234.5,
				null);

		assertEquals(store.state(), source.project());
		assertEquals(6000, source.totalDurationMs());
		assertEquals(1234.5, source.currentTimeMs());
		assertNotNull(source.waveforms());
	}

	@Test
	@DisplayName("adds zooms and captions through the store and selects them")
	void addsZoomsAndCaptions() {
		EditorStore store = store();
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });

		commands.addZoom(2500, 3000);
		assertEquals(2, store.state().zoomRegions().size());
		assertNotNull(store.selectedZoomId());

		commands.addCaption(1000, 2000);
		assertEquals(1, store.state().autoCaptions().size());
		assertEquals(1000, store.state().autoCaptions().get(0).startMs());
	}

	@Test
	@DisplayName("splits the selected clip at the playhead")
	void splitsClip() {
		EditorStore store = store();
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });

		commands.splitClip("clip-1", 1500);

		assertEquals(3, store.state().clipRegions().size());
		assertEquals(0, store.state().clipRegions().get(0).startMs());
		assertEquals(1500, store.state().clipRegions().get(0).endMs());
		assertEquals(1500, store.state().clipRegions().get(1).startMs());
		assertEquals(3000, store.state().clipRegions().get(1).endMs());
	}

	@Test
	@DisplayName("deletes the selected block by its kind")
	void deletesSelectedKind() {
		EditorStore store = store();
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });

		commands.select(new TimelineSelection(TimelineSelection.ZOOM, "zoom-1"));
		commands.deleteSelection();
		assertEquals(0, store.state().zoomRegions().size());

		commands.select(new TimelineSelection(TimelineSelection.ANNOTATION, "ann-1"));
		commands.deleteSelection();
		assertEquals(0, store.state().annotationRegions().size());

		commands.select(new TimelineSelection(TimelineSelection.AUDIO, "aud-1"));
		commands.deleteSelection();
		assertEquals(0, store.state().audioRegions().size());

		commands.select(new TimelineSelection(TimelineSelection.CLIP, "clip-1"));
		commands.deleteSelection();
		assertEquals(1, store.state().clipRegions().size());
	}

	@Test
	@DisplayName("moves a zoom and resizes a clip through the change-span commands")
	void movesAndResizes() {
		EditorStore store = store();
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { })
				.sourceDuration(id -> 6000);

		commands.move("zoom-1", "row-zoom", new Span(1200, 2200));
		assertEquals(1200, store.state().zoomRegions().get(0).startMs());
		assertEquals(2200, store.state().zoomRegions().get(0).endMs());

		commands.resizeEnd("clip-1", new Span(0, 2000));
		assertEquals(2000, store.state().clipRegions().get(0).endMs());
	}

	@Test
	@DisplayName("reorders the clip sequence from a compacted drag span")
	void reordersClips() {
		EditorStore store = store();
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });

		commands.move("clip-1", "row-clip", new Span(3000, 6000));

		List<String> ids = store.state().clipRegions().stream()
				.map(clip -> clip.id()).toList();
		assertEquals(List.of("clip-2", "clip-1"), ids);
		assertEquals(0, store.state().clipRegions().get(0).startMs());
		assertEquals(3000, store.state().clipRegions().get(1).startMs());
	}

	@Test
	@DisplayName("forwards seeks in seconds to the playback engine")
	void forwardsSeek() {
		AtomicReference<Double> seconds = new AtomicReference<>();
		StoreTimelineCommands commands = new StoreTimelineCommands(store(), seconds::set);

		commands.seek(2500);

		assertEquals(2.5, seconds.get());
	}

	@Test
	@DisplayName("publishes store changes to the data source listener")
	void publishesChanges() {
		EditorStore store = store();
		StoreTimelineDataSource source = new StoreTimelineDataSource(store, () -> 6000, () -> 0,
				null);
		AtomicInteger changes = new AtomicInteger();
		Subscription subscription = source.onChange(changes::incrementAndGet);
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });

		commands.addZoom(2000, 3000);
		assertEquals(1, changes.get());

		commands.clearSelection();
		assertNull(store.selectedZoomId());
		subscription.close();
	}

	@Test
	@DisplayName("two cuts leave three clips and deleting the middle keeps the outer spans")
	void twoCutsThenMiddleDelete() {
		EditorStore store = new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{
						  "clipRegions": [
						    {"id":"clip-1","startMs":0,"endMs":6000,"speed":1}
						  ]
						}
						""")));
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });

		commands.splitClip("clip-1", 2000);
		commands.splitClip(store.state().clipRegions().get(1).id(), 4000);
		assertEquals(3, store.state().clipRegions().size());
		assertEquals(0, store.state().clipRegions().get(0).startMs());
		assertEquals(2000, store.state().clipRegions().get(0).endMs());
		assertEquals(2000, store.state().clipRegions().get(1).startMs());
		assertEquals(4000, store.state().clipRegions().get(1).endMs());
		assertEquals(4000, store.state().clipRegions().get(2).startMs());
		assertEquals(6000, store.state().clipRegions().get(2).endMs());

		String middleId = store.state().clipRegions().get(1).id();
		commands.select(new TimelineSelection(TimelineSelection.CLIP, middleId));
		commands.deleteSelection();

		assertEquals(2, store.state().clipRegions().size());
		assertEquals(0, store.state().clipRegions().get(0).startMs());
		assertEquals(2000, store.state().clipRegions().get(0).endMs());
		assertEquals(2000, store.state().clipRegions().get(1).startMs());
		assertEquals(4000, store.state().clipRegions().get(1).endMs());
	}

	@Test
	@DisplayName("undo restores a deleted block and redo deletes it again")
	void undoesAndRedoesDeletion() {
		EditorStore store = store();
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });
		commands.select(new TimelineSelection(TimelineSelection.ZOOM, "zoom-1"));
		commands.deleteSelection();
		assertEquals(0, store.state().zoomRegions().size());

		assertTrue(store.undo());
		assertEquals(1, store.state().zoomRegions().size());
		assertEquals(1000, store.state().zoomRegions().get(0).startMs());

		assertTrue(store.redo());
		assertEquals(0, store.state().zoomRegions().size());
	}

	@Test
	@DisplayName("adds a suggested zoom with its focus and deletes one zoom")
	void addsSuggestedZoomAndDeletesOne() {
		EditorStore store = store();
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });

		commands.addSuggestedZoom(4_000, 5_000, new ZoomFocus(0.25, 0.75));

		assertEquals(2, store.state().zoomRegions().size());
		var added = store.state().zoomRegions().get(1);
		assertEquals(4_000, added.startMs());
		assertEquals(5_000, added.endMs());
		assertEquals(0.25, added.focus().cx());
		assertEquals(0.75, added.focus().cy());

		commands.deleteZoom(added.id());
		assertEquals(1, store.state().zoomRegions().size());
	}

	@Test
	@DisplayName("sets a source-audio track through the store command")
	void setsSourceAudioTrack() {
		EditorStore store = store();
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });

		commands.setSourceAudioTrack("clip-1", SourceAudioTrackSettings.MIC, 0.5, true);

		SourceAudioTrackSettings applied = store.state().sourceAudioTrackSettings().get("clip-1");
		assertNotNull(applied);
		assertEquals(0.5, applied.track(SourceAudioTrackSettings.MIC).volume(), 0.001);
		assertEquals(true, applied.track(SourceAudioTrackSettings.MIC).normalize());
	}

	@Test
	@DisplayName("undo reverts a source-audio track edit")
	void undoesSourceAudioTrackEdit() {
		EditorStore store = store();
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });

		commands.setSourceAudioTrack("clip-1", SourceAudioTrackSettings.MIC, 0.5, true);

		SourceAudioTrackSettings applied = store.state().sourceAudioTrackSettings().get("clip-1");
		assertNotNull(applied);
		assertEquals(0.5, applied.track(SourceAudioTrackSettings.MIC).volume(), 0.001);
		assertEquals(true, applied.track(SourceAudioTrackSettings.MIC).normalize());

		assertTrue(store.undo(), "source-audio edit must record an undo step");
		SourceAudioTrackSettings afterUndo = store.state().sourceAudioTrackSettings().get("clip-1");
		assertTrue(afterUndo == null || afterUndo.track(SourceAudioTrackSettings.MIC) == null,
				"undo must revert the source-audio settings (was " + afterUndo + ")");

		assertTrue(store.redo());
		assertEquals(0.5,
				store.state().sourceAudioTrackSettings().get("clip-1")
						.track(SourceAudioTrackSettings.MIC).volume(), 0.001);
	}

	@Test
	@DisplayName("clears every selection kind")
	void clearsSelections() {
		EditorStore store = store();
		StoreTimelineCommands commands = new StoreTimelineCommands(store, seconds -> { });
		commands.select(new TimelineSelection(TimelineSelection.CLIP, "clip-1"));
		assertEquals("clip-1", store.selectedClipId());

		commands.clearSelection();

		assertNull(store.selectedClipId());
		assertNull(store.selectedZoomId());
		assertNull(store.selectedAnnotationId());
		assertNull(store.selectedAudioId());
		assertNull(store.selectedCaptionId());
	}
}