package dev.gravaai.core.editor;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.project.GradeRegion;
import dev.gravaai.core.project.GradeTarget;

/**
 * The Color-track operations (one class per command, like {@link ZoomCommands}): selecting,
 * adding, moving/resizing, re-grading and deleting {@link GradeRegion}s. Grades stay
 * single-selection (like keystrokes and camera layouts); the multi-selection batch path does not
 * include them.
 */
public final class GradeCommands {

	private GradeCommands() {}

	private static List<String> ids(List<GradeRegion> regions) {
		List<String> ids = new ArrayList<>(regions.size());
		for (GradeRegion region : regions) {
			ids.add(region.id());
		}
		return ids;
	}

	/** Selects one grade (or clears the grade selection with {@code id = null}). */
	public static final class Select implements EditorCommand {
		private final @Nullable String id;

		public Select(@Nullable String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setSelectedGradeId(id);
			if (id != null) {
				store.clearSelectionsExcept(null, null, null, null, null, null, id);
			}
			return true;
		}
	}

	/** Adds a neutral grade over the span and selects it (the add-at-playhead gestures). */
	public static final class Add implements EditorCommand {
		private final double startMs;
		private final double endMs;

		public Add(double startMs, double endMs) {
			this.startMs = startMs;
			this.endMs = endMs;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<GradeRegion> regions = store.state().gradeRegions();
			String id = store.nextId("grade", ids(regions));
			GradeRegion region = GradeRegion.neutral(id, Math.round(startMs), Math.round(endMs));
			List<GradeRegion> next = new ArrayList<>(regions);
			next.add(region);
			store.setGradeRegions(next);
			store.setSelectedGradeId(id);
			store.clearSelectionsExcept(null, null, null, null, null, null, id);
			return true;
		}
	}

	/** Moves (or resizes) one grade's span. */
	public static final class ChangeSpan implements EditorCommand {
		private final String id;
		private final double startMs;
		private final double endMs;

		public ChangeSpan(String id, double startMs, double endMs) {
			this.id = id;
			this.startMs = startMs;
			this.endMs = endMs;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<GradeRegion> next = new ArrayList<>();
			boolean changed = false;
			for (GradeRegion region : store.state().gradeRegions()) {
				if (region.id().equals(id)) {
					GradeRegion moved = region.withSpan(Math.round(startMs), Math.round(endMs));
					next.add(moved);
					changed = !moved.equals(region);
				} else {
					next.add(region);
				}
			}
			if (changed) {
				store.setGradeRegions(next);
			}
			return changed;
		}
	}

	/** Sets the grading sliders of one region. */
	public static final class ChangeParams implements EditorCommand {
		private final String id;
		private final double brightness;
		private final double contrast;
		private final double saturation;
		private final double temperature;
		private final double gamma;

		public ChangeParams(String id, double brightness, double contrast, double saturation,
				double temperature, double gamma) {
			this.id = id;
			this.brightness = brightness;
			this.contrast = contrast;
			this.saturation = saturation;
			this.temperature = temperature;
			this.gamma = gamma;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<GradeRegion> next = new ArrayList<>();
			boolean changed = false;
			for (GradeRegion region : store.state().gradeRegions()) {
				if (region.id().equals(id)) {
					GradeRegion graded = region.withParams(brightness, contrast, saturation,
							temperature, gamma);
					next.add(graded);
					changed = !graded.equals(region);
				} else {
					next.add(region);
				}
			}
			if (changed) {
				store.setGradeRegions(next);
			}
			return changed;
		}
	}

	/** Sets which layers one grade shades (screen, camera or both). */
	public static final class ChangeTarget implements EditorCommand {
		private final String id;
		private final GradeTarget targets;

		public ChangeTarget(String id, GradeTarget targets) {
			this.id = id;
			this.targets = targets;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<GradeRegion> next = new ArrayList<>();
			boolean changed = false;
			for (GradeRegion region : store.state().gradeRegions()) {
				if (region.id().equals(id)) {
					GradeRegion retargeted = region.withTargets(targets);
					next.add(retargeted);
					changed = !retargeted.equals(region);
				} else {
					next.add(region);
				}
			}
			if (changed) {
				store.setGradeRegions(next);
			}
			return changed;
		}
	}

	/** Removes one grade. */
	public static final class Delete implements EditorCommand {
		private final String id;

		public Delete(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<GradeRegion> next = new ArrayList<>();
			for (GradeRegion region : store.state().gradeRegions()) {
				if (!region.id().equals(id)) {
					next.add(region);
				}
			}
			if (next.size() == store.state().gradeRegions().size()) {
				return false;
			}
			store.setGradeRegions(next);
			if (id.equals(store.selectedGradeId())) {
				store.setSelectedGradeId(null);
			}
			return true;
		}
	}
}
