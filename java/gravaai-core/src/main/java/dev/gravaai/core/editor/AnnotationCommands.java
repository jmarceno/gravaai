package dev.gravaai.core.editor;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.AnnotationPosition;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationSize;
import dev.gravaai.core.project.AnnotationTextStyle;
import dev.gravaai.core.project.AnnotationType;
import dev.gravaai.core.project.FigureData;
import dev.gravaai.core.project.SpotlightData;

/** The {@code useAnnotationRegionCommands} operations, one class per command. */
public final class AnnotationCommands {

	/** Partial style update: only non-null components are merged into the region style. */
	public record StylePatch(
			@Nullable String color,
			@Nullable String backgroundColor,
			@Nullable Double fontSize,
			@Nullable String fontFamily,
			AnnotationTextStyle.@Nullable FontWeight fontWeight,
			AnnotationTextStyle.@Nullable FontStyle fontStyle,
			AnnotationTextStyle.@Nullable TextDecoration textDecoration,
			AnnotationTextStyle.@Nullable TextAlign textAlign,
			@Nullable Double borderRadius) {}

	private AnnotationCommands() {}

	private static AnnotationRegion copy(AnnotationRegion region) {
		return region;
	}

	private static AnnotationRegion withRegion(AnnotationRegion region, long startMs, long endMs,
			@Nullable Integer trackIndex, String content, @Nullable String textContent, @Nullable String imageContent,
			AnnotationPosition position, AnnotationSize size, AnnotationTextStyle style,
			@Nullable FigureData figureData, @Nullable Double blurIntensity, @Nullable String blurColor,
			@Nullable SpotlightData spotlightData) {
		return new AnnotationRegion(region.id(), startMs, endMs, region.type(), content,
				textContent, imageContent, position, size, style, region.zIndex(),
				trackIndex != null ? trackIndex : region.trackIndex(), figureData, blurIntensity,
				blurColor, region.loupeMagnification(), spotlightData);
	}

	/** Selects an annotation region (the hook's {@code onSelectAnnotation}). */
	public static final class Select implements EditorCommand {
		private final String id;

		public Select(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setSelectedAnnotationId(id);
			if (id != null) {
				// The preview overlay is a single-selection gesture: any Ctrl/Cmd multi-selection
				// on the timeline ends here (zoom/audio select paths clear through the store).
				store.setMultiSelection(java.util.List.of());
			}
			return true;
		}
	}

	/** {@code handleAnnotationAdded}. */
	public static final class Add implements EditorCommand {
		private final double startMs;
		private final double endMs;
		private final Integer trackIndex;

		public Add(double startMs, double endMs, Integer trackIndex) {
			this.startMs = startMs;
			this.endMs = endMs;
			this.trackIndex = trackIndex;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> regions = store.state().annotationRegions();
			List<String> ids = new ArrayList<>();
			for (AnnotationRegion region : regions) {
				ids.add(region.id());
			}
			String id = store.nextId("annotation", ids);
			AnnotationRegion region = new AnnotationRegion(id, Math.round(startMs),
					Math.round(endMs), AnnotationType.TEXT, "Enter text...", null, null,
					AnnotationPosition.DEFAULT, AnnotationSize.DEFAULT,
					AnnotationTextStyle.defaultStyle(), store.nextIndex("annotation-zIndex"),
					trackIndex == null ? 0 : trackIndex, null, null, null, null, null);
			List<AnnotationRegion> next = new ArrayList<>(regions);
			next.add(region);
			store.setAnnotationRegions(next);
			store.setSelectedAnnotationId(id);
			store.setSelectedZoomId(null);
			return true;
		}
	}

	/**
	 * Adds a loupe annotation (the "Magnifying Glass"): a lens that follows the cursor, so unlike
	 * {@link Add} there is no text placeholder — the region only carries the span, the lens
	 * diameter and the magnification. It lands on the first lane that is free over its span, so
	 * it never hides under another block and a batch of suggestions shares one lane.
	 */
	public static final class AddLoupe implements EditorCommand {
		private final double startMs;
		private final double endMs;
		private final boolean suggestion;

		public AddLoupe(double startMs, double endMs) {
			this(startMs, endMs, false);
		}

