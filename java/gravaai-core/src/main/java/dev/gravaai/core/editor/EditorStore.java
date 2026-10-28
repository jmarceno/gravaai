package dev.gravaai.core.editor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.gravaai.core.event.Subscription;
import dev.gravaai.core.event.UiEvents;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Ids;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.ZoomRegion;
import tools.jackson.databind.node.ObjectNode;

/**
 * The editor state store (plan §5.11): holds the current {@link ProjectEditorState}, applies
 * {@link EditorCommand}s, keeps the undo/redo stack exactly like {@code editorHistory.ts}, owns the
 * selection and publishes JavaFX-free change events for the UI to adapt.
 *
 * <p>Region edits are applied through the tested project JSON normaliser: the store serialises the
 * current editor state, the command replaces one region list, and the normaliser rebuilds the typed
 * state (which also applies the clip-sequence ripple rules). This keeps commands small and makes
 * every mutation go through the same canonical path as loading a project.
 */
public final class EditorStore {

	public static final String SECTION_SCENE = "scene";
	public static final String SECTION_CLIP = "clip";
	public static final String SECTION_ZOOM = "zoom";
	public static final String SECTION_AUDIO = "audio";

	private final EditorHistory.Stack history;
	private final Map<String, Integer> counters = new HashMap<>();
	private final UiEvents<EditorStore> changes = new UiEvents<>();

	private ProjectEditorState state;
	private String selectedClipId;
	private String selectedZoomId;
	private String selectedAnnotationId;
	private String selectedAudioId;
	private String selectedCaptionId;
	private String activeEffectSection = SECTION_SCENE;

	public EditorStore(ProjectEditorState initialState) {
		this.state = initialState;
		this.history = EditorHistory.createStack();
		EditorHistory.record(history, snapshot());
	}

	public ProjectEditorState state() {
		return state;
	}

	public EditorHistory.Stack history() {
		return history;
	}

	/** Subscribes to state changes; the UI adapts this to the FX thread (plan §4.1). */
	public Subscription onChange(Consumer<EditorStore> listener) {
		return changes.subscribe(listener);
	}

	/** Applies a command, records an undo step when it changed the state, and publishes. */
	public boolean apply(EditorCommand command) {
		boolean changed = command.apply(this);
		if (changed) {
			EditorHistory.record(history, snapshot());
			changes.publish(this);
		}
		return changed;
	}

	public ObjectNode editorJson() {
		return ProjectDocument.writeEditor(state, Json.nodeFactory().objectNode());
	}

	public void replaceState(ProjectEditorState nextState) {
		this.state = nextState;
	}

	public void setZoomRegions(List<ZoomRegion> regions) {
		ObjectNode json = editorJson();
		json.set("zoomRegions", ProjectDocument.writeZoomRegions(regions));
		state = ProjectPersistence.normalizeProjectEditor(json);
	}

	public void setClipRegions(List<ClipRegion> regions) {
		ObjectNode json = editorJson();
		json.set("clipRegions", ProjectDocument.writeClipRegions(regions));
		state = ProjectPersistence.normalizeProjectEditor(json);
	}

	public void setSpeedRegions(List<SpeedRegion> regions) {
		ObjectNode json = editorJson();
		json.set("speedRegions", ProjectDocument.writeSpeedRegions(regions));
		state = ProjectPersistence.normalizeProjectEditor(json);
	}

	public void setAnnotationRegions(List<AnnotationRegion> regions) {
		ObjectNode json = editorJson();
		json.set("annotationRegions", ProjectDocument.writeAnnotationRegions(regions));
		state = ProjectPersistence.normalizeProjectEditor(json);
	}

	public void setAudioRegions(List<AudioRegion> regions) {
		ObjectNode json = editorJson();
		json.set("audioRegions", ProjectDocument.writeAudioRegions(regions));
		state = ProjectPersistence.normalizeProjectEditor(json);
	}

	/** Replaces the canonical raw source-audio settings map (unknown keys preserved). */
	public void setSourceAudioTrackSettingsByClip(tools.jackson.databind.node.ObjectNode next) {
		state = state.withSourceAudioTrackSettingsByClip(next);
	}

	public void setAutoCaptions(List<CaptionCue> cues) {
		// Raw list: a just-added cue has empty text, which only load-time normalisation drops.
		state = state.withAutoCaptions(List.copyOf(cues));
	}

	public void setAutoCaptionSettings(dev.gravaai.core.project.AutoCaptionSettings settings) {
		ObjectNode json = editorJson();
		json.set("autoCaptionSettings", ProjectDocument.writeAutoCaptionSettings(settings));
		state = ProjectPersistence.normalizeProjectEditor(json);
	}

