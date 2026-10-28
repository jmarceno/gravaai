package dev.gravaai.ui.editor.export;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.gravaai.core.editor.ExportPreferences;

/**
 * Mutable export settings of the export popover (port of {@code useExportSettings.ts}, plan
 * §5.10/§5.12): every change replaces the snapshot, persists it through the callback (the same JSON
 * names the TS build writes) and notifies the menu view.
 */
public final class ExportSettingsState {

	private ExportPreferences preferences;
	private final Consumer<ExportPreferences> onPersist;
	private final List<Runnable> listeners = new ArrayList<>();
	private boolean notifying;

	public ExportSettingsState(ExportPreferences initial, Consumer<ExportPreferences> onPersist) {
		this.preferences = initial == null ? ExportPreferences.defaults() : initial;
		this.onPersist = onPersist == null ? preferences -> { } : onPersist;
	}

	/** Subscribes a re-render callback (fired after every settings change). */
	public void onChange(Runnable listener) {
		listeners.add(listener);
	}

	private void changed() {
		onPersist.accept(preferences);
		if (notifying) {
			return;
		}
		notifying = true;
		try {
			for (Runnable listener : List.copyOf(listeners)) {
				listener.run();
			}
		} finally {
			notifying = false;
		}
	}

	public ExportPreferences preferences() {
		return preferences;
	}

	public ExportPreferences.ExportFormat exportFormat() {
		return preferences.exportFormat();
	}

	public void setExportFormat(ExportPreferences.ExportFormat format) {
		if (format != null && format != preferences.exportFormat()) {
			preferences = with(format);
			changed();
		}
	}

	public ExportPreferences.ExportQuality exportQuality() {
		return preferences.exportQuality();
	}

	public void setExportQuality(ExportPreferences.ExportQuality quality) {
		if (quality != null && quality != preferences.exportQuality()) {
			preferences = with(quality);
			changed();
		}
	}

	public ExportPreferences.ExportEncodingMode exportEncodingMode() {
		return preferences.exportEncodingMode();
	}

	public void setExportEncodingMode(ExportPreferences.ExportEncodingMode mode) {
		if (mode != null && mode != preferences.exportEncodingMode()) {
			preferences = with(mode);
			changed();
		}
	}

	public int mp4FrameRate() {
		return preferences.mp4FrameRate();
	}

	public void setMp4FrameRate(int frameRate) {
		if (frameRate != preferences.mp4FrameRate()) {
			preferences = with(frameRate);
			changed();
		}
	}

	public int gifFrameRate() {
		return preferences.gifFrameRate();
	}

	public void setGifFrameRate(int frameRate) {
		if (frameRate != preferences.gifFrameRate()) {
			preferences = withGif(frameRate);
			changed();
		}
	}

	public boolean gifLoop() {
		return preferences.gifLoop();
	}

	public void setGifLoop(boolean loop) {
		if (loop != preferences.gifLoop()) {
			preferences = withGifLoop(loop);
			changed();
		}
	}

	public ExportPreferences.GifSizePreset gifSizePreset() {
		return preferences.gifSizePreset();
	}

	public void setGifSizePreset(ExportPreferences.GifSizePreset preset) {
		if (preset != null && preset != preferences.gifSizePreset()) {
			preferences = with(preset);
			changed();
		}
	}

	public boolean includeCaptionSidecar() {
		return preferences.includeCaptionSidecar();
	}

	public void setIncludeCaptionSidecar(boolean include) {
		if (include != preferences.includeCaptionSidecar()) {
			preferences = withIncludeCaptionSidecar(include);
			changed();
		}
	}

	private ExportPreferences with(ExportPreferences.ExportFormat format) {
		return new ExportPreferences(preferences.exportEncodingMode(),
				preferences.exportBackendPreference(), preferences.exportPipelineModel(),
				preferences.exportQuality(), preferences.mp4FrameRate(), format,
				preferences.gifFrameRate(), preferences.gifLoop(), preferences.gifSizePreset(),
				preferences.publishDestination(), preferences.includeCaptionSidecar());
	}

	private ExportPreferences with(ExportPreferences.ExportQuality quality) {
		return new ExportPreferences(preferences.exportEncodingMode(),
				preferences.exportBackendPreference(), preferences.exportPipelineModel(), quality,
				preferences.mp4FrameRate(), preferences.exportFormat(),
				preferences.gifFrameRate(), preferences.gifLoop(), preferences.gifSizePreset(),
				preferences.publishDestination(), preferences.includeCaptionSidecar());
	}

	private ExportPreferences with(ExportPreferences.ExportEncodingMode mode) {
		return new ExportPreferences(mode, preferences.exportBackendPreference(),
				preferences.exportPipelineModel(), preferences.exportQuality(),
				preferences.mp4FrameRate(), preferences.exportFormat(),
				preferences.gifFrameRate(), preferences.gifLoop(), preferences.gifSizePreset(),
				preferences.publishDestination(), preferences.includeCaptionSidecar());
	}

	private ExportPreferences with(int mp4FrameRate) {
		return new ExportPreferences(preferences.exportEncodingMode(),
				preferences.exportBackendPreference(), preferences.exportPipelineModel(),
				preferences.exportQuality(), mp4FrameRate, preferences.exportFormat(),
				preferences.gifFrameRate(), preferences.gifLoop(), preferences.gifSizePreset(),
				preferences.publishDestination(), preferences.includeCaptionSidecar());
	}

	private ExportPreferences withGif(int gifFrameRate) {
		return new ExportPreferences(preferences.exportEncodingMode(),
				preferences.exportBackendPreference(), preferences.exportPipelineModel(),
				preferences.exportQuality(), preferences.mp4FrameRate(), preferences.exportFormat(),
				gifFrameRate, preferences.gifLoop(), preferences.gifSizePreset(),
				preferences.publishDestination(), preferences.includeCaptionSidecar());
	}

	private ExportPreferences withGifLoop(boolean gifLoop) {
		return new ExportPreferences(preferences.exportEncodingMode(),
				preferences.exportBackendPreference(), preferences.exportPipelineModel(),
				preferences.exportQuality(), preferences.mp4FrameRate(), preferences.exportFormat(),
				preferences.gifFrameRate(), gifLoop, preferences.gifSizePreset(),
				preferences.publishDestination(), preferences.includeCaptionSidecar());
	}

	private ExportPreferences with(ExportPreferences.GifSizePreset preset) {
		return new ExportPreferences(preferences.exportEncodingMode(),
				preferences.exportBackendPreference(), preferences.exportPipelineModel(),
				preferences.exportQuality(), preferences.mp4FrameRate(), preferences.exportFormat(),
				preferences.gifFrameRate(), preferences.gifLoop(), preset,
				preferences.publishDestination(), preferences.includeCaptionSidecar());
	}

	private ExportPreferences withIncludeCaptionSidecar(boolean includeCaptionSidecar) {
		return new ExportPreferences(preferences.exportEncodingMode(),
				preferences.exportBackendPreference(), preferences.exportPipelineModel(),
				preferences.exportQuality(), preferences.mp4FrameRate(), preferences.exportFormat(),
				preferences.gifFrameRate(), preferences.gifLoop(), preferences.gifSizePreset(),
				preferences.publishDestination(), includeCaptionSidecar);
	}
}
