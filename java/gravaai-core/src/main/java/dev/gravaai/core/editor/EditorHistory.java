package dev.gravaai.core.editor;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.ZoomRegion;

/**
 * Undo/redo for the timeline selections (port of
 * {@code src/components/video-editor/editorHistory.ts}, plan §5.11).
 *
 * <p>Snapshots are immutable records whose lists are copied defensively, so they play the role of
 * {@code structuredClone}: later edits to the editor cannot change what is stored in the stack.
 * Deep equality of two snapshots is record equality (the TS {@code areDeepEqual} over plain JSON).
 */
public final class EditorHistory {

	public static final int MAX_EDITOR_HISTORY_ENTRIES = 100;

	/** The undoable part of the editor state. */
	public record Snapshot(
			List<ZoomRegion> zoomRegions,
			List<ClipRegion> clipRegions,
			List<SpeedRegion> speedRegions,
			List<AnnotationRegion> annotationRegions,
			@Nullable List<AudioRegion> audioRegions,
			List<CaptionCue> autoCaptions,
			@Nullable String selectedZoomId,
			@Nullable String selectedClipId,
			@Nullable String selectedAnnotationId,
			@Nullable String selectedAudioId,
			tools.jackson.databind.node.@Nullable ObjectNode sourceAudioTrackSettingsByClip,
			List<String> multiSelectedIds,
			List<dev.gravaai.core.project.KeystrokeElement> keystrokeElements,
			@Nullable String selectedKeystrokeId) {

		public Snapshot(List<ZoomRegion> zoomRegions, List<ClipRegion> clipRegions,
			List<SpeedRegion> speedRegions, List<AnnotationRegion> annotationRegions,
			List<AudioRegion> audioRegions, List<CaptionCue> autoCaptions,
			String selectedZoomId, String selectedClipId, String selectedAnnotationId,
			String selectedAudioId) {
			this(zoomRegions, clipRegions, speedRegions, annotationRegions, audioRegions,
				autoCaptions, selectedZoomId, selectedClipId, selectedAnnotationId, selectedAudioId,
				null, List.of());
		}

		public Snapshot(List<ZoomRegion> zoomRegions, List<ClipRegion> clipRegions,
			List<SpeedRegion> speedRegions, List<AnnotationRegion> annotationRegions,
			List<AudioRegion> audioRegions, List<CaptionCue> autoCaptions,
			String selectedZoomId, String selectedClipId, String selectedAnnotationId,
			String selectedAudioId, tools.jackson.databind.node.@Nullable ObjectNode sourceAudio) {
			this(zoomRegions, clipRegions, speedRegions, annotationRegions, audioRegions,
				autoCaptions, selectedZoomId, selectedClipId, selectedAnnotationId, selectedAudioId,
				sourceAudio, List.of());
		}

		public Snapshot(List<ZoomRegion> zoomRegions, List<ClipRegion> clipRegions,
			List<SpeedRegion> speedRegions, List<AnnotationRegion> annotationRegions,
			List<AudioRegion> audioRegions, List<CaptionCue> autoCaptions,
			String selectedZoomId, String selectedClipId, String selectedAnnotationId,
			String selectedAudioId, tools.jackson.databind.node.@Nullable ObjectNode sourceAudio,
			List<String> multiSelectedIds) {
			this(zoomRegions, clipRegions, speedRegions, annotationRegions, audioRegions,
				autoCaptions, selectedZoomId, selectedClipId, selectedAnnotationId, selectedAudioId,
				sourceAudio, multiSelectedIds, List.of(), null);
		}
		}

	/** TS {@code "initialized" | "applied" | "recorded" | "unchanged"}. */
	public enum RecordResult {
		INITIALIZED, APPLIED, RECORDED, UNCHANGED
	}

	/** Options for {@link #record(Stack, Snapshot, RecordOptions)}; null fields take defaults. */
	public record RecordOptions(@Nullable Boolean applyingHistory, @Nullable Integer maxEntries, @Nullable String label) {

		public static final RecordOptions DEFAULT = new RecordOptions(null, null, null);

		public static RecordOptions applyHistory() {
			return new RecordOptions(true, null, null);
		}

		public static RecordOptions withMaxEntries(int maxEntries) {
			return new RecordOptions(null, maxEntries, null);
		}
	}

	/** The undo/redo stacks; mutable by design, exactly like the TS object. */
	public static final class Stack {
		private List<Snapshot> past = new ArrayList<>();
		private List<String> pastLabels = new ArrayList<>();
		private @Nullable Snapshot current;
		private List<Snapshot> future = new ArrayList<>();

		public List<Snapshot> past() {
			return past;
		}

