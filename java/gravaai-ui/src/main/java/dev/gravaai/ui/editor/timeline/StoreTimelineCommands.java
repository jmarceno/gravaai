package dev.gravaai.ui.editor.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

import dev.gravaai.core.editor.AnnotationCommands;
import dev.gravaai.core.editor.AudioCommands;
import dev.gravaai.core.editor.CaptionCommands;
import dev.gravaai.core.editor.ClipCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.SourceAudioCommands;
import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.core.project.CaptionOps;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.ui.editor.timeline.core.Span;
import dev.gravaai.ui.editor.timeline.core.TimelineConstants;
import dev.gravaai.ui.editor.timeline.core.TimelineRows;

/**
 * {@link TimelineCommands} over the real {@link EditorStore} commands (plan §5.11 / P2.7).
 *
 * <p>The store keeps undo/redo and the selection; the playback engine is reached through
 * {@link PlaybackSeek}. Clip drags are committed through {@code ClipCommands.ChangeSpan} with the
 * sequence index derived from the compacted span the timeline computed; other lanes use their
 * region command. Tests/simulations can inject the media duration with
 * {@link #sourceDuration(ToLongFunction)}; otherwise the clip's own source bounds are used.
 */
public final class StoreTimelineCommands implements TimelineCommands {

	private final EditorStore store;
	private final PlaybackSeek seek;
	private final PlaybackSeek scrub;
	private ToLongFunction<String> sourceDurationMs = this::fallbackSourceDuration;

	public StoreTimelineCommands(EditorStore store, PlaybackSeek seek) {
		this(store, seek, seek);
	}

	/** {@code scrub} is the non-blocking, latest-wins variant used while dragging the playhead. */
	public StoreTimelineCommands(EditorStore store, PlaybackSeek seek, PlaybackSeek scrub) {
		this.store = Objects.requireNonNull(store, "store");
		this.seek = seek;
		this.scrub = scrub;
	}

	/** Supplies the media duration of a clip for resize clamping (EditorSession wires the probe). */
	public StoreTimelineCommands sourceDuration(ToLongFunction<String> resolver) {
		this.sourceDurationMs = resolver == null ? this::fallbackSourceDuration : resolver;
		return this;
	}

	public EditorStore store() {
		return store;
	}

	@Override
	public void seek(double timeMs) {
		if (seek != null) {
			seek.seek(timeMs / 1000.0);
		}
	}

	@Override
	public void scrub(double timeMs) {
		if (scrub != null) {
			scrub.seek(timeMs / 1000.0);
		}
	}

	@Override
	public void select(TimelineSelection selection) {
		if (selection == null) {
			clearSelection();
			return;
		}
		switch (selection.kind()) {
			case TimelineSelection.CLIP -> store.apply(new ClipCommands.Select(selection.id()));
			case TimelineSelection.ZOOM -> store.apply(new ZoomCommands.Select(selection.id()));
			case TimelineSelection.AUDIO -> store.apply(new AudioCommands.Select(selection.id()));
			case TimelineSelection.ANNOTATION ->
				store.apply(new AnnotationCommands.Select(selection.id()));
			case TimelineSelection.CAPTION -> store.apply(new CaptionCommands.Select(selection.id()));
			default -> store.clearSelectionsExcept(null, null, null, null, null);
		}
	}

	@Override
	public void clearSelection() {
		store.clearSelectionsExcept(null, null, null, null, null);
	}

	@Override
	public void move(String itemId, String rowId, Span span) {
		if (TimelineConstants.CLIP_ROW_ID.equals(rowId)) {
			applyClipSpan(itemId, span, sequenceIndex(itemId, span.start()));
			return;
		}
		if (TimelineConstants.ZOOM_ROW_ID.equals(rowId)) {
			store.apply(new ZoomCommands.ChangeSpan(itemId, span.start(), span.end()));
			return;
		}
		if (TimelineRows.isAudioTrackRowId(rowId)) {
			store.apply(new AudioCommands.ChangeSpan(itemId, span.start(), span.end(),
					TimelineRows.getAudioTrackIndex(rowId)));
			return;
		}
		if (TimelineRows.isAnnotationTrackRowId(rowId)) {
			store.apply(new AnnotationCommands.ChangeSpan(itemId, span.start(), span.end(),
					TimelineRows.getAnnotationTrackIndex(rowId)));
			return;
		}
		if (TimelineConstants.CAPTION_ROW_ID.equals(rowId)) {
			retimeCaption(itemId, span);
		}
	}

	@Override
	public void resizeEnd(String itemId, Span span) {
		if (isClip(itemId)) {
			applyClipSpan(itemId, span, null);
			return;
		}
		move(itemId, rowOf(itemId), span);
	}

