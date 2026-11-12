package dev.gravaai.services.gaze;

import java.nio.file.Path;
import java.util.Optional;

import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.media.compose.ProjectEditorInputs;
import dev.gravaai.media.compose.WebcamFrameFilter;
import dev.gravaai.media.gaze.EyeContactFilter;
import dev.gravaai.media.gaze.GazeTrack;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;

/**
 * Builds the eye-contact {@link WebcamFrameFilter} for a render of a project, from the shared
 * {@link GazeCache}. Exports and agent renders use {@link #forRender}: the analysis is waited for
 * (on the render thread, which is never the UI thread) the first time a frame needs it.
 */
public final class EyeContactFilters {

	private EyeContactFilters() {}

	/**
	 * The filter for a render of {@code editor}, or {@link WebcamFrameFilter#NONE} when no part
	 * of the timeline asks for eye contact, there is no webcam, or the build lacks the models.
	 */
	public static WebcamFrameFilter forRender(ProjectEditorState editor, Optional<Path> webcam) {
		GazeCache cache = GazeCache.shared();
		if (webcam.isEmpty() || !ProjectEditorInputs.eyeContactWanted(editor)
				|| !cache.isAvailable()) {
			return WebcamFrameFilter.NONE;
		}
		Path recording = webcam.get();
		return new EyeContactFilter(new java.util.function.Supplier<Optional<GazeTrack>>() {
			private Optional<Optional<GazeTrack>> resolved = Optional.empty();

			@Override
			public Optional<GazeTrack> get() {
				if (resolved.isEmpty()) {
					resolved = Optional.of(cache.ensureBlocking(recording));
				}
				return resolved.get();
			}
		}, seconds -> ProjectEditorInputs.eyeContactAngleAt(editor, seconds * 1000),
				OnnxWebGpuPlugin::probe);
	}
}