		public List<String> pastLabels() {
			return pastLabels;
		}

		public @Nullable Snapshot current() {
			return current;
		}

		public List<Snapshot> future() {
			return future;
		}
	}

	private EditorHistory() {}

	public static Stack createStack() {
		return new Stack();
	}

	public static void reset(Stack stack) {
		stack.past = new ArrayList<>();
		stack.pastLabels = new ArrayList<>();
		stack.current = null;
		stack.future = new ArrayList<>();
	}

	/** {@code cloneEditorHistorySnapshot}: a copy whose lists cannot alias the editor's. */
	public static Snapshot cloneSnapshot(Snapshot snapshot) {
		return new Snapshot(
				List.copyOf(snapshot.zoomRegions()),
				List.copyOf(snapshot.clipRegions()),
				List.copyOf(snapshot.speedRegions()),
				List.copyOf(snapshot.annotationRegions()),
				List.copyOf(snapshot.audioRegions()),
				List.copyOf(snapshot.autoCaptions()),
				snapshot.selectedZoomId(),
				snapshot.selectedClipId(),
				snapshot.selectedAnnotationId(),
				snapshot.selectedAudioId(),
				snapshot.sourceAudioTrackSettingsByClip() == null ? null
						: snapshot.sourceAudioTrackSettingsByClip().deepCopy(),
				List.copyOf(snapshot.multiSelectedIds()),
				List.copyOf(snapshot.keystrokeElements()),
				snapshot.selectedKeystrokeId());
	}

	/** {@code areEditorHistorySnapshotsEqual}: records compare their lists element by element. */
	public static boolean areSnapshotsEqual(Snapshot left, Snapshot right) {
		return Objects.equals(left, right);
	}

	public static @Nullable RecordResult record(Stack stack, Snapshot snapshot) {
		return record(stack, snapshot, RecordOptions.DEFAULT);
	}

	public static @Nullable RecordResult record(Stack stack, Snapshot snapshot, RecordOptions options) {
		RecordOptions effectiveOptions = options == null ? RecordOptions.DEFAULT : options;
		Snapshot clonedSnapshot = cloneSnapshot(snapshot);

		if (stack.current == null) {
			stack.current = clonedSnapshot;
			return RecordResult.INITIALIZED;
		}

		if (Boolean.TRUE.equals(effectiveOptions.applyingHistory())) {
			stack.current = clonedSnapshot;
			return RecordResult.APPLIED;
		}

		if (areSnapshotsEqual(stack.current, snapshot)) {
			return RecordResult.UNCHANGED;
		}

		stack.past.add(cloneSnapshot(stack.current));
		stack.pastLabels.add(effectiveOptions.label() == null ? "" : effectiveOptions.label());
		int maxEntries = effectiveOptions.maxEntries() == null
				? MAX_EDITOR_HISTORY_ENTRIES
				: effectiveOptions.maxEntries();
		if (stack.past.size() > maxEntries) {
			stack.past.remove(0);
			stack.pastLabels.remove(0);
		}

		stack.current = clonedSnapshot;
		stack.future = new ArrayList<>();
		return RecordResult.RECORDED;
	}

	public static @Nullable Snapshot undo(Stack stack, Snapshot fallbackCurrent) {
		if (stack.past.isEmpty()) {
			return null;
		}

		Snapshot current = stack.current != null ? stack.current : cloneSnapshot(fallbackCurrent);
		Snapshot previous = stack.past.remove(stack.past.size() - 1);
		if (!stack.pastLabels.isEmpty()) {
			stack.pastLabels.remove(stack.pastLabels.size() - 1);
		}

		stack.future.add(cloneSnapshot(current));
		stack.current = cloneSnapshot(previous);
		return cloneSnapshot(previous);
	}

	public static @Nullable Snapshot redo(Stack stack, Snapshot fallbackCurrent) {
		if (stack.future.isEmpty()) {
			return null;
		}

		Snapshot current = stack.current != null ? stack.current : cloneSnapshot(fallbackCurrent);
		Snapshot next = stack.future.remove(stack.future.size() - 1);

		stack.past.add(cloneSnapshot(current));
		stack.pastLabels.add("");
		stack.current = cloneSnapshot(next);
		return cloneSnapshot(next);
	}

	/** {@code peekUndoLabel}: label of the change Ctrl+Z would undo, when there is one. */
	public static @Nullable String peekUndoLabel(Stack stack) {
		if (stack.pastLabels.isEmpty()) {
			return null;
		}
		String label = stack.pastLabels.get(stack.pastLabels.size() - 1);
		return label != null && !label.isEmpty() ? label : null;
	}
}
