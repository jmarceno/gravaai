package dev.gravaai.core.editor;

import dev.gravaai.core.project.CropRegion;

/** The screen-recording crop operations, one class per command ({@code CropControl}). */
public final class CropCommands {

	private CropCommands() {}

	/**
	 * {@code handleCropChange}: replaces the crop as one undoable step (a drag gesture commits
	 * once on release; the preview crop overlay and the crop presets both land here). Reports no
	 * change when the normaliser kept the same region, so a no-op drag records no history step.
	 */
	public static final class Set implements EditorCommand {

		private final CropRegion crop;

		public Set(CropRegion crop) {
			this.crop = crop;
		}

		@Override
		public boolean apply(EditorStore store) {
			CropRegion before = store.state().cropRegion();
			store.setCropRegion(crop);
			return !store.state().cropRegion().equals(before);
		}
	}
}
