package dev.gravaai.core.editor;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.ClipCameraLayout;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipSequence;
import dev.gravaai.core.project.ClipSpanChanges;
import dev.gravaai.core.project.ClipSplits;
import dev.gravaai.core.project.ClipTransition;
import dev.gravaai.core.project.Clips;

/** The {@code useClipRegionCommands} operations, one class per command. */
public final class ClipCommands {

	private ClipCommands() {}

	/** {@code applySequence}: repack the clips and ripple every connected region. */
	private static void applySequence(EditorStore store, List<ClipRegion> edited) {
		List<ClipRegion> before = store.state().clipRegions();
		List<ClipRegion> next = ClipSequence.packClipSequence(edited);
		store.setClipRegions(next);
		store.setZoomRegions(ClipSequence.rippleRegions(store.state().zoomRegions(), before, next,
				ClipSequence.ZOOM_SPAN));
		store.setAnnotationRegions(ClipSequence.rippleRegions(store.state().annotationRegions(),
				before, next, ClipSequence.ANNOTATION_SPAN));
		store.setAudioRegions(ClipSequence.rippleRegionAnchors(store.state().audioRegions(), before,
				next, ClipSequence.AUDIO_SPAN));
	}

	private static List<String> ids(List<ClipRegion> clips) {
		List<String> ids = new ArrayList<>(clips.size());
		for (ClipRegion clip : clips) {
			ids.add(clip.id());
		}
		return ids;
	}

	private static ClipRegion replace(ClipRegion clip, @Nullable Boolean muted, @Nullable ClipCameraLayout cameraLayout,
			@Nullable ClipTransition transitionOut, @Nullable Boolean showSourceAudio) {
		return new ClipRegion(clip.id(), clip.startMs(), clip.endMs(), clip.sourceStartMs(),
				clip.sourceMinMs(), clip.sourceMaxMs(), clip.speed(),
				muted != null ? muted : clip.muted(),
				showSourceAudio != null ? showSourceAudio : clip.showSourceAudio(),
				cameraLayout != null ? cameraLayout : clip.cameraLayout(),
				transitionOut);
	}

	/** {@code handleSelectClip}. */
	public static final class Select implements EditorCommand {
		private final String id;

		public Select(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setSelectedClipId(id);
			if (id != null) {
				store.setActiveEffectSection(EditorStore.SECTION_CLIP);
				store.clearSelectionsExcept(id, null, null, null, null);
			} else if (EditorStore.SECTION_CLIP.equals(store.activeEffectSection())) {
				store.setActiveEffectSection(EditorStore.SECTION_SCENE);
			}
			return true;
		}
	}

	/** {@code handleClipSplit}. */
	public static final class Split implements EditorCommand {
		private final double splitMs;

		public Split(double splitMs) {
			this.splitMs = splitMs;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<ClipRegion> clips = store.state().clipRegions();
			ClipSplits.Plan plan = ClipSplits.planClipSplit(clips, splitMs,
					() -> store.nextId("clip", ids(clips)));
			if (plan == null) {
				return false;
			}
			List<ClipRegion> next = new ArrayList<>(clips.size() + 1);
			for (ClipRegion clip : clips) {
				if (clip.id().equals(plan.targetId())) {
					next.add(plan.left());
					next.add(plan.right());
				} else {
					next.add(clip);
				}
			}
			store.setClipRegions(next);
			new Select(plan.left().id()).apply(store);
			return true;
		}
	}

	/** {@code handleClipSpanChange} (move/resize, or reorder by sequence index). */
	public static final class ChangeSpan implements EditorCommand {
		private final String id;
		private final double start;
		private final double end;
		/** Null for a plain move/resize; set to reorder within the compacted clip sequence. */
		private final @Nullable Integer sequenceIndex;
		private final long sourceDurationMs;

		public ChangeSpan(String id, double start, double end, @Nullable Integer sequenceIndex,
				long sourceDurationMs) {
			this.id = id;
			this.start = start;
			this.end = end;
			this.sequenceIndex = sequenceIndex;
			this.sourceDurationMs = sourceDurationMs;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<ClipRegion> clips = store.state().clipRegions();
			boolean exists = clips.stream().anyMatch(clip -> clip.id().equals(id));
			if (!exists) {
				return false;
			}
			if (sequenceIndex != null) {
				applySequence(store, ClipSequence.reorderClipSequence(clips, id, sequenceIndex));
				return true;
			}
			List<ClipRegion> next = new ArrayList<>(clips.size());
			for (ClipRegion clip : clips) {
				next.add(clip.id().equals(id)
						? ClipSpanChanges.changeClipSpan(clip, Math.round(start), Math.round(end),
								sourceDurationMs)
						: clip);
			}
			applySequence(store, next);
			return true;
		}
	}

	/** {@code handleClipSpeedChange}: keeps the source window, only the timeline length changes. */
	public static final class ChangeSpeed implements EditorCommand {
		private final double speed;

		public ChangeSpeed(double speed) {
			this.speed = speed;
		}

