package dev.gravaai.ui.editor;

import java.util.Optional;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * The editor's window into the optional deep-zoom upscaler (export only): whether exports should
 * upscale, whether the model is installed, which execution provider exports will run on, and the
 * install actions.
 *
 * <p>The inspector owns no state: the app controller publishes {@link State} snapshots, so the
 * panel and the export path can never disagree about whether the pass is on.</p>
 */
public interface SuperResolutionUiService {

	/**
	 * One snapshot of the feature.
	 *
	 * @param enabled        whether the user asked exports to upscale deep zooms
	 * @param modelInstalled whether this machine has the model
	 * @param percent        install progress, 0-100
	 * @param message        what the install is doing, or the failure text; null when idle
	 * @param failed         whether the last install attempt failed
	 * @param provider       the execution provider exports will use ("WebGPU · AMD Radeon RX
	 *                       560", "CPU"), or empty while it is being probed
	 * @param providerDetail why the CPU is in use when no GPU device was found; empty otherwise
	 */
	record State(boolean enabled, boolean modelInstalled, int percent, @Nullable String message,
			boolean failed, Optional<String> provider, Optional<String> providerDetail) {

		/** A snapshot before the provider has been probed. */
		public State(boolean enabled, boolean modelInstalled, int percent, @Nullable String message,
				boolean failed) {
			this(enabled, modelInstalled, percent, message, failed, Optional.empty(), Optional.empty());
		}

		/** Whether an export started now will upscale (the toggle only means this with a model). */
		public boolean active() {
			return enabled && modelInstalled;
		}

		/** Whether the probed provider is a GPU one. */
		public boolean hardwareAccelerated() {
			return provider.filter(label -> label.startsWith("WebGPU")).isPresent();
		}

		/** The same snapshot with the probed provider; everything else is unchanged. */
		public State withProvider(Optional<String> provider, Optional<String> providerDetail) {
			return new State(enabled, modelInstalled, percent, message, failed, provider,
					providerDetail);
		}
	}

	State state();

	void setEnabled(boolean enabled);

	void downloadModel();

	void removeModel();

	void addListener(Consumer<State> listener);

	void removeListener(Consumer<State> listener);
}
