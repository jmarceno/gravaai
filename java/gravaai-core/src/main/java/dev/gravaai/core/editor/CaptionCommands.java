package dev.gravaai.core.editor;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.AutoCaptionSettings;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionEditing;
import dev.gravaai.core.project.CaptionOps;
import dev.gravaai.core.project.CaptionTimeline;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;

/**
 * The {@code useCaptionCommands} operations, one class per command.
 *
 * <p>The hook also seeks playback on select/add; seeking belongs to the media layer (plan §5.9),
 * so the commands only change the store state and selection (recorded narrowing).
 */
public final class CaptionCommands {

	public static final String SECTION_CAPTION = "caption";

	private CaptionCommands() {}

	/** {@code handleSelectCaption}. */
	public static final class Select implements EditorCommand {
		private final String id;

		public Select(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setSelectedCaptionId(id);
			if (id == null) {
				if (SECTION_CAPTION.equals(store.activeEffectSection())) {
					store.setActiveEffectSection(EditorStore.SECTION_SCENE);
				}
				return true;
			}
			store.setActiveEffectSection(SECTION_CAPTION);
			store.clearSelectionsExcept(null, null, null, null, id);
			return true;
		}
	}

	/** {@code handleBeginCaptionEdit}. */
	public static final class BeginEdit implements EditorCommand {
		private final String id;

		public BeginEdit(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setSelectedCaptionId(id);
			return true;
		}
	}

	/** {@code handleCaptionTextEdit}. */
	public static final class TextEdit implements EditorCommand {
		private final String id;
		private final String text;

		public TextEdit(String id, String text) {
			this.id = id;
			this.text = text;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<CaptionCue> captions = store.state().autoCaptions();
			CaptionCue cue = null;
			for (CaptionCue candidate : captions) {
				if (candidate.id().equals(id)) {
					cue = candidate;
					break;
				}
			}
			if (cue == null) {
				return false;
			}

			List<dev.gravaai.core.project.CaptionCueWord> words =
					CaptionEditing.normalizeCaptionWords(cue);
			if (words.isEmpty()) {
				String normalized = CaptionEditing.normalizeCaptionEditText(text);
				List<CaptionCue> next = new ArrayList<>(captions.size());
				for (CaptionCue value : captions) {
					next.add(value.id().equals(id) ? new CaptionCue(value.id(), value.startMs(),
							value.endMs(), normalized, value.words()) : value);
				}
				store.setAutoCaptions(next);
				return true;
			}

			List<CaptionEditing.WordRef> refs = new ArrayList<>(words.size());
			for (int index = 0; index < words.size(); index++) {
				var word = words.get(index);
				refs.add(new CaptionEditing.WordRef(cue.id(), index, word.startMs(), word.endMs(),
						word.text(), Boolean.TRUE.equals(word.leadingSpace())));
			}
			CaptionEditing.EditTarget target = new CaptionEditing.EditTarget(cue.id(),
					cue.startMs(), cue.endMs(), cue.text(), refs);
			store.setAutoCaptions(CaptionEditing.updateCaptionCuesForEditedTarget(captions,
					target, text));
			return true;
		}
	}

	/** {@code handleCaptionRetime}. */
	public static final class Retime implements EditorCommand {
		private final String id;
		private final CaptionOps.RetimeSpan span;

		public Retime(String id, CaptionOps.RetimeSpan span) {
			this.id = id;
			this.span = span;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<CaptionCue> next = CaptionOps.retimeCue(store.state().autoCaptions(), id, span);
			store.setAutoCaptions(next);
			return true;
		}
	}

	/** {@code handleCaptionSplit}. */
	public static final class Split implements EditorCommand {
		private final String id;
		private final double atMs;

		public Split(String id, double atMs) {
			this.id = id;
			this.atMs = atMs;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setAutoCaptions(CaptionOps.splitCue(store.state().autoCaptions(), id, atMs));
			return true;
		}
	}

	/** {@code handleCaptionMerge}. */
	public static final class Merge implements EditorCommand {
		private final String idA;
		private final String idB;

		public Merge(String idA, String idB) {
			this.idA = idA;
			this.idB = idB;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setAutoCaptions(CaptionOps.mergeCues(store.state().autoCaptions(), idA, idB));
			return true;
		}
	}

	/** {@code handleCaptionDelete}. */
	public static final class Delete implements EditorCommand {
		private final String id;

		public Delete(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setAutoCaptions(CaptionOps.deleteCue(store.state().autoCaptions(), id));
			if (id.equals(store.selectedCaptionId())) {
				store.setSelectedCaptionId(null);
			}
			return true;
		}
	}

	/** {@code handleCaptionAdded}: needs a clip under the requested timeline span. */
	public static final class Add implements EditorCommand {
		private final double startMs;
		private final double endMs;

		public Add(double startMs, double endMs) {
			this.startMs = startMs;
			this.endMs = endMs;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<ClipRegion> clips = store.state().clipRegions();
			ClipRegion clip = Clips.findClipAtTimelineTime(startMs, clips);
			if (clip == null) {
				return false;
			}
			CaptionTimeline.SourceSpan source = CaptionTimeline.captionSpanToSource(clip, startMs,
					endMs);
			CaptionCue cue = CaptionOps.createCaptionCue(source.startMs(), source.endMs(), null);
			store.setAutoCaptions(CaptionOps.addCue(store.state().autoCaptions(), cue));
			store.setSelectedCaptionId(cue.id());
			store.setActiveEffectSection(SECTION_CAPTION);
			store.clearSelectionsExcept(null, null, null, null, cue.id());
			return true;
		}
	}

	/** {@code handleClearAutoCaptions}. */
	public static final class ClearAll implements EditorCommand {
		@Override
		public boolean apply(EditorStore store) {
			store.setAutoCaptions(List.of());
			AutoCaptionSettings settings = store.state().autoCaptionSettings();
			store.setAutoCaptionSettings(new AutoCaptionSettings(false,
					settings.timelineQuickAdd(), settings.language(), settings.fontFamily(),
					settings.fontSize(), settings.bottomOffset(), settings.maxWidth(),
					settings.maxRows(), settings.animationStyle(), settings.boxRadius(),
					settings.textColor(), settings.inactiveTextColor(),
					settings.backgroundOpacity()));
			return true;
		}
	}
}
