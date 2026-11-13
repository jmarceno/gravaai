package dev.gravaai.ui.editor.timeline;

import org.jspecify.annotations.Nullable;

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

	/**
	 * The project's smart-framing dodge schedule, for the move markers on the Camera layout
	 * track (null when smart framing is off or the adapter has no telemetry for it).
	 */
	default dev.gravaai.media.compose.SmartFraming.@Nullable Plan smartFramingPlan() {
		return null;
	}

	/**
	 * What the cleanup badge of a source-audio lane adds while its denoised copy is not ready:
	 * a percentage while it renders, a warning mark when it failed, the empty string otherwise
	 * (ready, or no noise reduction on the lane).
	 */
	default String cleanupRenderStatus(String sourceTrackId) {
		return "";
	}

	/**
	 * The eye-contact strip of the Camera layout track; empty when no part of the timeline asks
	 * for eye contact.
	 */
	default java.util.Optional<EyeContactStrip> eyeContactStrip() {
		return java.util.Optional.empty();
	}

	/** Filmstrip thumbnails for the clip row (empty provider keeps the placeholder cells). */
	default @Nullable FilmstripProvider filmstripProvider() {
		return FilmstripProvider.empty();
	}

	/** Where the presenter drew on screen while recording, on the edited timeline. */
	default java.util.List<InkMark> inkMarks() {
		return java.util.List.of();
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