	@Override
	public void splitClip(String clipId, double timeMs) {
		if (clipId == null) {
			return;
		}
		store.setSelectedClipId(clipId);
		store.apply(new ClipCommands.Split(timeMs));
	}

	@Override
	public void deleteSelection() {
		String zoom = store.selectedZoomId();
		if (zoom != null) {
			store.apply(new ZoomCommands.Delete(zoom));
			return;
		}
		String clip = store.selectedClipId();
		if (clip != null) {
			store.apply(new ClipCommands.Delete(clip));
			return;
		}
		String annotation = store.selectedAnnotationId();
		if (annotation != null) {
			store.apply(new AnnotationCommands.Delete(annotation));
			return;
		}
		String audio = store.selectedAudioId();
		if (audio != null) {
			store.apply(new AudioCommands.Delete(audio));
			return;
		}
		String caption = store.selectedCaptionId();
		if (caption != null) {
			store.apply(new CaptionCommands.Delete(caption));
		}
	}

	@Override
	public void addZoom(double startMs, double endMs) {
		store.apply(new ZoomCommands.Add(startMs, endMs));
	}

	@Override
	public void addSuggestedZoom(double startMs, double endMs,
			dev.gravaai.core.project.ZoomFocus focus) {
		store.apply(new ZoomCommands.AddSuggested(startMs, endMs, focus));
	}

	@Override
	public void setSourceAudioTrack(String clipId, String trackId, double volume,
			boolean normalize) {
		store.apply(new SourceAudioCommands.SetTrackSettings(clipId, trackId, volume, normalize));
	}

	@Override
	public void deleteZoom(String id) {
		if (id != null) {
			store.apply(new ZoomCommands.Delete(id));
		}
	}

	@Override
	public void addCaption(double startMs, double endMs) {
		store.apply(new CaptionCommands.Add(startMs, endMs));
	}

	private void retimeCaption(String id, Span span) {
		if (id != null) {
			store.apply(new CaptionCommands.Retime(id,
					new CaptionOps.RetimeSpan(Math.round(span.start()), Math.round(span.end()))));
		}
	}

	private void applyClipSpan(String clipId, Span span, Integer sequenceIndex) {
		store.apply(new ClipCommands.ChangeSpan(clipId, span.start(), span.end(), sequenceIndex,
				sourceDurationMs.applyAsLong(clipId)));
	}

	/**
	 * Index in the compacted clip sequence for a span start: the number of other clips whose
	 * compacted span ends at or before the proposed start.
	 */
	private Integer sequenceIndex(String clipId, double proposedStart) {
		List<ClipRegion> siblings = new ArrayList<>();
		for (ClipRegion clip : store.state().clipRegions()) {
			if (!clip.id().equals(clipId)) {
				siblings.add(clip);
			}
		}
		siblings.sort(Comparator.comparingLong(ClipRegion::startMs));
		double cursor = 0;
		int index = 0;
		for (ClipRegion sibling : siblings) {
			double duration = Math.max(0, sibling.endMs() - sibling.startMs());
			if (cursor + duration <= proposedStart + 0.5) {
				index++;
			}
			cursor += duration;
		}
		return index;
	}

	private boolean isClip(String itemId) {
		for (ClipRegion clip : store.state().clipRegions()) {
			if (clip.id().equals(itemId)) {
				return true;
			}
		}
		return false;
	}

	private String rowOf(String itemId) {
		for (ClipRegion clip : store.state().clipRegions()) {
			if (clip.id().equals(itemId)) {
				return TimelineConstants.CLIP_ROW_ID;
			}
		}
		for (var zoom : store.state().zoomRegions()) {
			if (zoom.id().equals(itemId)) {
				return TimelineConstants.ZOOM_ROW_ID;
			}
		}
		for (var audio : store.state().audioRegions()) {
			if (audio.id().equals(itemId)) {
				return TimelineRows.getAudioTrackRowId(audio.trackIndex() == null ? 0
						: audio.trackIndex());
			}
		}
		for (var annotation : store.state().annotationRegions()) {
			if (annotation.id().equals(itemId)) {
				return TimelineRows.getAnnotationTrackRowId(annotation.trackIndex() == null ? 0
						: annotation.trackIndex());
			}
		}
		for (var caption : store.state().autoCaptions()) {
			if (caption.id().equals(itemId)) {
				return TimelineConstants.CAPTION_ROW_ID;
			}
		}
		return "";
	}

	private long fallbackSourceDuration(String clipId) {
		for (ClipRegion clip : store.state().clipRegions()) {
			if (clip.id().equals(clipId)) {
				return clip.sourceMaxMs() != null ? clip.sourceMaxMs()
						: Clips.getClipSourceEndMs(clip);
			}
		}
		return 0;
	}
}