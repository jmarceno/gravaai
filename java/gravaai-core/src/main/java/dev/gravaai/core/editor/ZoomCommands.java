package dev.gravaai.core.editor;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomFocus;
import dev.gravaai.core.project.ZoomMode;
import dev.gravaai.core.project.ZoomRegion;

/** The {@code useZoomRegionCommands} operations, one class per command. */
public final class ZoomCommands {

	private ZoomCommands() {}

	private static List<String> ids(List<ZoomRegion> regions) {
		List<String> ids = new ArrayList<>(regions.size());
		for (ZoomRegion region : regions) {
			ids.add(region.id());
		}
		return ids;
	}

	/** {@code handleSelectZoom}. */
	public static final class Select implements EditorCommand {
		private final String id;

		public Select(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setSelectedZoomId(id);
			if (id != null) {
				store.setActiveEffectSection(EditorStore.SECTION_ZOOM);
				store.clearSelectionsExcept(null, id, null, null, null);
			} else if (EditorStore.SECTION_ZOOM.equals(store.activeEffectSection())) {
				store.setActiveEffectSection(EditorStore.SECTION_SCENE);
			}
			return true;
		}
	}

	/** {@code handleZoomAdded}: a manually drawn region, auto camera tracking mode. */
	public static final class Add implements EditorCommand {
		private final double startMs;
		private final double endMs;

		public Add(double startMs, double endMs) {
			this.startMs = startMs;
			this.endMs = endMs;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<ZoomRegion> regions = store.state().zoomRegions();
			ZoomDepth depth = ZoomDepth.DEFAULT;
			String id = store.nextId("zoom", ids(regions));
			List<ZoomRegion> next = new ArrayList<>(regions);
			next.add(new ZoomRegion(id, Math.round(startMs), Math.round(endMs), depth,
					new ZoomFocus(0.5, 0.5).clampToDepth(depth), ZoomMode.AUTO));
			store.setZoomRegions(next);
			store.setSelectedZoomId(id);
			store.clearSelectionsExcept(null, id, null, null, null);
			return true;
		}
	}

	/** {@code handleZoomSuggested}: an auto-zoom suggestion; it does not steal the selection. */
	public static final class AddSuggested implements EditorCommand {
		private final double startMs;
		private final double endMs;
		private final ZoomFocus focus;

		public AddSuggested(double startMs, double endMs, ZoomFocus focus) {
			this.startMs = startMs;
			this.endMs = endMs;
			this.focus = focus;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<ZoomRegion> regions = store.state().zoomRegions();
			ZoomDepth depth = ZoomDepth.DEFAULT_AUTO;
			String id = store.nextId("zoom", ids(regions));
			List<ZoomRegion> next = new ArrayList<>(regions);
			next.add(new ZoomRegion(id, Math.round(startMs), Math.round(endMs), depth,
					focus.clampToDepth(depth), ZoomMode.AUTO));
			store.setZoomRegions(next);
			return true;
		}
	}

	/** {@code handleZoomSpanChange}. */
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
			List<ZoomRegion> next = new ArrayList<>();
			for (ZoomRegion region : store.state().zoomRegions()) {
				next.add(region.id().equals(id)
						? new ZoomRegion(region.id(), Math.round(startMs), Math.round(endMs),
								region.depth(), region.focus(), region.mode())
						: region);
			}
			store.setZoomRegions(next);
			return true;
		}
	}

	/** {@code handleZoomFocusChange}. */
	public static final class ChangeFocus implements EditorCommand {
		private final String id;
		private final ZoomFocus focus;

		public ChangeFocus(String id, ZoomFocus focus) {
			this.id = id;
			this.focus = focus;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<ZoomRegion> next = new ArrayList<>();
			for (ZoomRegion region : store.state().zoomRegions()) {
				next.add(region.id().equals(id)
						? new ZoomRegion(region.id(), region.startMs(), region.endMs(),
								region.depth(), focus.clampToDepth(region.depth()), region.mode())
						: region);
			}
			store.setZoomRegions(next);
			return true;
		}
	}

	/** {@code handleZoomDepthChange}. */
	public static final class ChangeDepth implements EditorCommand {
		private final @Nullable String id;
		private final ZoomDepth depth;

		public ChangeDepth(ZoomDepth depth) {
			this(null, depth);
		}

		/** Applies the depth to one zoom (multi-selection batches per id); null uses the selection. */
		public ChangeDepth(@Nullable String id, ZoomDepth depth) {
			this.id = id;
			this.depth = depth;
		}

		@Override
		public boolean apply(EditorStore store) {
			String selectedZoomId = id == null ? store.selectedZoomId() : id;
			if (selectedZoomId == null) {
				return false;
			}
			List<ZoomRegion> next = new ArrayList<>();
			for (ZoomRegion region : store.state().zoomRegions()) {
				next.add(region.id().equals(selectedZoomId)
						? new ZoomRegion(region.id(), region.startMs(), region.endMs(), depth,
								region.focus().clampToDepth(depth), region.mode())
						: region);
			}
			store.setZoomRegions(next);
			return true;
		}
	}

	/** {@code handleZoomModeChange}. */
	public static final class ChangeMode implements EditorCommand {
		private final @Nullable String id;
		private final ZoomMode mode;

		public ChangeMode(ZoomMode mode) {
			this(null, mode);
		}

		/** Applies the mode to one zoom (multi-selection batches per id); null uses the selection. */
		public ChangeMode(@Nullable String id, ZoomMode mode) {
			this.id = id;
			this.mode = mode;
		}

		@Override
		public boolean apply(EditorStore store) {
			String selectedZoomId = id == null ? store.selectedZoomId() : id;
			if (selectedZoomId == null) {
				return false;
			}
			List<ZoomRegion> next = new ArrayList<>();
			for (ZoomRegion region : store.state().zoomRegions()) {
				next.add(region.id().equals(selectedZoomId)
						? new ZoomRegion(region.id(), region.startMs(), region.endMs(),
								region.depth(), region.focus(), mode)
						: region);
			}
			store.setZoomRegions(next);
			return true;
		}
	}

	/** {@code handleZoomDelete}. */
	public static final class Delete implements EditorCommand {
		private final String id;

		public Delete(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<ZoomRegion> next = new ArrayList<>();
			for (ZoomRegion region : store.state().zoomRegions()) {
				if (!region.id().equals(id)) {
					next.add(region);
				}
			}
			store.setZoomRegions(next);
			if (id.equals(store.selectedZoomId())) {
				store.setSelectedZoomId(null);
			}
			return true;
		}
	}
}
