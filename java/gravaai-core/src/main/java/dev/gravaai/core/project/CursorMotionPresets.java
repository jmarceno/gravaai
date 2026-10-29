package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Motion presets shared by the zoom and cursor settings (port of cursorMotionPresets.ts). */
public final class CursorMotionPresets {

	public record CursorMotionPreset(
			String id,
			String label,
			double zoomSmoothness,
			double zoomInDurationMs,
			double zoomOutDurationMs,
			double cursorSize,
			double cursorSmoothing,
			double cursorSpringStiffnessMultiplier,
			double cursorSpringDampingMultiplier,
			double cursorSpringMassMultiplier,
			double cursorClickBounce,
			double cursorClickBounceDuration) {}

	public record SelectionInput(
			double zoomInDurationMs,
			double zoomOutDurationMs,
			double cursorSize,
			double cursorSmoothing,
			double cursorSpringStiffnessMultiplier,
			double cursorSpringDampingMultiplier,
			double cursorSpringMassMultiplier,
			double cursorClickBounce,
			double cursorClickBounceDuration) {}

	private static final double SHARED_CURSOR_SIZE = 2.5;
	private static final double SHARED_CURSOR_SMOOTHING = 0.67;
	private static final double SHARED_SPRING_MASS = 1.29;
	private static final double SHARED_CLICK_BOUNCE = 2;
	private static final double SHARED_CLICK_BOUNCE_DURATION = 350;

	public static final Map<String, CursorMotionPreset> PRESETS = createPresets();

	private CursorMotionPresets() {}

	private static Map<String, CursorMotionPreset> createPresets() {
		Map<String, CursorMotionPreset> presets = new LinkedHashMap<>();
		presets.put("focused", new CursorMotionPreset("focused", "Focused",
				EditorDefaults.DEFAULT_ZOOM_SMOOTHNESS, 200, 200, SHARED_CURSOR_SIZE,
				SHARED_CURSOR_SMOOTHING, 1.35, 0.79, SHARED_SPRING_MASS, SHARED_CLICK_BOUNCE,
				SHARED_CLICK_BOUNCE_DURATION));
		presets.put("smooth", new CursorMotionPreset("smooth", "Smooth",
				EditorDefaults.DEFAULT_ZOOM_SMOOTHNESS,
				EditorDefaults.DEFAULT_ZOOM_IN_DURATION_MS,
				EditorDefaults.DEFAULT_ZOOM_OUT_DURATION_MS, SHARED_CURSOR_SIZE,
				SHARED_CURSOR_SMOOTHING, 0.92, 1.36, SHARED_SPRING_MASS, SHARED_CLICK_BOUNCE,
				SHARED_CLICK_BOUNCE_DURATION));
		return Map.copyOf(presets);
	}

	/** {@code getMatchingCursorMotionPresetId}: null when no preset matches exactly. */
	public static @Nullable String getMatchingCursorMotionPresetId(SelectionInput values) {
		for (Map.Entry<String, CursorMotionPreset> entry : PRESETS.entrySet()) {
			CursorMotionPreset preset = entry.getValue();
			if (preset.zoomInDurationMs() == values.zoomInDurationMs()
					&& preset.zoomOutDurationMs() == values.zoomOutDurationMs()
					&& preset.cursorSize() == values.cursorSize()
					&& preset.cursorSmoothing() == values.cursorSmoothing()
					&& preset.cursorSpringStiffnessMultiplier() == values.cursorSpringStiffnessMultiplier()
					&& preset.cursorSpringDampingMultiplier() == values.cursorSpringDampingMultiplier()
					&& preset.cursorSpringMassMultiplier() == values.cursorSpringMassMultiplier()
					&& preset.cursorClickBounce() == values.cursorClickBounce()
					&& preset.cursorClickBounceDuration() == values.cursorClickBounceDuration()) {
				return entry.getKey();
			}
		}
		return null;
	}

	/** {@code resolveCursorMotionPresetId}: falls back to "focused". */
	public static String resolveCursorMotionPresetId(SelectionInput values) {
		String id = getMatchingCursorMotionPresetId(values);
		return id != null ? id : "focused";
	}

	public static @Nullable CursorMotionPreset preset(String id) {
		return PRESETS.get(id);
	}
}
