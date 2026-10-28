package dev.gravaai.ui.editor.timeline;

import dev.gravaai.core.project.ProjectEditorState;

/**
 * Read seam between the timeline view and the editor state (Lead decision for P2.7: the
 * {@code EditorStore} arrives later from core-storage; this port keeps the view unchanged).
 *
 * <p>Phase A fixtures implement this directly with a hand-built {@link ProjectEditorState}; the
 * real adapter will read the live store and push redraws through the view.
 */
public interface TimelineDataSource {

	/** The current editor state (regions, trims, speeds, captions, ...). */
	ProjectEditorState project();

	/** The rendered timeline duration in milliseconds (video duration after trims). */
	long totalDurationMs();

	/** The playhead position in milliseconds. */
	double currentTimeMs();

	/** Audio peaks for the waveform regions. */
	WaveformProvider waveforms();

	/** Cursor telemetry used by the suggest-zooms toolbar action (empty without a recording). */
	default java.util.List<dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils.CursorTelemetryPoint> cursorTelemetry() {
		return java.util.List.of();
	}

	/** Filmstrip thumbnails for the clip row (empty provider keeps the placeholder cells). */
	default FilmstripProvider filmstripProvider() {
		return FilmstripProvider.empty();
	}

	/** Agent highlight spans (shell for {@code AgentTimelineHighlights.tsx}, P3 fills it). */
	default java.util.List<AgentHighlight> agentHighlights() {
		return java.util.List.of();
	}

	/**
	 * Source-audio tracks to render under the clip row (built from the recording's sidecars by the
	 * app; empty when the media layer has no peaks). See {@code TimelineSourceAudioTracks}.
	 */
	default java.util.List<dev.gravaai.ui.editor.timeline.core.TimelineSourceAudioTrack> sourceAudioTracks() {
		return java.util.List.of();
	}
}