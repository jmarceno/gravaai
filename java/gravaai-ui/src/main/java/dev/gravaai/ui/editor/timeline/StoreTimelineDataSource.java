package dev.gravaai.ui.editor.timeline;

import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.event.Subscription;
import dev.gravaai.core.project.ProjectEditorState;

/**
 * {@link TimelineDataSource} over the real {@link EditorStore} (plan §5.11 / P2.7).
 *
 * <p>AppContext/EditorSession owns the store and supplies the media duration and the live playhead
 * (playback engine); the timeline only reads. Subscribe with {@link #onChange(Runnable)} and call
 * {@link TimelineView#refreshData()} on the FX thread to repaint after edits.
 */
public final class StoreTimelineDataSource implements TimelineDataSource {

	private final EditorStore store;
	private final LongSupplier totalDurationMs;
	private final DoubleSupplier playheadMs;
	private final WaveformProvider waveforms;
	private Supplier<List<ZoomSuggestionUtils.CursorTelemetryPoint>> cursorTelemetry = List::of;

	public StoreTimelineDataSource(EditorStore store, LongSupplier totalDurationMs,
			DoubleSupplier playheadMs, WaveformProvider waveforms) {
		this.store = Objects.requireNonNull(store, "store");
		this.totalDurationMs = totalDurationMs == null ? () -> 0L : totalDurationMs;
		this.playheadMs = playheadMs == null ? () -> 0.0 : playheadMs;
		this.waveforms = waveforms == null ? WaveformProvider.empty() : waveforms;
	}

	public EditorStore store() {
		return store;
	}

	/** Supplies the cursor telemetry used by the suggest-zooms toolbar action. */
	public StoreTimelineDataSource cursorTelemetry(
			Supplier<List<ZoomSuggestionUtils.CursorTelemetryPoint>> telemetry) {
		this.cursorTelemetry = telemetry == null ? List::of : telemetry;
		return this;
	}

	@Override
	public List<ZoomSuggestionUtils.CursorTelemetryPoint> cursorTelemetry() {
		return cursorTelemetry.get();
	}

	/** Subscribes to store edits; adapt to the FX thread before calling refreshData(). */
	public Subscription onChange(Runnable listener) {
		return store.onChange(changed -> listener.run());
	}

	@Override
	public ProjectEditorState project() {
		return store.state();
	}

	@Override
	public long totalDurationMs() {
		return Math.max(0, totalDurationMs.getAsLong());
	}

	@Override
	public double currentTimeMs() {
		return playheadMs.getAsDouble();
	}

	@Override
	public WaveformProvider waveforms() {
		return waveforms;
	}
}