		/**
		 * @param suggestion true for a suggested loupe: it is skipped when a loupe already covers
		 *        any of its span (re-running "Suggest zooms" must not stack lenses)
		 */
		public AddLoupe(double startMs, double endMs, boolean suggestion) {
			this.startMs = startMs;
			this.endMs = endMs;
			this.suggestion = suggestion;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> regions = store.state().annotationRegions();
			long start = Math.round(startMs);
			long end = Math.round(endMs);
			if (suggestion && regions.stream().anyMatch(region -> region.type() == AnnotationType.LOUPE
					&& region.startMs() < end && start < region.endMs())) {
				return false;
			}
			List<String> ids = new ArrayList<>();
			for (AnnotationRegion region : regions) {
				ids.add(region.id());
			}
			int nextTrack = freeLane(regions, start, end);
			String id = store.nextId("annotation", ids);
			double diameter = AnnotationRegion.LOUPE_DEFAULT_DIAMETER_PERCENT;
			AnnotationRegion region = new AnnotationRegion(id, start, end,
					AnnotationType.LOUPE, "", null, null,
					AnnotationPosition.DEFAULT, new AnnotationSize(diameter, diameter),
					AnnotationTextStyle.defaultStyle(), store.nextIndex("annotation-zIndex"),
					nextTrack, null, null, null, AnnotationRegion.LOUPE_DEFAULT_MAGNIFICATION,
					null);
			List<AnnotationRegion> next = new ArrayList<>(regions);
			next.add(region);
			store.setAnnotationRegions(next);
			store.setSelectedAnnotationId(id);
			store.setSelectedZoomId(null);
			return true;
		}

		/** The lowest annotation lane with no block overlapping {@code [start, end)}. */
		private static int freeLane(List<AnnotationRegion> regions, long start, long end) {
			int lane = 0;
			while (true) {
				int candidate = lane;
				boolean taken = regions.stream().anyMatch(region ->
						(region.trackIndex() == null ? 0 : region.trackIndex()) == candidate
								&& region.startMs() < end && start < region.endMs());
				if (!taken) {
					return lane;
				}
				lane++;
			}
		}
	}

	/** {@code handleAnnotationSpanChange}. */
	public static final class ChangeSpan implements EditorCommand {
		private final String id;
		private final double startMs;
		private final double endMs;
		private final Integer trackIndex;

		public ChangeSpan(String id, double startMs, double endMs, Integer trackIndex) {
			this.id = id;
			this.startMs = startMs;
			this.endMs = endMs;
			this.trackIndex = trackIndex;
		}

		@Override
		public boolean apply(EditorStore store) {
			int normalizedTrackIndex = trackIndex != null && Double.isFinite(trackIndex)
					? Math.max(0, (int) Math.floor(trackIndex)) : -1;
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				if (!region.id().equals(id)) {
					next.add(region);
					continue;
				}
				next.add(new AnnotationRegion(region.id(), Math.round(startMs), Math.round(endMs),
						region.type(), region.content(), region.textContent(), region.imageContent(),
						region.position(), region.size(), region.style(), region.zIndex(),
						normalizedTrackIndex >= 0 ? normalizedTrackIndex : region.trackIndex(),
						region.figureData(), region.blurIntensity(), region.blurColor(),
						region.loupeMagnification(), region.spotlightData()));
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** {@code handleAnnotationDelete}. */
	public static final class Delete implements EditorCommand {
		private final String id;

		public Delete(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				if (!region.id().equals(id)) {
					next.add(region);
				}
			}
			store.setAnnotationRegions(next);
			if (id.equals(store.selectedAnnotationId())) {
				store.setSelectedAnnotationId(null);
			}
			return true;
		}
	}

	/** {@code handleAnnotationContentChange}. */
	public static final class ChangeContent implements EditorCommand {
		private final String id;
		private final String content;

