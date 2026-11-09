package dev.gravaai.ui.editor.timeline;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.event.Subscription;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.ui.editor.timeline.core.TimelineSourceAudioTrack;

/**
 * {@link TimelineDataSource} over the real {@link EditorStore} (plan §5.11 / P2.7).
 *
 * <p>AppContext/EditorSession owns the store and supplies the media duration and the live playhead
 * (playback engine); the timeline only reads. Subscribe with {@link #onChange(Runnable)} and call
 * {@link TimelineView#refreshData()} on the FX thread to repaint after edits. Store edits and the
 * session's late-arriving media facts (source-audio tracks) both notify the listeners; the
 * subscriber marshals to the FX thread.
 */
public final class StoreTimelineDataSource implements TimelineDataSource {

	private final EditorStore store;
	private final LongSupplier totalDurationMs;
	private final DoubleSupplier playheadMs;
	private final WaveformProvider waveforms;
	private Supplier<List<ZoomSuggestionUtils.CursorTelemetryPoint>> cursorTelemetry = List::of;
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
	private volatile List<TimelineSourceAudioTrack> sourceAudioTracks = List.of();

	/** @param waveforms optional waveform source; {@code null} means "no waveforms yet". */
	public StoreTimelineDataSource(EditorStore store, LongSupplier totalDurationMs,
			DoubleSupplier playheadMs, @Nullable WaveformProvider waveforms) {
		this.store = Objects.requireNonNull(store, "store");
		this.totalDurationMs = totalDurationMs == null ? () -> 0L : totalDurationMs;
		this.playheadMs = playheadMs == null ? () -> 0.0 : playheadMs;
		this.waveforms = waveforms == null ? WaveformProvider.empty() : waveforms;
		this.store.onChange(changed -> changeListeners.forEach(Runnable::run));
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

	/** Subscribes to store edits and late-arriving data; marshal to the FX thread before repaint. */
	public Subscription onChange(Runnable listener) {
		changeListeners.add(listener);
		return () -> changeListeners.remove(listener);
	}

	/**
	 * Replaces the source-audio tracks once the session resolved the recording's sidecars (off the
	 * FX thread); notifies the change listeners so the view refreshes on the FX thread.
	 */
	public void setSourceAudioTracks(List<TimelineSourceAudioTrack> tracks) {
		this.sourceAudioTracks = tracks == null ? List.of() : List.copyOf(tracks);
		changeListeners.forEach(Runnable::run);
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

	@Override
	public List<TimelineSourceAudioTrack> sourceAudioTracks() {
		return sourceAudioTracks;
	}
}