		@Override
		public boolean apply(EditorStore store) {
			String selectedClipId = store.selectedClipId();
			if (selectedClipId == null || !Double.isFinite(speed) || speed <= 0) {
				return false;
			}
			List<ClipRegion> next = new ArrayList<>();
			for (ClipRegion clip : store.state().clipRegions()) {
				if (!clip.id().equals(selectedClipId)) {
					next.add(clip);
					continue;
				}
				double oldSpeed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
				next.add(new ClipRegion(clip.id(), clip.startMs(),
						clip.startMs() + Math.max(1,
								Math.round((clip.endMs() - clip.startMs()) * oldSpeed / speed)),
						Clips.getClipSourceStartMs(clip), clip.sourceMinMs(), clip.sourceMaxMs(), speed,
						clip.muted(), clip.showSourceAudio(), clip.cameraLayout(),
						clip.transitionOut()));
			}
			applySequence(store, next);
			return true;
		}
	}

	/** {@code handleClipMutedChange}. */
	public static final class SetMuted implements EditorCommand {
		private final boolean muted;

		public SetMuted(boolean muted) {
			this.muted = muted;
		}

		@Override
		public boolean apply(EditorStore store) {
			String selectedClipId = store.selectedClipId();
			if (selectedClipId == null) {
				return false;
			}
			List<ClipRegion> next = new ArrayList<>();
			for (ClipRegion clip : store.state().clipRegions()) {
				next.add(clip.id().equals(selectedClipId)
						? replace(clip, muted, null, clip.transitionOut(), null) : clip);
			}
			store.setClipRegions(next);
			return true;
		}
	}

	/** {@code handleClipCameraLayoutChange}. */
	public static final class SetCameraLayout implements EditorCommand {
		private final ClipCameraLayout layout;

		public SetCameraLayout(ClipCameraLayout layout) {
			this.layout = layout;
		}

		@Override
		public boolean apply(EditorStore store) {
			String selectedClipId = store.selectedClipId();
			if (selectedClipId == null) {
				return false;
			}
			ClipCameraLayout value = layout == ClipCameraLayout.DEFAULT ? null : layout;
			List<ClipRegion> next = new ArrayList<>();
			for (ClipRegion clip : store.state().clipRegions()) {
				next.add(clip.id().equals(selectedClipId)
						? replace(clip, null, value, clip.transitionOut(), null) : clip);
			}
			store.setClipRegions(next);
			return true;
		}
	}

	/** {@code handleClipTransitionChange}; a null transition removes the outgoing fade. */
	public static final class SetTransition implements EditorCommand {
		private final ClipTransition transition;

		public SetTransition(ClipTransition transition) {
			this.transition = transition;
		}

		@Override
		public boolean apply(EditorStore store) {
			String selectedClipId = store.selectedClipId();
			if (selectedClipId == null) {
				return false;
			}
			List<ClipRegion> next = new ArrayList<>();
			for (ClipRegion clip : store.state().clipRegions()) {
				next.add(clip.id().equals(selectedClipId)
						? new ClipRegion(clip.id(), clip.startMs(), clip.endMs(),
								clip.sourceStartMs(), clip.sourceMinMs(), clip.sourceMaxMs(),
								clip.speed(), clip.muted(), clip.showSourceAudio(),
								clip.cameraLayout(), transition)
						: clip);
			}
			store.setClipRegions(next);
			return true;
		}
	}

	/** {@code handleClipShowSourceAudioChange}. */
	public static final class SetShowSourceAudio implements EditorCommand {
		private final boolean showSourceAudio;

		public SetShowSourceAudio(boolean showSourceAudio) {
			this.showSourceAudio = showSourceAudio;
		}

		@Override
		public boolean apply(EditorStore store) {
			String selectedClipId = store.selectedClipId();
			if (selectedClipId == null) {
				return false;
			}
			List<ClipRegion> next = new ArrayList<>();
			for (ClipRegion clip : store.state().clipRegions()) {
				next.add(clip.id().equals(selectedClipId)
						? replace(clip, null, null, clip.transitionOut(), showSourceAudio) : clip);
			}
			store.setClipRegions(next);
			return true;
		}
	}

	/** {@code handleClipDelete}. */
	public static final class Delete implements EditorCommand {
		private final String id;

		public Delete(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<ClipRegion> next = new ArrayList<>();
			for (ClipRegion clip : store.state().clipRegions()) {
				if (!clip.id().equals(id)) {
					next.add(clip);
				}
			}
			applySequence(store, next);
			if (id.equals(store.selectedClipId())) {
				store.setSelectedClipId(null);
			}
			return true;
		}
	}

	/** {@code handleCutSourceRanges}: false when the cut would remove everything playable. */
	public static final class CutSourceRanges implements EditorCommand {
		private final List<TranscriptCut.SourceRange> ranges;

		public CutSourceRanges(List<TranscriptCut.SourceRange> ranges) {
			this.ranges = ranges;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<ClipRegion> clips = store.state().clipRegions();
			List<ClipRegion> next = TranscriptCut.removeSourceRangesFromClips(clips, ranges,
					() -> store.nextId("clip", ids(clips)));
			if (next == clips) {
				return false;
			}
			applySequence(store, next);
			store.setSelectedClipId(null);
			return true;
		}
	}
}
