package dev.gravaai.ui.editor.transcript;

import java.util.List;
import java.util.Objects;

import dev.gravaai.core.editor.ClipCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.TranscriptCut;
import dev.gravaai.core.project.ClipRegion;

/** {@link TranscriptCutController} over the real {@link EditorStore} (undoable source cuts). */
public final class StoreTranscriptCutController implements TranscriptCutController {

	private final EditorStore store;

	public StoreTranscriptCutController(EditorStore store) {
		this.store = Objects.requireNonNull(store, "store");
	}

	@Override
	public boolean cut(List<TranscriptCut.SourceRange> ranges) {
		return store.apply(new ClipCommands.CutSourceRanges(ranges));
	}

	@Override
	public boolean undo() {
		return store.undo();
	}

	@Override
	public List<ClipRegion> clips() {
		return store.state().clipRegions();
	}

	public EditorStore store() {
		return store;
	}
}