	/** Next generated id for a prefix (e.g. {@code clip}); respects ids already in the state. */
	public String nextId(String prefix, List<String> existingIds) {
		int next = Math.max(counters.getOrDefault(prefix, 1), Ids.deriveNextId(prefix, existingIds));
		counters.put(prefix, next + 1);
		return prefix + "-" + next;
	}

	/** Next value of a plain counter (e.g. annotation z-index). */
	public int nextIndex(String counterKey) {
		int next = counters.getOrDefault(counterKey, 1);
		counters.put(counterKey, next + 1);
		return next;
	}

	public EditorHistory.Snapshot snapshot() {
		return new EditorHistory.Snapshot(state.zoomRegions(), state.clipRegions(),
				state.speedRegions(), state.annotationRegions(), state.audioRegions(),
				state.autoCaptions(), selectedZoomId, selectedClipId, selectedAnnotationId,
				selectedAudioId,
				state.sourceAudioTrackSettingsByClip() == null ? null
						: state.sourceAudioTrackSettingsByClip().deepCopy());
	}

	private void restore(EditorHistory.Snapshot snapshot) {
		setZoomRegions(snapshot.zoomRegions());
		setClipRegions(snapshot.clipRegions());
		setSpeedRegions(snapshot.speedRegions());
		setAnnotationRegions(snapshot.annotationRegions());
		setAudioRegions(snapshot.audioRegions());
		setAutoCaptions(snapshot.autoCaptions());
		selectedZoomId = snapshot.selectedZoomId();
		selectedClipId = snapshot.selectedClipId();
		selectedAnnotationId = snapshot.selectedAnnotationId();
		selectedAudioId = snapshot.selectedAudioId();
		setSourceAudioTrackSettingsByClip(snapshot.sourceAudioTrackSettingsByClip() == null
				? null : snapshot.sourceAudioTrackSettingsByClip().deepCopy());
	}

	public boolean undo() {
		EditorHistory.Snapshot previous = EditorHistory.undo(history, snapshot());
		if (previous == null) {
			return false;
		}
		restore(previous);
		changes.publish(this);
		return true;
	}

	public boolean redo() {
		EditorHistory.Snapshot next = EditorHistory.redo(history, snapshot());
		if (next == null) {
			return false;
		}
		restore(next);
		changes.publish(this);
		return true;
	}

	public String selectedClipId() {
		return selectedClipId;
	}

	public void setSelectedClipId(String id) {
		this.selectedClipId = id;
	}

	public String selectedZoomId() {
		return selectedZoomId;
	}

	public void setSelectedZoomId(String id) {
		this.selectedZoomId = id;
	}

	public String selectedAnnotationId() {
		return selectedAnnotationId;
	}

	public void setSelectedAnnotationId(String id) {
		this.selectedAnnotationId = id;
	}

	public String selectedAudioId() {
		return selectedAudioId;
	}

	public void setSelectedAudioId(String id) {
		this.selectedAudioId = id;
	}

	public String selectedCaptionId() {
		return selectedCaptionId;
	}

	public void setSelectedCaptionId(String id) {
		this.selectedCaptionId = id;
	}

	public String activeEffectSection() {
		return activeEffectSection;
	}

	public void setActiveEffectSection(String section) {
		this.activeEffectSection = section;
	}

	/** Clears every timeline selection except the given one. */
	public void clearSelectionsExcept(String keepClip, String keepZoom, String keepAnnotation,
			String keepAudio, String keepCaption) {
		selectedClipId = keepClip;
		selectedZoomId = keepZoom;
		selectedAnnotationId = keepAnnotation;
		selectedAudioId = keepAudio;
		selectedCaptionId = keepCaption;
	}

	/**
	 * Applies a whole recording-import batch (clip/zoom/annotation/audio regions, webcam and the
	 * new clip selection) as one undoable command, mirroring React's synchronous state updates at
	 * the end of {@code useRecordingLibrary.addToTimeline}. The changed check captures the previous
	 * clip selection before the mutation, like the region commands do.
	 */
	public boolean applyImportBatch(Consumer<ObjectNode> mutation, String selectedClipId) {
		return apply(editor -> {
			ObjectNode before = editor.editorJson();
			ObjectNode json = before.deepCopy();
			mutation.accept(json);
			ProjectEditorState next = ProjectPersistence.normalizeProjectEditor(json);
			String previousClip = editor.selectedClipId();
			editor.replaceState(next);
			editor.setSelectedClipId(selectedClipId);
			return !ProjectPersistence.normalizeProjectEditor(before).equals(next)
					|| !java.util.Objects.equals(previousClip, selectedClipId);
		});
	}
}
