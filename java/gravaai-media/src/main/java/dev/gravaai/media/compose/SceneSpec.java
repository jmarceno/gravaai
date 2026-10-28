package dev.gravaai.media.compose;

import java.util.List;

import dev.gravaai.core.project.ClipCameraLayout;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;

/**
 * Per-clip camera layout state for one frame, 1:1 port of
 * {@code src/components/video-editor/sceneLayout.ts}.
 *
 * <p>Clips can hide the camera, show the screen only, or show the camera full-frame
 * ({@code camera-only}). Layout changes blend over 300 ms with a smoothstep. The compositor uses
 * this to fade the webcam ({@link #cameraOpacity}), lerp its box to full frame
 * ({@link #cameraFill}) and fade the screen video ({@link #screenOpacity}).</p>
 */
public record SceneSpec(double cameraOpacity, double cameraFill, double screenOpacity) {

	/** Normal overlay: webcam box visible, screen visible. */
	public static final SceneSpec FULL = new SceneSpec(1, 0, 1);

	public static final long SCENE_TRANSITION_MS = 300;

	public record Span(long startMs, long endMs, ClipCameraLayout layout) {
	}

	private static final SceneSpec HIDDEN_CAMERA = new SceneSpec(0, 0, 1);
	private static final SceneSpec CAMERA_ONLY = new SceneSpec(1, 1, 0);

	public SceneSpec {
		if (!Double.isFinite(cameraOpacity) || !Double.isFinite(cameraFill)
				|| !Double.isFinite(screenOpacity)) {
			throw new IllegalArgumentException("scene values must be finite");
		}
	}

	/** {@code buildSceneLayoutSpans}. */
	public static List<Span> spans(List<ClipRegion> clips) {
		List<ClipRegion> sorted = Clips.sortClipRegions(clips);
		List<Span> spans = new java.util.ArrayList<>(sorted.size());
		for (ClipRegion clip : sorted) {
			ClipCameraLayout layout = clip.cameraLayout() == null
					? ClipCameraLayout.DEFAULT
					: clip.cameraLayout();
			spans.add(new Span(clip.startMs(), clip.endMs(), layout));
		}
		return List.copyOf(spans);
	}

	private static SceneSpec stateOf(ClipCameraLayout layout) {
		if (layout == null || layout == ClipCameraLayout.DEFAULT) {
			return FULL;
		}
		return layout == ClipCameraLayout.HIDE_CAMERA ? HIDDEN_CAMERA : CAMERA_ONLY;
	}

	private static double smoothstep(double value) {
		double t = Math.min(1, Math.max(0, value));
		return t * t * (3 - 2 * t);
	}

	private static double lerp(double from, double to, double t) {
		return from + (to - from) * t;
	}

	private static SceneSpec blend(SceneSpec from, SceneSpec to, double t) {
		return new SceneSpec(lerp(from.cameraOpacity(), to.cameraOpacity(), t),
				lerp(from.cameraFill(), to.cameraFill(), t),
				lerp(from.screenOpacity(), to.screenOpacity(), t));
	}

	/** {@code resolveSceneLayout}: state at a timeline time with 300 ms smooth transitions. */
	public static SceneSpec resolve(List<Span> spans, double timeMs) {
		if (spans == null || spans.isEmpty()) {
			return FULL;
		}
		int index = -1;
		for (int i = 0; i < spans.size(); i++) {
			Span span = spans.get(i);
			if (timeMs >= span.startMs() && timeMs < span.endMs()) {
				index = i;
				break;
			}
		}
		if (index < 0) {
			index = timeMs < spans.get(0).startMs() ? 0 : spans.size() - 1;
			for (int i = 0; i < spans.size(); i++) {
				if (spans.get(i).startMs() > timeMs) {
					break;
				}
				index = i;
			}
		}
		Span span = spans.get(index);
		SceneSpec current = stateOf(span.layout());
		double half = SCENE_TRANSITION_MS / 2.0;
		Span previous = index > 0 ? spans.get(index - 1) : null;
		if (previous != null && previous.layout() != span.layout()
				&& timeMs < span.startMs() + half) {
			double t = smoothstep((timeMs - (span.startMs() - half)) / SCENE_TRANSITION_MS);
			return blend(stateOf(previous.layout()), current, t);
		}
		Span next = index + 1 < spans.size() ? spans.get(index + 1) : null;
		if (next != null && next.layout() != span.layout()
				&& timeMs >= next.startMs() - half) {
			double t = smoothstep((timeMs - (next.startMs() - half)) / SCENE_TRANSITION_MS);
			return blend(current, stateOf(next.layout()), t);
		}
		return current;
	}
}
