package dev.gravaai.ui.editor;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * The editor's window into the optional deep-zoom upscaler (export only): whether exports should
 * upscale, whether the model is installed, and the install actions.
 *
 * <p>The inspector owns no state: the app controller publishes {@link State} snapshots, so the
 * panel and the export path can never disagree about whether the pass is on.</p>
 */
public interface SuperResolutionUiService {

	/**
	 * One snapshot of the feature.
	 *
	 * @param enabled       whether the user asked exports to upscale deep zooms
	 * @param modelInstalled whether this machine has the model
	 * @param percent        install progress, 0-100
	 * @param message       what the install is doing, or the failure text; null when idle
	 * @param failed        whether the last install attempt failed
	 */
	record State(boolean enabled, boolean modelInstalled, int percent, @Nullable String message,
			boolean failed) {

		/** Whether an export started now will upscale (the toggle only means this with a model). */
		public boolean active() {
			return enabled && modelInstalled;
		}
	}

	State state();

	void setEnabled(boolean enabled);

	void downloadModel();

	void removeModel();

	void addListener(Consumer<State> listener);

	void removeListener(Consumer<State> listener);
}
