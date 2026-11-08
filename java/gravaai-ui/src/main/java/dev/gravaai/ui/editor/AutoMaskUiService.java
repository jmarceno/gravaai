package dev.gravaai.ui.editor;

import java.util.function.Consumer;

/**
 * What the inspector's AUTO-MASK section needs from the app, without the UI module depending on
 * {@code gravaai-app}. The app's {@code AutoMaskController} implements it, the same way
 * {@code CaptionModelUiService} hides the caption controller.
 */
public interface AutoMaskUiService {

	/** Whether the PII model is installed; the listener fires when that changes. */
	record State(boolean modelInstalled) {}

	/** One immutable progress snapshot for the section's bar and status line. */
	record Progress(boolean running, int percent, String message, boolean failed) {}

	State state();

	/** The last progress snapshot the section should show. */
	Progress progress();

	/** True while a run is in flight, so the section shows Cancel instead of Detect. */
	boolean detecting();

	AutoCloseable addListener(Consumer<State> listener);

	/** Starts a run; {@code includeFaces}/{@code includeText} are the section's toggles. */
	void detect(boolean includeFaces, boolean includeText);

	void cancel();

	/** Downloads the model without running detection. */
	void downloadModel();

	/** Removes the regions a previous run generated. */
	void removeAutoRegions();

	/** The recording-relative span the last run produced, for the section's summary. */
	int lastRegionCount();
}