		public ChangeContent(String id, String content) {
			this.id = id;
			this.content = content;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				if (!region.id().equals(id)) {
					next.add(region);
					continue;
				}
				String textContent = region.type() == AnnotationType.TEXT ? content
						: region.textContent();
				String imageContent = region.type() == AnnotationType.IMAGE ? content
						: region.imageContent();
				next.add(withRegion(region, region.startMs(), region.endMs(), null, content,
						textContent, imageContent, region.position(), region.size(),
						region.style(), region.figureData(), region.blurIntensity(),
						region.blurColor(), region.spotlightData()));
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** {@code handleAnnotationTypeChange}. */
	public static final class ChangeType implements EditorCommand {
		private final String id;
		private final AnnotationType type;

		public ChangeType(String id, AnnotationType type) {
			this.id = id;
			this.type = type;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				if (!region.id().equals(id)) {
					next.add(region);
					continue;
				}
				String content = region.content();
				FigureData figureData = region.figureData();
				Double blurIntensity = region.blurIntensity();
				Double loupeMagnification = region.loupeMagnification();
				SpotlightData spotlightData = region.spotlightData();
				AnnotationSize size = region.size();
				if (type == AnnotationType.TEXT) {
					content = region.textContent() != null ? region.textContent() : "Enter text...";
				} else if (type == AnnotationType.IMAGE) {
					content = region.imageContent() != null ? region.imageContent() : "";
				} else if (type == AnnotationType.FIGURE) {
					content = "";
					if (figureData == null) {
						figureData = FigureData.DEFAULT;
					}
				} else if (type == AnnotationType.BLUR) {
					content = "";
					if (blurIntensity == null) {
						blurIntensity = 20.0;
					}
				} else if (type == AnnotationType.LOUPE) {
					content = "";
					if (loupeMagnification == null) {
						loupeMagnification = AnnotationRegion.LOUPE_DEFAULT_MAGNIFICATION;
					}
					if (region.type() != AnnotationType.LOUPE) {
						// A box annotation's size means nothing for a lens: start from the default.
						size = loupeSize(AnnotationRegion.LOUPE_DEFAULT_DIAMETER_PERCENT);
					} else if (size.width() != size.height()) {
						size = loupeSize(size.width());
					}
				} else if (type == AnnotationType.SPOTLIGHT) {
					content = "";
					if (spotlightData == null) {
						spotlightData = SpotlightData.DEFAULT;
					}
				}
				next.add(new AnnotationRegion(region.id(), region.startMs(), region.endMs(), type,
						content, region.textContent(), region.imageContent(), region.position(),
						size, region.style(), region.zIndex(), region.trackIndex(),
						figureData, blurIntensity, region.blurColor(), loupeMagnification,
						spotlightData));
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** {@code handleAnnotationStyleChange}: merge the partial style over the current one. */
	public static final class ChangeStyle implements EditorCommand {
		private final String id;
		private final StylePatch patch;

		public ChangeStyle(String id, StylePatch patch) {
			this.id = id;
			this.patch = patch;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				if (!region.id().equals(id)) {
					next.add(region);
					continue;
				}
				AnnotationTextStyle style = region.style();
				AnnotationTextStyle merged = new AnnotationTextStyle(
						patch.color() != null ? patch.color() : style.color(),
						patch.backgroundColor() != null ? patch.backgroundColor()
								: style.backgroundColor(),
						patch.fontSize() != null ? patch.fontSize() : style.fontSize(),
						patch.fontFamily() != null ? patch.fontFamily() : style.fontFamily(),
						patch.fontWeight() != null ? patch.fontWeight() : style.fontWeight(),
						patch.fontStyle() != null ? patch.fontStyle() : style.fontStyle(),
						patch.textDecoration() != null ? patch.textDecoration()
								: style.textDecoration(),
						patch.textAlign() != null ? patch.textAlign() : style.textAlign(),
						patch.borderRadius() != null ? patch.borderRadius() : style.borderRadius());
				next.add(withRegion(region, region.startMs(), region.endMs(), null,
						region.content(), region.textContent(), region.imageContent(),
						region.position(), region.size(), merged, region.figureData(),
						region.blurIntensity(), region.blurColor(), region.spotlightData()));
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** {@code handleAnnotationFigureDataChange}. */
	public static final class SetFigureData implements EditorCommand {
		private final String id;
		private final FigureData figureData;

		public SetFigureData(String id, FigureData figureData) {
			this.id = id;
			this.figureData = figureData;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				next.add(region.id().equals(id)
						? withRegion(region, region.startMs(), region.endMs(), null,
								region.content(), region.textContent(), region.imageContent(),
								region.position(), region.size(), region.style(), figureData,
								region.blurIntensity(), region.blurColor(), region.spotlightData())
						: region);
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** {@code handleAnnotationBlurIntensityChange}. */
	public static final class SetBlurIntensity implements EditorCommand {
		private final String id;
		private final double blurIntensity;

		public SetBlurIntensity(String id, double blurIntensity) {
			this.id = id;
			this.blurIntensity = blurIntensity;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				next.add(region.id().equals(id)
						? withRegion(region, region.startMs(), region.endMs(), null,
								region.content(), region.textContent(), region.imageContent(),
								region.position(), region.size(), region.style(),
								region.figureData(), blurIntensity, region.blurColor(),
								region.spotlightData())
						: region);
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** {@code handleAnnotationBlurColorChange}. */
	public static final class SetBlurColor implements EditorCommand {
		private final String id;
		private final String blurColor;

		public SetBlurColor(String id, String blurColor) {
			this.id = id;
			this.blurColor = blurColor;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				next.add(region.id().equals(id)
						? withRegion(region, region.startMs(), region.endMs(), null,
								region.content(), region.textContent(), region.imageContent(),
								region.position(), region.size(), region.style(),
								region.figureData(), region.blurIntensity(), blurColor,
								region.spotlightData())
						: region);
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** {@code handleAnnotationLoupeMagnificationChange}: clamped to the 1.2–4× renderer range. */
	public static final class SetLoupeMagnification implements EditorCommand {
		private final String id;
		private final double loupeMagnification;

		public SetLoupeMagnification(String id, double loupeMagnification) {
			this.id = id;
			this.loupeMagnification = loupeMagnification;
		}

		@Override
		public boolean apply(EditorStore store) {
			double clamped = Math.max(1.2, Math.min(4, loupeMagnification));
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				next.add(region.id().equals(id)
						? new AnnotationRegion(region.id(), region.startMs(), region.endMs(),
								region.type(), region.content(), region.textContent(),
								region.imageContent(), region.position(), region.size(),
								region.style(), region.zIndex(), region.trackIndex(),
								region.figureData(), region.blurIntensity(), region.blurColor(),
								clamped, region.spotlightData())
						: region);
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** {@code handleAnnotationSpotlightDimChange}: clamped by {@link SpotlightData}. */
	public static final class SetSpotlightDim implements EditorCommand {
		private final String id;
		private final double dimPercent;

		public SetSpotlightDim(String id, double dimPercent) {
			this.id = id;
			this.dimPercent = dimPercent;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				next.add(region.id().equals(id)
						? withRegion(region, region.startMs(), region.endMs(), null,
								region.content(), region.textContent(), region.imageContent(),
								region.position(), region.size(), region.style(),
								region.figureData(), region.blurIntensity(), region.blurColor(),
								replace(region.spotlightDataOrDefault(), dimPercent, null))
						: region);
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** {@code handleAnnotationSpotlightFeatherChange}: clamped by {@link SpotlightData}. */
	public static final class SetSpotlightFeather implements EditorCommand {
		private final String id;
		private final double featherPercent;

		public SetSpotlightFeather(String id, double featherPercent) {
			this.id = id;
			this.featherPercent = featherPercent;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				next.add(region.id().equals(id)
						? withRegion(region, region.startMs(), region.endMs(), null,
								region.content(), region.textContent(), region.imageContent(),
								region.position(), region.size(), region.style(),
								region.figureData(), region.blurIntensity(), region.blurColor(),
								replace(region.spotlightDataOrDefault(), null, featherPercent))
						: region);
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** One spotlight component replaced ({@code null} keeps the current one). */
	private static SpotlightData replace(SpotlightData spotlight, @Nullable Double dimPercent,
			@Nullable Double featherPercent) {
		return new SpotlightData(
				dimPercent == null ? spotlight.dimPercent() : dimPercent,
				featherPercent == null ? spotlight.featherPercent() : featherPercent);
	}

	/** The square lens size for a loupe diameter percent (the lens stays circular). */
	public static AnnotationSize loupeSize(double diameterPercent) {
		double diameter = Math.max(5, Math.min(100, diameterPercent));
		return new AnnotationSize(diameter, diameter);
	}

	/** {@code handleAnnotationPositionChange}. */
	public static final class ChangePosition implements EditorCommand {
		private final String id;
		private final AnnotationPosition position;

		public ChangePosition(String id, AnnotationPosition position) {
			this.id = id;
			this.position = position;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				next.add(region.id().equals(id)
						? withRegion(region, region.startMs(), region.endMs(), null,
								region.content(), region.textContent(), region.imageContent(),
								position, region.size(), region.style(), region.figureData(),
								region.blurIntensity(), region.blurColor(), region.spotlightData())
						: region);
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}

	/** {@code handleAnnotationSizeChange}. */
	public static final class ChangeSize implements EditorCommand {
		private final String id;
		private final AnnotationSize size;

		public ChangeSize(String id, AnnotationSize size) {
			this.id = id;
			this.size = size;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AnnotationRegion> next = new ArrayList<>();
			for (AnnotationRegion region : store.state().annotationRegions()) {
				next.add(region.id().equals(id)
						? withRegion(region, region.startMs(), region.endMs(), null,
								region.content(), region.textContent(), region.imageContent(),
								region.position(), size, region.style(), region.figureData(),
								region.blurIntensity(), region.blurColor(), region.spotlightData())
						: region);
			}
			store.setAnnotationRegions(next);
			return true;
		}
	}
}
