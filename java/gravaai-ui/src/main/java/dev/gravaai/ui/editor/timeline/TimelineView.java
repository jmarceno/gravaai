package dev.gravaai.ui.editor.timeline;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionTimeline;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.TrimRegion;
import dev.gravaai.core.project.ZoomRegion;
import dev.gravaai.ui.editor.timeline.core.ClipPresentation;
import dev.gravaai.ui.editor.timeline.core.Span;
import dev.gravaai.ui.editor.timeline.core.TimelineDndEngine;
import dev.gravaai.ui.editor.timeline.core.TimelineRangeMath;
import dev.gravaai.ui.editor.timeline.core.TimelineRegionSpan;
import dev.gravaai.ui.editor.timeline.core.TimelineShortcuts;
import dev.gravaai.ui.editor.timeline.core.TimelineSourceAudioTrack;
import dev.gravaai.ui.agent.AgentHighlightView;
import dev.gravaai.ui.agent.AgentHighlightsController;
import dev.gravaai.ui.agent.AgentTimelineHighlights;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils;
import dev.gravaai.ui.editor.timeline.core.TimelineLayout;
import dev.gravaai.ui.editor.timeline.core.TimelineModel;
import dev.gravaai.ui.editor.timeline.core.TimelineRenderItem;
import dev.gravaai.ui.editor.timeline.core.TimelineRows;
import dev.gravaai.ui.editor.timeline.core.TimelineScale;
import dev.gravaai.ui.editor.timeline.core.TimelineConstants;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.Cursor;
import javafx.scene.control.ScrollBar;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * The timeline surface (port of {@code TimelineCanvas.tsx} painting; plan §5.12/P2.7).
 *
 * <p>{@code TimelineView extends Region} and draws every row on one {@link Canvas}: ruler, clip row
 * with seams/fades/speed/camera indicators, zoom lane, trim gaps, speed bands, annotation lanes,
 * audio lanes with waveforms, embedded captions, playhead and the agent-highlight shell. Data comes
 * from {@link TimelineDataSource}, gestures go to {@link TimelineCommands}; nothing here depends on
 * the media layer (waveforms come from {@link WaveformProvider}).
 *
 * <p>Visual constants are copied from the React sources: row heights from {@code timelineLayout.ts},
 * the 24 px clip seam and 8 px snap from {@code clipPresentation.ts}, block colours from
 * {@code ItemGlass.module.css} ({@code glassCyan} #183065/#1b3a7f, {@code glassBlue} accent,
 * {@code glassYellow} #4e4726/#62582c, {@code glassDarkGreen} #164f2d/#196b38,
 * {@code glassAmber} #67460f/#83570f, {@code glassRed} #652425/#802a2b, captions #b58b12/#facc15),
 * 8 px block radius, and the 85% block height inside its row.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class TimelineView extends Region {

	private static final double AXIS_HEIGHT = TimelineLayout.TIMELINE_AXIS_HEIGHT_PX;
	private static final double CLIP_ROW_HEIGHT = TimelineLayout.TIMELINE_CLIP_ROW_HEIGHT_PX;
	private static final double COMPACT_ROW_HEIGHT = TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX;
	private static final double ROW_GAP = 2;
	private static final double MIN_VISIBLE_RANGE_MS = 300;
	private static final int WAVEFORM_BARS = 96;
	private static final double RESIZE_EDGE_PX = 6;
	private static final double DRAG_THRESHOLD_PX = 3;
	private static final double GHOST_ZOOM_MS = 1000;
	private static final double CAPTION_GHOST_MS = 1500;
	private static final double CAPTION_STRIP_TOP_OFFSET = 36;
	private static final double CAPTION_STRIP_HEIGHT = 20;
	/** Thickness of the horizontal (time) and vertical (rows) scroll bars. */
	private static final double SCROLL_BAR_SIZE = 10;
	/** Vertical wheel step for the rows scroll, in pixels per wheel unit. */
	private static final double ROW_SCROLL_STEP = 1;

	/* ItemGlass.module.css colours (dark theme). */
	private static final Color CLIP_FILL = Color.web("#183065");
	private static final Color CLIP_BORDER = Color.web("#1b3a7f");
	private static final Color CLIP_SELECTED = Color.web("#2563eb");
	private static final Color ZOOM_FILL = Color.web("#2fb3a3");
	private static final Color ZOOM_BORDER = Color.web("#3fd4c1");
	private static final Color ANNOTATION_FILL = Color.web("#4e4726");
	private static final Color ANNOTATION_BORDER = Color.web("#62582c");
	private static final Color ANNOTATION_SELECTED = Color.web("#b4a046");
	private static final Color AUDIO_FILL = Color.web("#164f2d");
	private static final Color AUDIO_BORDER = Color.web("#196b38");
	private static final Color AUDIO_SELECTED = Color.web("#22c55e");
	private static final Color SOURCE_AUDIO_FILL = Color.web("#0f3b2a");
	private static final Color CAPTION_FILL = Color.web("#b58b12");
	private static final Color CAPTION_BORDER = Color.web("#facc15");
	private static final Color CAPTION_SELECTED = Color.web("#60a5fa");
	private static final Color TRIM_FILL = Color.web("#652425", 0.35);
	private static final Color TRIM_BORDER = Color.web("#802a2b");
	private static final Color SPEED_FILL = Color.web("#67460f", 0.55);
	private static final Color SPEED_BORDER = Color.web("#83570f");
	private static final Color PLAYHEAD = Color.web("#ef4444");
	private static final double PLAYHEAD_CAP_WIDTH = 16;
	private static final double PLAYHEAD_CAP_EXPANDED = 68;
	private static final double PLAYHEAD_CAP_HEIGHT = 16;
	/** Grab tolerance around the playhead line in the lanes. */
	private static final double PLAYHEAD_GRAB_PX = 4;
	private static final Font PLAYHEAD_FONT = Font.font("System",
			javafx.scene.text.FontWeight.MEDIUM, 10);
	private static final Color ROW_LABEL = Color.web("#666666");
	private static final Color GRID_LINE = Color.web("#ffffff", 0.04);
	private static final Color BLOCK_TEXT = Color.web("#ffffff", 0.92);
	private static final Color BADGE_FILL = Color.web("#000000", 0.65);

	private static final Font AXIS_FONT = Font.font("DM Sans", 10);
	private static final Font BLOCK_FONT = Font.font("DM Sans", FontWeight.MEDIUM, 11);
	private static final Font SMALL_FONT = Font.font("DM Sans", 10);
	private static final Font ROW_LABEL_FONT = Font.font("DM Sans", FontWeight.BOLD, 9);

	private record RowLayout(String id, double top, double height) {
	}

	/** Axis-aligned block bounds in view-local pixels ({@link javafx.geometry.Bounds} is abstract). */
	public record BlockBounds(double minX, double minY, double width, double height) {

		public double maxX() {
			return minX + width;
		}

		public double maxY() {
			return minY + height;
		}

		public boolean contains(double x, double y) {
			return x >= minX && x <= maxX() && y >= minY && y <= maxY();
		}

		public double centerX() {
			return minX + width / 2;
		}

		public double centerY() {
			return minY + height / 2;
		}
	}

	private final TimelineDataSource dataSource;
	private final TimelineCommands commands;
	private final Canvas canvas = new Canvas(1000, 200);
	/** Time scroll bar under the lanes: thumb = visible range inside the whole timeline. */
	private final ScrollBar timeScrollBar = new ScrollBar();
	/** Rows scroll bar beside the lanes; shown when the rows are taller than the panel. */
	private final ScrollBar rowScrollBar = new ScrollBar();
	/** Vertical offset of the lanes (the ruler stays pinned, like the React sticky axis). */
	private double scrollY;
	private boolean syncingScrollBars;

	private Span range = new Span(0, 1000);
	private Span fullRange = new Span(0, 1000);
	private boolean rangeInitialised;
	private @Nullable TimelineSelection selection;
	private List<AgentHighlight> agentHighlights = List.of();
	private double currentTimeMs;
	private List<RowLayout> rowLayouts = List.of();
	private List<TimelineRenderItem> items = List.of();
	private List<ClipPresentation.Presentation> clipPresentation = List.of();
	private final Map<String, BlockBounds> blockBounds = new LinkedHashMap<>();

	private enum DragMode {
		NONE,
		MOVE,
		RESIZE_LEFT,
		RESIZE_RIGHT
	}

	private DragMode dragMode = DragMode.NONE;
	private @Nullable String dragItemId;
	private @Nullable String dragRowId;
	private double dragStartX;
	private @Nullable Span dragOriginalSpan;
	private @Nullable Span dragLiveSpan;
	private boolean dragged;
	private boolean pressedEmpty;
	/** Seek-drag in progress (empty lane press or playhead grab, React isSeeking / cap drag). */
	private boolean scrubbing;
	/** Pointer over the playhead cap/line: the cap expands and shows the time (React isHovered). */
	private boolean playheadHovered;
	private @Nullable Span hoverGhost;
	private @Nullable String hoverGhostKind;
	private double minItemDurationMs = 100;
	private List<CaptionTimeline.Fragment> captionFragments = List.of();
	private List<TimelineSourceAudioTrack> sourceAudioTracks = List.of();
	private @Nullable FilmstripProvider filmstripProvider = FilmstripProvider.empty();
	private static final int FILMSTRIP_THUMB_WIDTH = 160;
	private static final int FILMSTRIP_THUMB_HEIGHT = 90;
	private static final int FILMSTRIP_CACHE_LIMIT = 768;
	private final java.util.Map<FilmstripKey, Image> filmstripCache = new java.util.LinkedHashMap<>(128,
			0.75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<FilmstripKey, Image> eldest) {
			return size() > FILMSTRIP_CACHE_LIMIT;
		}
	};
	private final java.util.Map<String, List<Double>> filmstripSamples = new java.util.LinkedHashMap<>();
	private final java.util.Map<String, Integer> filmstripFrameCounts = new java.util.LinkedHashMap<>();

	private record FilmstripKey(String clipId, double sourceTimeMs, int width, int height) {
	}
	private @Nullable AgentTimelineHighlights agentOverlay;
	private @Nullable AgentHighlightsController agentController;
	private javafx.collections.@Nullable ListChangeListener<AgentHighlightView> agentHighlightsListener;
	private java.util.function.@Nullable Consumer<String> onCaptionSelected;
	private @Nullable Runnable onItemPressed;
	private boolean selectAllBlocksActive;
	private TimelineShortcuts.Bindings shortcutBindings = TimelineShortcuts.defaults();
	private boolean macPlatform;

	public TimelineView(TimelineDataSource dataSource, TimelineCommands commands) {
		this.dataSource = dataSource;
		this.commands = commands == null ? TimelineCommands.NONE : commands;
		getChildren().addAll(canvas, timeScrollBar, rowScrollBar);
		getStyleClass().add("timeline-view");
		java.net.URL timelineCss = TimelineView.class.getResource("/ui/editor/timeline.css");
		if (timelineCss != null) {
			getStylesheets().add(timelineCss.toExternalForm());
		}
		timeScrollBar.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
		timeScrollBar.getStyleClass().add("timeline-scroll-bar");
		timeScrollBar.setFocusTraversable(false);
		timeScrollBar.valueProperty().addListener((observable, oldValue, value) -> {
			if (!syncingScrollBars) {
				double duration = range.duration();
				setRange(new Span(value.doubleValue(), value.doubleValue() + duration));
			}
		});
		rowScrollBar.setOrientation(javafx.geometry.Orientation.VERTICAL);
		rowScrollBar.getStyleClass().add("timeline-scroll-bar");
		rowScrollBar.setFocusTraversable(false);
		rowScrollBar.setVisible(false);
		rowScrollBar.valueProperty().addListener((observable, oldValue, value) -> {
			if (!syncingScrollBars) {
				setScrollY(value.doubleValue());
			}
		});
		setMinHeight(AXIS_HEIGHT);
		setFocusTraversable(false);
		canvas.setOnScroll(this::handleScroll);
		canvas.setOnMousePressed(this::handleMousePressed);
		canvas.setOnMouseDragged(this::handleMouseDragged);
		canvas.setOnMouseReleased(this::handleMouseReleased);
		canvas.setOnMouseMoved(this::handleMouseMoved);
		canvas.setOnMouseExited(event -> {
			hoverGhost = null;
			hoverGhostKind = null;
			canvas.setCursor(Cursor.DEFAULT);
			redraw();
		});
		canvas.setFocusTraversable(true);
		canvas.setOnKeyPressed(this::handleKeyPressed);
		widthProperty().addListener((observable, oldValue, value) -> requestLayout());
		heightProperty().addListener((observable, oldValue, value) -> requestLayout());
		refreshData();
	}

	/* ---------- public API (tests / shell wiring) ---------- */

	public Span range() {
		return range;
	}

	public Span fullRange() {
		return fullRange;
	}

	/** Visible milliseconds per screen pixel. */
	public double msPerPixel() {
		double width = Math.max(1, contentWidth());
		return range.duration() / width;
	}

	/** Screen x for a timeline time. */
	public double xForTime(double timeMs) {
		return (timeMs - range.start()) / msPerPixel();
	}

	/** Timeline time for a screen x. */
	public double timeAtX(double x) {
		return Math.max(0, Math.min(range.start() + x * msPerPixel(), fullRange.end()));
	}

	public void setRange(Span next) {
		range = TimelineDndEngine.clampRange(next,
				new TimelineDndEngine.RangeBounds(fullRange.end(), MIN_VISIBLE_RANGE_MS));
		redraw();
	}

	/** Zoom around an anchor time; {@code factor > 1} zooms in. */
	public void zoomAt(double factor, double anchorMs) {
		if (!(factor > 0) || !Double.isFinite(factor)) {
			return;
		}
		double visible = range.duration();
		double newVisible = Math.max(MIN_VISIBLE_RANGE_MS, Math.min(visible / factor, fullRange.end()));
		double anchorRatio = visible <= 0 ? 0 : (anchorMs - range.start()) / visible;
		double start = anchorMs - anchorRatio * newVisible;
		setRange(new Span(start, start + newVisible));
	}

	/** Horizontal pan by a timeline-millisecond delta. */
	public void panByMs(double deltaMs) {
		if (!Double.isFinite(deltaMs) || deltaMs == 0) {
			return;
		}
		setRange(new Span(range.start() + deltaMs, range.end() + deltaMs));
	}

	public void setCurrentTimeMs(double currentTimeMs) {
		this.currentTimeMs = Math.max(0, Math.min(currentTimeMs, fullRange.end()));
		redraw();
	}

	/** The playhead position in milliseconds. */
	public double currentTime() {
		return currentTimeMs;
	}

	public @Nullable TimelineSelection selection() {
		return selection;
	}

	public void setSelection(@Nullable TimelineSelection selection) {
		this.selection = selection;
		redraw();
	}

	/** Agent highlight overlay shell (P3 fills the data). */
	public void setAgentHighlights(List<AgentHighlight> highlights) {
		this.agentHighlights = highlights == null ? List.of() : List.copyOf(highlights);
		redraw();
	}

	/** Row ids in painting order; the Clip row first, then Zoom, annotations, audio. */
	public List<String> rowIds() {
		return rowLayouts.stream().map(RowLayout::id).toList();
	}

	/** Vertical scroll of the lanes in pixels (0 = first row right under the ruler). */
	public double scrollY() {
		return scrollY;
	}

	/** Largest {@link #scrollY()}: how far the rows overflow the visible lane area. */
	public double maxScrollY() {
		return Math.max(0, contentHeight() - Math.max(AXIS_HEIGHT, canvas.getHeight()));
	}

	/** Scrolls the lanes vertically (clamped); the ruler and playhead cap stay put. */
	public void setScrollY(double next) {
		double clamped = Double.isFinite(next) ? Math.max(0, Math.min(next, maxScrollY())) : 0;
		if (clamped != scrollY) {
			scrollY = clamped;
			redraw();
		}
	}

	/** The time scroll bar (tests, styling). */
	public ScrollBar timeScrollBar() {
		return timeScrollBar;
	}

	/** The rows scroll bar (tests, styling). */
	public ScrollBar rowScrollBar() {
		return rowScrollBar;
	}

	/**
	 * Bounds of a rendered block in content coordinates (lane y before {@link #scrollY()}), the
	 * same space {@link #pressAt}/{@link #dragTo}/{@link #releaseAt} take; for tests and automation.
	 */
	public Optional<BlockBounds> blockBounds(String itemId) {
		return Optional.ofNullable(blockBounds.get(itemId));
	}

	/** Number of rows: clip + zoom + one per annotation track + one per audio track. */
	public int rowCount() {
		return rowLayouts.size();
	}

	/** The minimum height for every row plus the axis (port of timelineLayout.ts). */
	public double contentHeight() {
		double rowsBottom = rowLayouts.isEmpty() ? 0
				: rowLayouts.get(rowLayouts.size() - 1).top() + rowLayouts.get(rowLayouts.size() - 1).height()
						+ ROW_GAP;
		return Math.max(TimelineLayout.getTimelineContentMinHeightPx(rowCount()), rowsBottom);
	}

	/** Re-reads the data source and repaints (the EditorStore adapter calls this on changes). */
	public void refreshData() {
		ProjectEditorState project = dataSource.project();
		long total = Math.max(1, dataSource.totalDurationMs());
		minItemDurationMs = TimelineScale.calculateTimelineScale(total / 1000.0).minItemDurationMs();
		Span nextFullRange = new Span(0, total);
		boolean resetRange = !rangeInitialised
				|| fullRange.end() != nextFullRange.end()
				|| range.end() > total
				|| range.duration() <= 0;
		fullRange = nextFullRange;
		if (resetRange) {
			range = nextFullRange;
		}
		rangeInitialised = true;
		currentTimeMs = Math.max(0, Math.min(dataSource.currentTimeMs(), total));
		items = TimelineModel.buildTimelineItems(new TimelineModel.ItemsRequest(
				project.zoomRegions(), project.clipRegions(), project.annotationRegions(),
				project.audioRegions(), project.autoCaptions(),
				project.sourceAudioTrackSettings(), sourceAudioTracks));
		sourceAudioTracks = dataSource.sourceAudioTracks();
		filmstripProvider = dataSource.filmstripProvider() == null ? FilmstripProvider.empty()
				: dataSource.filmstripProvider();
		filmstripProvider.setOnFrameReady(
				() -> javafx.application.Platform.runLater(this::redraw));
		captionFragments = CaptionTimeline.projectCaptionCues(project.autoCaptions(),
				project.clipRegions());
		clipPresentation = buildClipPresentation(project.clipRegions());
		rowLayouts = buildRowLayouts();
		requestLayout();
	}

	/* ---------- layout ---------- */

	@Override
	protected double computePrefWidth(double height) {
		return 1200;
	}

	@Override
	protected double computePrefHeight(double width) {
		return contentHeight() + SCROLL_BAR_SIZE;
	}

	/**
	 * Only the ruler and one lane are required: the rest scrolls inside the panel (React's
	 * {@code timeline-scroll} is {@code overflow-y-auto}), so the timeline never grows past it.
	 */
	@Override
	protected double computeMinHeight(double width) {
		return AXIS_HEIGHT + COMPACT_ROW_HEIGHT + SCROLL_BAR_SIZE;
	}

	@Override
	protected void layoutChildren() {
		double width = Math.max(1, getWidth());
		double lanesHeight = Math.max(AXIS_HEIGHT, getHeight() - SCROLL_BAR_SIZE);
		boolean rowsOverflow = contentHeight() > lanesHeight + 0.5;
		double lanesWidth = Math.max(1, width - (rowsOverflow ? SCROLL_BAR_SIZE : 0));
		canvas.setWidth(lanesWidth);
		canvas.setHeight(lanesHeight);
		timeScrollBar.resizeRelocate(0, lanesHeight, lanesWidth, SCROLL_BAR_SIZE);
		rowScrollBar.setVisible(rowsOverflow);
		rowScrollBar.resizeRelocate(lanesWidth, AXIS_HEIGHT, SCROLL_BAR_SIZE,
				Math.max(1, lanesHeight - AXIS_HEIGHT));
		scrollY = Math.max(0, Math.min(scrollY, maxScrollY()));
		if (agentOverlay != null) {
			agentOverlay.resizeRelocate(0, 0, lanesWidth, lanesHeight);
		}
		redraw();
	}

	/** Mirrors the visible range and the rows scroll into the two scroll bars. */
	private void syncScrollBars() {
		syncingScrollBars = true;
		try {
			double total = Math.max(1, fullRange.end());
			double visible = Math.min(total, Math.max(1, range.duration()));
			double timeMax = Math.max(0, total - visible);
			timeScrollBar.setMin(0);
			timeScrollBar.setMax(Math.max(timeMax, 1e-6));
			// JavaFX sizes the thumb as visibleAmount / (max - min) of the track.
			timeScrollBar.setVisibleAmount(timeMax <= 0 ? 1e-6 : timeMax * visible / total);
			timeScrollBar.setUnitIncrement(visible * 0.05);
			timeScrollBar.setBlockIncrement(visible * 0.9);
			timeScrollBar.setValue(Math.max(0, Math.min(range.start(), timeMax)));
			timeScrollBar.setDisable(timeMax <= 0);

			double rowsVisible = Math.max(1, canvas.getHeight() - AXIS_HEIGHT);
			double rowsMax = maxScrollY();
			rowScrollBar.setMin(0);
			rowScrollBar.setMax(Math.max(rowsMax, 1e-6));
			rowScrollBar.setVisibleAmount(rowsMax <= 0 ? 1e-6 : rowsMax * rowsVisible / (rowsMax + rowsVisible));
			rowScrollBar.setUnitIncrement(COMPACT_ROW_HEIGHT / 2);
			rowScrollBar.setBlockIncrement(rowsVisible * 0.9);
			rowScrollBar.setValue(scrollY);
		} finally {
			syncingScrollBars = false;
		}
	}

	/** Pointer y in the canvas to content y: the ruler is pinned, the lanes scroll. */
	private double contentY(double canvasY) {
		return canvasY <= AXIS_HEIGHT ? canvasY : canvasY + scrollY;
	}

	private double contentWidth() {
		return Math.max(1, canvas.getWidth());
	}

	private List<RowLayout> buildRowLayouts() {
		List<RowLayout> rows = new ArrayList<>();
		long annotationTracks = items.stream()
				.filter(item -> TimelineRows.isAnnotationTrackRowId(item.rowId()))
				.map(TimelineRenderItem::rowId).distinct().count();
		long audioTracks = items.stream()
				.filter(item -> TimelineRows.isAudioTrackRowId(item.rowId()))
				.map(TimelineRenderItem::rowId).distinct().count();
		long extras = annotationTracks + audioTracks + sourceAudioTracks.size();

		double top = AXIS_HEIGHT;
		rows.add(new RowLayout(TimelineConstants.CLIP_ROW_ID, top, CLIP_ROW_HEIGHT));
		top += CLIP_ROW_HEIGHT + ROW_GAP;
		for (TimelineSourceAudioTrack track : sourceAudioTracks) {
			rows.add(new RowLayout(TimelineRows.getSourceAudioTrackRowId(track.id()), top,
					COMPACT_ROW_HEIGHT));
			top += COMPACT_ROW_HEIGHT + ROW_GAP;
		}

		double zoomHeight = extras > 0 ? COMPACT_ROW_HEIGHT : CLIP_ROW_HEIGHT;
		rows.add(new RowLayout(TimelineConstants.ZOOM_ROW_ID, top, zoomHeight));
		top += zoomHeight + ROW_GAP;

		List<Integer> annotationTrackIndexes = new ArrayList<>();
		for (TimelineRenderItem item : items) {
			if (TimelineRows.isAnnotationTrackRowId(item.rowId())) {
				int index = TimelineRows.getAnnotationTrackIndex(item.rowId());
				if (!annotationTrackIndexes.contains(index)) {
					annotationTrackIndexes.add(index);
				}
			}
		}
		annotationTrackIndexes.sort(Comparator.naturalOrder());
		for (int index : annotationTrackIndexes) {
			rows.add(new RowLayout(TimelineRows.getAnnotationTrackRowId(index), top, COMPACT_ROW_HEIGHT));
			top += COMPACT_ROW_HEIGHT + ROW_GAP;
		}

		List<Integer> audioTrackIndexes = new ArrayList<>();
		for (TimelineRenderItem item : items) {
			if (TimelineRows.isAudioTrackRowId(item.rowId())) {
				int index = TimelineRows.getAudioTrackIndex(item.rowId());
				if (!audioTrackIndexes.contains(index)) {
					audioTrackIndexes.add(index);
				}
			}
		}
		audioTrackIndexes.sort(Comparator.naturalOrder());
		for (int index : audioTrackIndexes) {
			rows.add(new RowLayout(TimelineRows.getAudioTrackRowId(index), top, COMPACT_ROW_HEIGHT));
			top += COMPACT_ROW_HEIGHT + ROW_GAP;
		}
		return List.copyOf(rows);
	}

	private List<ClipPresentation.Presentation> buildClipPresentation(List<ClipRegion> clips) {
		if (clips == null || clips.isEmpty()) {
			return List.of();
		}
		List<ClipRegion> sorted = new ArrayList<>(clips);
		sorted.sort(Comparator.comparingLong(ClipRegion::startMs));
		List<Span> spans = new ArrayList<>();
		for (ClipRegion clip : sorted) {
			spans.add(new Span(clip.startMs(), clip.endMs()));
		}
		double pxPerMs = 1.0 / msPerPixel();
		List<ClipPresentation.Presentation> presentations = new ArrayList<>();
		for (Span span : spans) {
			presentations.add(new ClipPresentation.Presentation(span,
					ClipPresentation.getClipDisplaySpan(span, spans, pxPerMs)));
		}
		return List.copyOf(presentations);
	}

	/* ---------- input ---------- */

	private void handleScroll(ScrollEvent event) {
		boolean zoom = (event.isControlDown() || event.isMetaDown()) && !event.isShiftDown();
		if (zoom) {
			zoomAt(event.getDeltaY() < 0 ? 1.25 : 0.8, timeAtX(event.getX()));
			event.consume();
			return;
		}
		double deltaPx = TimelineRangeMath.resolveWheelPanDeltaPx(new TimelineRangeMath.WheelInput(
				event.getDeltaX(), event.getDeltaY(), 0, event.isShiftDown(),
				event.isControlDown(), event.isMetaDown(),
				maxScrollY() > 0));
		if (deltaPx == 0) {
			// Rows taller than the panel: a plain vertical wheel scrolls the lanes (React
			// overflow-y-auto); shift+wheel or a horizontal wheel still pans time.
			if (event.getDeltaY() != 0 && maxScrollY() > 0) {
				setScrollY(scrollY - event.getDeltaY() * ROW_SCROLL_STEP);
				event.consume();
			}
			return;
		}
		panByMs(TimelineRangeMath.wheelPanMs(deltaPx, contentWidth(), range.duration()));
		event.consume();
	}

	/* ---------- pointer gestures (drag / resize / lane add) ---------- */

	private void handleMousePressed(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		canvas.requestFocus();
		pressAt(event.getX(), contentY(event.getY()));
	}

	/** Picks a block edge for resize, or starts a move; the TestFX harness uses this. */
	public void pressAt(double x, double y) {
		resetDrag();
		if (isOnPlayhead(x, y)) {
			// PlaybackCursor cap drag: scrub without changing the selection.
			scrubbing = true;
			redraw();
			return;
		}
		if (hasCaptions() && inCaptionStrip(y)) {
			// The caption strip overlays the filmstrip. A caption block selects/edits that cue;
			// empty strip space starts the add-caption gesture.
			TimelineSelection captionHit = captionHitAt(x, y);
			if (captionHit != null) {
				selectAllBlocksActive = false;
				String sourceId = captionSourceId(captionHit.id());
				setSelection(new TimelineSelection(TimelineSelection.CAPTION, sourceId));
				commands.select(new TimelineSelection(TimelineSelection.CAPTION, sourceId));
				CaptionTimeline.Fragment fragment = captionFragment(captionHit.id());
				if (fragment != null) {
					commands.seek(fragment.startMs());
					setCurrentTimeMs(fragment.startMs());
				}
				if (onCaptionSelected != null) {
					onCaptionSelected.accept(sourceId);
				}
				fireItemPressed();
				redraw();
				return;
			}
			pressedEmpty = true;
			dragStartX = x;
			return;
		}
		TimelineSelection hit = hitTest(x, y);
		if (hit == null) {
			if (inZoomRow(y)) {
				// The zoom lane adds a zoom on click instead of seeking (React stops propagation).
				pressedEmpty = true;
				dragStartX = x;
				return;
			}
			// handleTimelineMouseDown: clear the block selection, seek at once, then keep seeking
			// while the pointer drags (the preview follows in real time).
			selectAllBlocksActive = false;
			setSelection(null);
			commands.clearSelection();
			scrubbing = true;
			double time = scrubTimeAtX(x);
			setCurrentTimeMs(time);
			commands.seek(time);
			return;
		}
		TimelineRenderItem item = itemById(hit.id());
		BlockBounds bounds = blockBounds.get(hit.id());
		if (item == null || bounds == null) {
			pressedEmpty = true;
			dragStartX = x;
			return;
		}
		if (TimelineRows.isSourceAudioTrackRowId(item.rowId())) {
			// Source-audio blocks are display/inspector affordances: clicking selects the owning
			// clip (React renders them as disabled Items with onSelect={() => onSelectClip(item.id)}).
			selectAllBlocksActive = false;
			TimelineSelection clipSelection = new TimelineSelection(TimelineSelection.CLIP,
					sourceAudioOwnerClipId(item.rowId(), item.id()));
			setSelection(clipSelection);
			commands.select(clipSelection);
			fireItemPressed();
			redraw();
			return;
		}
		selectAllBlocksActive = false;
		setSelection(hit);
		commands.select(hit);
		fireItemPressed();
		dragItemId = hit.id();
		dragRowId = item.rowId();
		dragOriginalSpan = item.span();
		dragLiveSpan = item.span();
		dragStartX = x;
		double edge = Math.min(RESIZE_EDGE_PX, bounds.width() / 3);
		if (x <= bounds.minX() + edge) {
			dragMode = DragMode.RESIZE_LEFT;
		} else if (x >= bounds.maxX() - edge) {
			dragMode = DragMode.RESIZE_RIGHT;
		} else {
			dragMode = DragMode.MOVE;
		}
	}

	private void handleMouseDragged(MouseEvent event) {
		dragTo(event.getX(), contentY(event.getY()));
	}

	/** Updates the live preview during a drag; the TestFX harness uses this. */
	public void dragTo(double x, double y) {
		if (scrubbing) {
			double time = scrubTimeAtX(x);
			if (time != currentTimeMs) {
				setCurrentTimeMs(time);
				commands.scrub(time);
			}
			return;
		}
		if (dragMode == DragMode.NONE || dragItemId == null) {
			return;
		}
		TimelineRenderItem item = itemById(dragItemId);
		if (item == null) {
			return;
		}
		if (Math.abs(x - dragStartX) > DRAG_THRESHOLD_PX) {
			dragged = true;
		}
		double deltaMs = (x - dragStartX) * msPerPixel();
		Span proposed = switch (dragMode) {
			case MOVE -> new Span(dragOriginalSpan.start() + deltaMs, dragOriginalSpan.end() + deltaMs);
			case RESIZE_LEFT -> new Span(Math.min(dragOriginalSpan.end() - minItemDurationMs,
					dragOriginalSpan.start() + deltaMs), dragOriginalSpan.end());
			case RESIZE_RIGHT -> new Span(dragOriginalSpan.start(),
					Math.max(dragOriginalSpan.start() + minItemDurationMs,
							dragOriginalSpan.end() + deltaMs));
			case NONE -> dragOriginalSpan;
		};
		if (dragMode != DragMode.MOVE) {
			proposed = ClipPresentation.snapRegionSpan(proposed, snapTargets(dragItemId),
					clipPresentation, msPerPixel(),
					dragMode == DragMode.RESIZE_LEFT ? ClipPresentation.Edge.START
							: ClipPresentation.Edge.END);
			Span resolved = TimelineDndEngine.resolveResizeEnd(dragItemId, proposed, resizeConfig());
			if (resolved != null) {
				proposed = resolved;
			}
		} else {
			if (!TimelineConstants.CLIP_ROW_ID.equals(item.rowId())) {
				proposed = ClipPresentation.snapRegionSpan(proposed, snapTargets(dragItemId),
						clipPresentation, msPerPixel());
			}
			proposed = TimelineDndEngine.clampDraggedSpanToNeighbours(proposed, dragItemId,
					dragRowId, neighbourConfig()).span();
		}
		dragLiveSpan = proposed;
		redraw();
	}

	private void handleMouseReleased(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		releaseAt(event.getX(), contentY(event.getY()));
	}

	/** Commits the drag or treats the press as a click; the TestFX harness uses this. */
	public void releaseAt(double x, double y) {
		if (scrubbing) {
			// handleMouseUp flushes the last pointer position.
			double time = scrubTimeAtX(x);
			if (time != currentTimeMs) {
				setCurrentTimeMs(time);
				commands.scrub(time);
			}
			resetDrag();
			redraw();
			return;
		}
		if (dragMode != DragMode.NONE) {
			if (dragged && dragLiveSpan != null && dragItemId != null) {
				if (dragMode == DragMode.MOVE) {
					commands.move(dragItemId, dragRowId, dragLiveSpan);
				} else {
					commands.resizeEnd(dragItemId, dragLiveSpan);
				}
			}
			resetDrag();
			redraw();
			return;
		}
		boolean wasEmpty = pressedEmpty;
		resetDrag();
		if (wasEmpty) {
			selectAllBlocksActive = false;
			handleEmptyClick(x, y);
		}
	}

	private void resetDrag() {
		dragMode = DragMode.NONE;
		dragItemId = null;
		dragRowId = null;
		dragOriginalSpan = null;
		dragLiveSpan = null;
		dragged = false;
		pressedEmpty = false;
		scrubbing = false;
	}

	/** Timeline time under a pointer x, clamped and moved off clip seams (getTimeAtClipSeam). */
	private double scrubTimeAtX(double x) {
		double time = Math.max(0, Math.min(timeAtX(x), fullRange.end()));
		return Math.max(0, Math.min(ClipPresentation.getTimeAtClipSeam(time, clipPresentation),
				fullRange.end()));
	}

	private boolean inZoomRow(double y) {
		RowLayout zoomRow = rowById(TimelineConstants.ZOOM_ROW_ID);
		return zoomRow != null && y >= zoomRow.top() && y <= zoomRow.top() + zoomRow.height();
	}

	/**
	 * The playhead cap on the axis, or the line itself within a few pixels where no block sits
	 * under it (a block under the line keeps its own move/trim gesture).
	 */
	private boolean isOnPlayhead(double x, double y) {
		double playheadX = xForTime(currentTimeMs);
		if (playheadX < 0 || playheadX > contentWidth()) {
			return false;
		}
		double capHalfWidth = (playheadHovered ? PLAYHEAD_CAP_EXPANDED : PLAYHEAD_CAP_WIDTH) / 2;
		if (y <= AXIS_HEIGHT && Math.abs(x - playheadX) <= capHalfWidth) {
			return true;
		}
		return Math.abs(x - playheadX) <= PLAYHEAD_GRAB_PX && hitTest(x, y) == null;
	}

	private void handleEmptyClick(double x, double y) {
		if (inZoomRow(y)) {
			double displayTime = timeAtX(x);
			double start = ClipPresentation.getTimeAtClipSeam(
					Math.max(0, Math.min(displayTime, fullRange.end())), clipPresentation);
			start = Math.max(0, Math.min(start, fullRange.end()));
			double end = Math.min(fullRange.end(), start + Math.min(GHOST_ZOOM_MS, fullRange.end()));
			commands.addZoom(start, end);
			return;
		}
		if (hasCaptions() && inCaptionStrip(y)) {
			Span caption = captionSpanAt(x);
			if (caption != null) {
				commands.addCaption(caption.start(), caption.end());
				return;
			}
		}
		double time = ClipPresentation.getTimeAtClipSeam(timeAtX(x), clipPresentation);
		setSelection(null);
		commands.clearSelection();
		commands.seek(time);
		setCurrentTimeMs(time);
	}

	private void handleMouseMoved(MouseEvent event) {
		moveTo(event.getX(), contentY(event.getY()));
	}

	/** Updates the hover ghost and the resize cursor; the TestFX harness uses this. */
	public void moveTo(double x, double y) {
		hoverGhost = null;
		hoverGhostKind = null;
		boolean overPlayhead = isOnPlayhead(x, y);
		if (overPlayhead != playheadHovered) {
			playheadHovered = overPlayhead;
			redraw();
		}
		if (overPlayhead) {
			canvas.setCursor(Cursor.H_RESIZE);
			return;
		}
		if (hasCaptions() && inCaptionStrip(y)) {
			TimelineSelection captionHit = captionHitAt(x, y);
			if (captionHit != null) {
				canvas.setCursor(Cursor.HAND);
				redraw();
				return;
			}
			Span caption = captionSpanAt(x);
			if (caption != null) {
				hoverGhost = ClipPresentation.getRegionDisplaySpan(caption, clipPresentation);
				hoverGhostKind = "caption";
			}
			canvas.setCursor(Cursor.CROSSHAIR);
			redraw();
			return;
		}
		TimelineSelection hit = hitTest(x, y);
		Cursor cursor = Cursor.DEFAULT;
		if (hit != null) {
			BlockBounds bounds = blockBounds.get(hit.id());
			double edge = bounds == null ? RESIZE_EDGE_PX
					: Math.min(RESIZE_EDGE_PX, bounds.width() / 3);
			if (bounds != null && (x <= bounds.minX() + edge || x >= bounds.maxX() - edge)) {
				cursor = Cursor.H_RESIZE;
			} else {
				cursor = Cursor.HAND;
			}
		} else {
			RowLayout zoomRow = rowById(TimelineConstants.ZOOM_ROW_ID);
			if (zoomRow != null && y >= zoomRow.top() && y <= zoomRow.top() + zoomRow.height()) {
				double displayTime = timeAtX(x);
				double start = ClipPresentation.getTimeAtClipSeam(
						Math.max(0, Math.min(displayTime, fullRange.end())), clipPresentation);
				start = Math.max(0, Math.min(start, fullRange.end()));
				double end = Math.min(fullRange.end(),
						start + Math.min(GHOST_ZOOM_MS, fullRange.end()));
				hoverGhost = ClipPresentation.getRegionDisplaySpan(new Span(start, end),
						clipPresentation);
				hoverGhostKind = "zoom";
			}
		}
		canvas.setCursor(cursor);
		redraw();
	}

	private void handleKeyPressed(KeyEvent event) {
		keyAction(event.getCode(), event.isControlDown(), event.isMetaDown(), event.isShiftDown(),
				event.isAltDown(), event);
	}

	/** Dispatches the timeline keyboard shortcuts; the TestFX harness uses this. */
	public void pressKey(KeyCode code) {
		pressKey(code, false, false, false, false);
	}

	/** Dispatches one key with modifiers (the TestFX harness uses this). */
	public void pressKey(KeyCode code, boolean ctrl, boolean meta, boolean shift, boolean alt) {
		keyAction(code, ctrl, meta, shift, alt, null);
	}

	/** Applies the configured timeline shortcut bindings (from the shortcuts settings store). */
	public void setShortcutBindings(TimelineShortcuts.Bindings bindings) {
		this.shortcutBindings = bindings == null ? TimelineShortcuts.defaults() : bindings;
		redraw();
	}

	/** macOS uses Cmd as the primary modifier (port of {@code matchesShortcut} platform flag). */
	public void setMacPlatform(boolean mac) {
		this.macPlatform = mac;
	}

	private @Nullable TimelineSelection captionHitAt(double x, double y) {
		for (TimelineRenderItem item : items) {
			if (item.variant() != TimelineRenderItem.Variant.CAPTION) {
				continue;
			}
			BlockBounds bounds = blockBounds.get(item.id());
			if (bounds != null && bounds.contains(x, y)) {
				return new TimelineSelection(TimelineSelection.CAPTION, item.id());
			}
		}
		return null;
	}

	public boolean selectAllBlocksActive() {
		return selectAllBlocksActive;
	}

	/** Port of {@code activateSelectAllZooms}: Ctrl/Cmd+A selects every zoom block. */
	public void selectAllZooms() {
		if (!hasAnyZoomBlocks()) {
			return;
		}
		selectAllBlocksActive = true;
		setSelection(null);
		commands.clearSelection();
		redraw();
	}

	/** Builds the toolbar suggest-zooms result from the data source cursor telemetry. */
	public ZoomSuggestionUtils.SuggestionResult suggestZooms() {
		double total = fullRange.end();
		List<Span> reserved = new ArrayList<>();
		for (TimelineRenderItem item : items) {
			if (TimelineConstants.ZOOM_ROW_ID.equals(item.rowId())) {
				reserved.add(item.span());
			}
		}
		return ZoomSuggestionUtils.buildInteractionZoomSuggestions(
				new ZoomSuggestionUtils.SuggestionParams(dataSource.cursorTelemetry(), total,
						Math.min(1_000, total), reserved));
	}

	/** Applies the suggest-zooms result through {@link TimelineCommands#addSuggestedZoom}. */
	public int addSuggestedZooms() {
		ZoomSuggestionUtils.SuggestionResult result = suggestZooms();
		int added = 0;
		for (ZoomSuggestionUtils.SuggestedZoomRegion suggestion : result.suggestions()) {
			commands.addSuggestedZoom(suggestion.startMs(), suggestion.endMs(), suggestion.focus());
			added++;
		}
		return added;
	}

	private void keyAction(KeyCode code, boolean ctrl, boolean meta, boolean shift, boolean alt,
			@Nullable KeyEvent event) {
		boolean primary = macPlatform ? meta : ctrl;
		boolean deleteShortcut = TimelineShortcuts.matches(code, ctrl, meta, shift, alt,
				shortcutBindings.deleteSelected(), macPlatform)
				|| ((code == KeyCode.DELETE || code == KeyCode.BACK_SPACE) && !ctrl && !meta
						&& !alt && !shift);
		if (deleteShortcut) {
			if (selectAllBlocksActive) {
				for (TimelineRenderItem item : items) {
					if (TimelineConstants.ZOOM_ROW_ID.equals(item.rowId())) {
						commands.deleteZoom(item.id());
					}
				}
				selectAllBlocksActive = false;
			} else if (selection != null) {
				commands.deleteSelection();
			}
			consume(event);
			return;
		}
		if (code == KeyCode.ESCAPE) {
			selectAllBlocksActive = false;
			setSelection(null);
			commands.clearSelection();
			consume(event);
			return;
		}
		if (code == KeyCode.TAB) {
			if (cycleAnnotationsAtPlayhead(shift)) {
				consume(event);
			}
			return;
		}
		if (TimelineShortcuts.matches(code, ctrl, meta, shift, alt,
				TimelineShortcuts.Binding.of("a", true, false, false), macPlatform)) {
			selectAllZooms();
			consume(event);
			return;
		}
		if (TimelineShortcuts.matches(code, ctrl, meta, shift, alt, shortcutBindings.addZoom(),
				macPlatform)) {
			addZoomAtPlayhead();
			consume(event);
			return;
		}
		if (TimelineShortcuts.matches(code, ctrl, meta, shift, alt, shortcutBindings.splitClip(),
				macPlatform)) {
			TimelineRenderItem clip = clipAtPlayhead();
			if (clip != null) {
				commands.splitClip(clip.id(), currentTimeMs);
				consume(event);
			}
		}
	}

	private boolean hasAnyZoomBlocks() {
		for (TimelineRenderItem item : items) {
			if (TimelineConstants.ZOOM_ROW_ID.equals(item.rowId())) {
				return true;
			}
		}
		return false;
	}

	/** Port of {@code addZoomAtMs}: place a default-length zoom at the playhead if it fits. */
	public void addZoomAtPlayhead() {
		double total = fullRange.end();
		if (total <= 0) {
			return;
		}
		double defaultDuration = Math.min(1_000, total);
		if (defaultDuration <= 0) {
			return;
		}
		double startPos = Math.max(0, Math.min(currentTimeMs, total));
		Span activeClip = null;
		boolean hasClips = false;
		for (TimelineRenderItem item : items) {
			if (!TimelineConstants.CLIP_ROW_ID.equals(item.rowId())) {
				continue;
			}
			hasClips = true;
			if (startPos >= item.span().start() && startPos < item.span().end()) {
				activeClip = item.span();
				break;
			}
		}
		if (hasClips && activeClip == null) {
			return;
		}
		double clipEnd = activeClip == null ? total : activeClip.end();
		double nextZoomStart = total;
		boolean overlapping = false;
		for (TimelineRenderItem item : items) {
			if (!TimelineConstants.ZOOM_ROW_ID.equals(item.rowId())) {
				continue;
			}
			if (startPos >= item.span().start() && startPos < item.span().end()) {
				overlapping = true;
			}
			if (item.span().start() > startPos) {
				nextZoomStart = Math.min(nextZoomStart, item.span().start());
			}
		}
		double available = Math.min(clipEnd - startPos, nextZoomStart - startPos);
		if (overlapping || available < defaultDuration) {
			return;
		}
		commands.addZoom(startPos, startPos + defaultDuration);
	}

	/** Port of {@code cycleAnnotationsAtCurrentTime}. */
	private boolean cycleAnnotationsAtPlayhead(boolean backward) {
		List<TimelineRenderItem> overlapping = new ArrayList<>();
		for (TimelineRenderItem item : items) {
			if (TimelineConstants.ANNOTATION_ROW_ID.equals(item.rowId())
					&& currentTimeMs >= item.span().start() && currentTimeMs <= item.span().end()) {
				overlapping.add(item);
			}
		}
		if (overlapping.isEmpty()) {
			return false;
		}
		overlapping.sort(java.util.Comparator.comparingDouble(item -> item.span().start()));
		int currentIndex = -1;
		for (int index = 0; index < overlapping.size(); index++) {
			if (selection != null && overlapping.get(index).id().equals(selection.id())) {
				currentIndex = index;
				break;
			}
		}
		int nextIndex;
		if (currentIndex < 0) {
			nextIndex = 0;
		} else if (backward) {
			nextIndex = (currentIndex - 1 + overlapping.size()) % overlapping.size();
		} else {
			nextIndex = (currentIndex + 1) % overlapping.size();
		}
		TimelineSelection next = new TimelineSelection(TimelineSelection.ANNOTATION,
				overlapping.get(nextIndex).id());
		setSelection(next);
		commands.select(next);
		redraw();
		return true;
	}

	private static void consume(@Nullable KeyEvent event) {
		if (event != null) {
			event.consume();
		}
	}

	private @Nullable TimelineRenderItem clipAtPlayhead() {
		TimelineRenderItem selected = selection != null
				&& TimelineSelection.CLIP.equals(selection.kind()) ? itemById(selection.id()) : null;
		if (selected != null && currentTimeMs >= selected.span().start()
				&& currentTimeMs <= selected.span().end()
				&& currentTimeMs > selected.span().start()) {
			return selected;
		}
		for (TimelineRenderItem item : items) {
			if (TimelineConstants.CLIP_ROW_ID.equals(item.rowId())
					&& currentTimeMs >= item.span().start() && currentTimeMs < item.span().end()) {
				return item;
			}
		}
		return null;
	}

	private boolean hasCaptions() {
		ProjectEditorState project = dataSource.project();
		return project != null && project.autoCaptions() != null && !project.autoCaptions().isEmpty();
	}

	private boolean inCaptionStrip(double y) {
		RowLayout clipRow = rowById(TimelineConstants.CLIP_ROW_ID);
		return clipRow != null && y >= clipRow.top() + CAPTION_STRIP_TOP_OFFSET
				&& y <= clipRow.top() + CAPTION_STRIP_TOP_OFFSET + CAPTION_STRIP_HEIGHT;
	}

	/**
	 * Mounts the transient agent-timeline overlay (P3.2) above the Canvas and follows the
	 * controller's highlight list. Passing {@code null} unmounts it. Shell wiring:
	 * {@code timelineView.setAgentHighlights(shell.agentHighlights())}.
	 */
	public void setAgentHighlights(AgentHighlightsController controller) {
		if (agentController != null && agentHighlightsListener != null) {
			agentController.highlights().removeListener(agentHighlightsListener);
		}
		agentController = controller;
		agentHighlightsListener = null;
		if (controller == null) {
			if (agentOverlay != null) {
				agentOverlay.setHighlights(List.of());
			}
			return;
		}
		if (agentOverlay == null) {
			agentOverlay = new AgentTimelineHighlights();
			agentOverlay.setMouseTransparent(true);
			agentOverlay.setPickOnBounds(false);
			getChildren().add(agentOverlay);
		}
		agentOverlay.timeAxis(new AgentTimelineHighlights.TimeAxis() {
			@Override
			public double xForMs(double timeMs) {
				return xForTime(timeMs);
			}

			@Override
			public double widthForMs(double durationMs) {
				return msPerPixel() <= 0 ? 0 : durationMs / msPerPixel();
			}
		});
		agentOverlay.range((long) range.start(), (long) range.end());
		agentHighlightsListener = change -> {
			agentOverlay.range((long) range.start(), (long) range.end());
			agentOverlay.setHighlights(controller.highlights());
		};
		controller.highlights().addListener(agentHighlightsListener);
		agentOverlay.setHighlights(controller.highlights());
		requestLayout();
	}

	/** The mounted agent overlay, or null (tests/shell inspection). */
	public @Nullable AgentTimelineHighlights agentOverlay() {
		return agentOverlay;
	}

	/** Reports the source cue when a caption block is selected on the timeline. */
	public void setOnCaptionSelected(java.util.function.Consumer<String> listener) {
		this.onCaptionSelected = listener;
	}

	/** Fires when a press selects a timeline item (the shell closes the Clips panel, React parity). */
	public void onItemPressed(Runnable listener) {
		this.onItemPressed = listener;
	}

	private void fireItemPressed() {
		if (onItemPressed != null) {
			onItemPressed.run();
		}
	}

	/** Bounds of the caption block for one source cue (first clip it appears on). */
	public java.util.Optional<BlockBounds> captionBounds(String sourceCueId) {
		for (CaptionTimeline.Fragment fragment : captionFragments) {
			if (fragment.sourceCueId().equals(sourceCueId)) {
				BlockBounds bounds = blockBounds.get(fragment.id());
				if (bounds != null) {
					return java.util.Optional.of(bounds);
				}
			}
		}
		return java.util.Optional.empty();
	}

	/** Selects a source cue and seeks to its first timeline fragment (caption list -> timeline). */
	public void selectCaption(String sourceCueId) {
		for (CaptionTimeline.Fragment fragment : captionFragments) {
			if (!fragment.sourceCueId().equals(sourceCueId)) {
				continue;
			}
			selectAllBlocksActive = false;
			setSelection(new TimelineSelection(TimelineSelection.CAPTION, sourceCueId));
			commands.select(new TimelineSelection(TimelineSelection.CAPTION, sourceCueId));
			commands.seek(fragment.startMs());
			setCurrentTimeMs(fragment.startMs());
			redraw();
			return;
		}
	}

	private String captionSourceId(String fragmentId) {
		for (CaptionTimeline.Fragment fragment : captionFragments) {
			if (fragment.id().equals(fragmentId)) {
				return fragment.sourceCueId();
			}
		}
		return fragmentId;
	}

	private CaptionTimeline.@Nullable Fragment captionFragment(String fragmentId) {
		for (CaptionTimeline.Fragment fragment : captionFragments) {
			if (fragment.id().equals(fragmentId)) {
				return fragment;
			}
		}
		return null;
	}

	public @Nullable Span hoverGhostSpan() {
		return hoverGhost;
	}

	/** "zoom" or "caption" while an add ghost is drawn, else null. */
	public @Nullable String hoverGhostKind() {
		return hoverGhostKind;
	}

	/** Top of one row in view-local pixels, or -1 when the row does not exist. */
	public double rowTop(String rowId) {
		RowLayout row = rowById(rowId);
		return row == null ? -1 : row.top();
	}

	/** Height of one row in view-local pixels, or -1 when the row does not exist. */
	public @Nullable double rowHeight(String rowId) {
		RowLayout row = rowById(rowId);
		return row == null ? -1 : row.height();
	}

	/** The media span a caption added at this x would occupy (clamped inside one clip). */
	public @Nullable Span captionSpanAt(double x) {
		Span embedded = ClipPresentation.getEmbeddedCaptionSpan(
				new Span(timeAtX(x), timeAtX(x) + CAPTION_GHOST_MS), clipPresentation);
		if (embedded == null) {
			return null;
		}
		double start = ClipPresentation.getTimeAtClipSeam(embedded.start(), clipPresentation);
		double end = Math.min(fullRange.end(), start + CAPTION_GHOST_MS);
		return end > start ? new Span(start, end) : null;
	}

	private TimelineDndEngine.NeighbourConfig neighbourConfig() {
		return new TimelineDndEngine.NeighbourConfig(regionSpans(), minItemDurationMs,
				fullRange.end());
	}

	private TimelineDndEngine.ResizeConfig resizeConfig() {
		return new TimelineDndEngine.ResizeConfig(fullRange.end(), minItemDurationMs, regionSpans(),
				this::hasOverlap);
	}

	private List<TimelineRegionSpan> regionSpans() {
		List<TimelineRegionSpan> spans = new ArrayList<>();
		for (TimelineRenderItem item : items) {
			spans.add(new TimelineRegionSpan(item.id(), item.span().start(), item.span().end(),
					item.rowId()));
		}
		return spans;
	}

	private boolean hasOverlap(Span span, String excludeId, @Nullable String rowId) {
		for (TimelineRenderItem item : items) {
			if (item.id().equals(excludeId)) {
				continue;
			}
			if (rowId != null && !rowId.equals(item.rowId())) {
				continue;
			}
			if (item.span().end() > span.start() && item.span().start() < span.end()) {
				return true;
			}
		}
		return false;
	}

	private List<Double> snapTargets(String excludeId) {
		java.util.LinkedHashSet<Double> targets = new java.util.LinkedHashSet<>();
		targets.add(0.0);
		targets.add(fullRange.end());
		for (TimelineRenderItem item : items) {
			if (item.id().equals(excludeId)) {
				continue;
			}
			targets.add(item.span().start());
			targets.add(item.span().end());
		}
		return List.copyOf(targets);
	}

	private @Nullable RowLayout rowById(@Nullable String rowId) {
		for (RowLayout row : rowLayouts) {
			if (row.id().equals(rowId)) {
				return row;
			}
		}
		return null;
	}

	private @Nullable TimelineRenderItem itemById(String itemId) {
		for (TimelineRenderItem item : items) {
			if (item.id().equals(itemId)) {
				return item;
			}
		}
		return null;
	}

	private void drawInteractionOverlays(GraphicsContext graphics) {
		if (dragMode != DragMode.NONE && dragLiveSpan != null) {
			double startX = xForTime(dragLiveSpan.start());
			double width = Math.max(4, xForTime(dragLiveSpan.end()) - startX);
			RowLayout row = rowById(dragRowId);
			double top = row == null ? AXIS_HEIGHT : row.top() + 2;
			double height = row == null ? 24 : row.height() - 4;
			graphics.setFill(Color.web("#2fb3a3", 0.25));
			graphics.fillRoundRect(startX, top, width, height, 8, 8);
			graphics.setStroke(Color.web("#3fd4c1", 0.8));
			graphics.setLineWidth(1.5);
			graphics.setLineDashes(5, 4);
			graphics.strokeRoundRect(startX + 0.5, top + 0.5, Math.max(1, width - 1), height - 1, 8,
					8);
			graphics.setLineDashes(null);
		}
		if (hoverGhost != null) {
			double startX = xForTime(hoverGhost.start());
			double width = Math.max(4, xForTime(hoverGhost.end()) - startX);
			double top;
			double height;
			if ("caption".equals(hoverGhostKind)) {
				RowLayout clipRow = rowById(TimelineConstants.CLIP_ROW_ID);
				top = (clipRow == null ? AXIS_HEIGHT : clipRow.top()) + CAPTION_STRIP_TOP_OFFSET;
				height = CAPTION_STRIP_HEIGHT;
			} else {
				RowLayout zoomRow = rowById(TimelineConstants.ZOOM_ROW_ID);
				top = zoomRow == null ? AXIS_HEIGHT : zoomRow.top() + zoomRow.height() * 0.1;
				height = zoomRow == null ? 24 : zoomRow.height() * 0.8;
			}
			graphics.setFill(Color.web("#2fb3a3", 0.18));
			graphics.fillRoundRect(startX, top, width, height, 8, 8);
			graphics.setStroke(Color.web("#3fd4c1", 0.7));
			graphics.setLineWidth(1);
			graphics.setLineDashes(4, 4);
			graphics.strokeRoundRect(startX + 0.5, top + 0.5, Math.max(1, width - 1), height - 1, 8,
					8);
			graphics.setLineDashes(null);
			double centerX = startX + width / 2;
			double centerY = top + height / 2;
			graphics.setFill(Color.web("#2fb3a3", 0.9));
			graphics.fillOval(centerX - 9, centerY - 9, 18, 18);
			graphics.setFont(Font.font("DM Sans", FontWeight.BOLD, 13));
			graphics.setFill(Color.WHITE);
			graphics.fillText("+", centerX - 4.5, centerY + 5);
		}
	}

	/** Simulates a primary click at a view-local point (press + release; click semantics). */
	public void clickAt(double x, double y) {
		pressAt(x, y);
		releaseAt(x, y);
	}

	/** Which block is under a point, if any (top-most rows win). */
	public @Nullable TimelineSelection hitTest(double x, double y) {
		for (TimelineRenderItem item : items) {
			BlockBounds bounds = blockBounds.get(item.id());
			if (bounds != null && bounds.contains(x, y)) {
				return new TimelineSelection(kindFor(item.variant()), item.id());
			}
		}
		return null;
	}

	private static String kindFor(TimelineRenderItem.Variant variant) {
		return switch (variant) {
			case ZOOM -> TimelineSelection.ZOOM;
			case CLIP -> TimelineSelection.CLIP;
			case ANNOTATION -> TimelineSelection.ANNOTATION;
			case AUDIO -> TimelineSelection.AUDIO;
			case CAPTION -> TimelineSelection.CAPTION;
			case TRIM, SPEED -> TimelineSelection.CLIP;
		};
	}

	/* ---------- painting ---------- */

	private void redraw() {
		GraphicsContext graphics = canvas.getGraphicsContext2D();
		double width = contentWidth();
		double height = canvas.getHeight();
		graphics.setFill(Color.web("#121418"));
		graphics.fillRect(0, 0, width, height);
		blockBounds.clear();
		if (agentOverlay != null) {
			agentOverlay.range((long) range.start(), (long) range.end());
		}
		if (items.isEmpty() && dataSource.project() == null) {
			return;
		}
		syncScrollBars();
		drawGrid(graphics, width, height);
		drawRuler(graphics, width);
		// Lanes scroll under the pinned ruler: clip below the axis and shift by scrollY.
		graphics.save();
		graphics.beginPath();
		graphics.rect(0, AXIS_HEIGHT, width, Math.max(0, height - AXIS_HEIGHT));
		graphics.clip();
		graphics.translate(0, -scrollY);
		drawRows(graphics, width);
		drawInteractionOverlays(graphics);
		drawRowLabels(graphics);
		graphics.restore();
		drawAgentHighlights(graphics, width);
		drawPlayhead(graphics, height);
	}

	private void drawGrid(GraphicsContext graphics, double width, double height) {
		TimelineScale.AxisScale axis = TimelineScale.calculateAxisScale(range.duration(), width);
		graphics.setStroke(GRID_LINE);
		graphics.setLineWidth(1);
		double first = Math.floor(range.start() / Math.max(1, axis.gridMs())) * axis.gridMs();
		for (double time = first; time <= range.end(); time += axis.gridMs()) {
			double x = Math.round(xForTime(time)) + 0.5;
			if (x >= 0 && x <= width) {
				graphics.strokeLine(x, AXIS_HEIGHT, x, height);
			}
		}
	}

	private void drawRuler(GraphicsContext graphics, double width) {
		TimelineScale.AxisScale axis = TimelineScale.calculateAxisScale(range.duration(), width);
		graphics.setFont(AXIS_FONT);
		graphics.setFill(Color.web("#8b929a"));
		graphics.setStroke(Color.web("#8b929a", 0.45));
		graphics.setLineWidth(1);
		double first = Math.max(0,
				Math.floor(range.start() / Math.max(1, axis.intervalMs())) * axis.intervalMs());
		for (double time = first; time <= range.end(); time += axis.intervalMs()) {
			double x = Math.round(xForTime(time)) + 0.5;
			if (x < 0 || x > width) {
				continue;
			}
			graphics.strokeLine(x, AXIS_HEIGHT - 6, x, AXIS_HEIGHT);
			graphics.fillText(TimelineScale.formatTimeLabel(time, axis.intervalMs()), x + 4, 13);
		}
	}

	private void drawRows(GraphicsContext graphics, double width) {
		ProjectEditorState project = dataSource.project();
		for (RowLayout row : rowLayouts) {
			if (TimelineConstants.CLIP_ROW_ID.equals(row.id())) {
				drawClips(graphics, row, width);
				// Trim/speed indicators are drawn over the clips: they must stay visible (plan §1.2.8
				// and the lead's row/indicator checklist).
				drawSpeedBands(graphics, project.speedRegions(), row, width);
				drawTrimGaps(graphics, project.trimRegions(), row, width);
				drawCaptions(graphics, row, width);
			} else if (TimelineConstants.ZOOM_ROW_ID.equals(row.id())) {
				for (TimelineRenderItem item : items) {
					if (TimelineConstants.ZOOM_ROW_ID.equals(item.rowId())) {
						drawZoom(graphics, item, row, width);
					}
				}
			} else if (TimelineRows.isAnnotationTrackRowId(row.id())) {
				for (TimelineRenderItem item : items) {
					if (row.id().equals(item.rowId())) {
						drawAnnotation(graphics, item, row, width);
					}
				}
			} else if (TimelineRows.isAudioTrackRowId(row.id())) {
				for (TimelineRenderItem item : items) {
					if (row.id().equals(item.rowId())) {
						drawAudio(graphics, item, row, width);
					}
				}
			} else if (TimelineRows.isSourceAudioTrackRowId(row.id())) {
				for (TimelineRenderItem item : items) {
					if (row.id().equals(item.rowId())) {
						drawSourceAudio(graphics, item, row, width);
					}
				}
			}
		}
	}

	private void drawClips(GraphicsContext graphics, RowLayout row, double width) {
		double blockHeight = row.height() * 0.85;
		double blockTop = row.top() + (row.height() - blockHeight) / 2;
		List<TimelineRenderItem> clips = items.stream()
				.filter(item -> TimelineConstants.CLIP_ROW_ID.equals(item.rowId()))
				.toList();
		for (int index = 0; index < clips.size(); index++) {
			TimelineRenderItem item = clips.get(index);
			Span display = displaySpanFor(item);
			double startX = xForTime(display.start());
			double endX = xForTime(display.end());
			double blockWidth = Math.max(6, endX - startX);
			if (endX < 0 || startX > width) {
				continue;
			}
			double x = Math.max(-blockWidth, startX);
			graphics.setFill(CLIP_FILL);
			graphics.fillRoundRect(x, blockTop, blockWidth, blockHeight, 8, 8);
			drawFilmstrip(graphics, item, x, blockTop, blockWidth, blockHeight);
			drawFade(graphics, item, x, blockTop, blockWidth, blockHeight);
			graphics.setStroke(CLIP_BORDER);
			graphics.setLineWidth(1);
			graphics.strokeRoundRect(x + 0.5, blockTop + 0.5, Math.max(1, blockWidth - 1),
					blockHeight - 1, 8, 8);
			drawClipIndicators(graphics, item, x, blockTop, blockWidth, blockHeight);
			if (isSelected(TimelineSelection.CLIP, item.id())) {
				drawSelection(graphics, x, blockTop, blockWidth, blockHeight, CLIP_SELECTED);
			}
			blockBounds.put(item.id(), new BlockBounds(x, blockTop, blockWidth, blockHeight));
		}
		for (int index = 0; index + 1 < clips.size(); index++) {
			Span left = displaySpanFor(clips.get(index));
			Span right = displaySpanFor(clips.get(index + 1));
			if (left.end() == right.start()) {
				double seam = (xForTime(left.end()) + xForTime(right.start())) / 2;
				graphics.setFill(Color.web("#ffffff", 0.88));
				graphics.fillRoundRect(seam - 2, blockTop + blockHeight * 0.2, 4,
						blockHeight * 0.6, 2, 2);
			}
		}
	}

	private void drawFilmstrip(GraphicsContext graphics, TimelineRenderItem item, double x,
			double y, double width, double height) {
		double cellHeight = Math.max(18, height - 6);
		double cellWidth = Math.max(24, cellHeight * 16.0 / 9.0);
		// Filmstrip frames are composited at timeline times (the source time is implied by the
		// clip speed), so the samples run across the clip's timeline span.
		Span timelineSpan = item.span();
		double spanDuration = Math.max(1, timelineSpan.duration());
		double quantum = Math.max(1, filmstripProvider.quantumMs());
		List<Double> samples = new ArrayList<>();
		int drawn = 0;
		double clipRight = x + width;
		for (double cellX = x; cellX < clipRight; cellX += cellWidth + 2) {
			double cellDrawWidth = Math.min(cellWidth, clipRight - cellX);
			if (cellDrawWidth < 6) {
				break;
			}
			double center = (cellX - x + cellDrawWidth / 2) / Math.max(1, width);
			double quantized = Math.round((timelineSpan.start() + center * spanDuration) / quantum)
					* quantum;
			samples.add(quantized);
			Image frame = filmstripFrame(item.id(), quantized);
			if (frame != null) {
				graphics.drawImage(frame, cellX + 1, y + 3, Math.max(1, cellDrawWidth - 2),
						cellHeight);
				drawn++;
			} else {
				graphics.setFill(Color.web("#ffffff", 0.035));
				graphics.fillRect(cellX + 1, y + 3, Math.max(1, cellDrawWidth - 2), cellHeight);
			}
		}
		filmstripSamples.put(item.id(), List.copyOf(samples));
		filmstripFrameCounts.put(item.id(), drawn);
	}

	private @Nullable Image filmstripFrame(String clipId, double sourceTimeMs) {
		FilmstripKey key = new FilmstripKey(clipId, sourceTimeMs, FILMSTRIP_THUMB_WIDTH,
				FILMSTRIP_THUMB_HEIGHT);
		Image cached = filmstripCache.get(key);
		if (cached != null) {
			return cached;
		}
		Image frame = filmstripProvider.frameAt(clipId, sourceTimeMs, FILMSTRIP_THUMB_WIDTH,
				FILMSTRIP_THUMB_HEIGHT);
		if (frame == null) {
			filmstripProvider.requestFrame(clipId, sourceTimeMs, FILMSTRIP_THUMB_WIDTH,
					FILMSTRIP_THUMB_HEIGHT);
			return null;
		}
		filmstripCache.put(key, frame);
		return frame;
	}

	/** Source-time samples requested during the last filmstrip draw (tests/shell inspection). */
	public List<Double> filmstripSampleTimes(String clipId) {
		return filmstripSamples.getOrDefault(clipId, List.of());
	}

	/** Number of filmstrip cells that had a cached thumbnail in the last draw. */
	public int filmstripFrameCount(String clipId) {
		return filmstripFrameCounts.getOrDefault(clipId, 0);
	}

	/** The active filmstrip provider (tests/shell inspection). */
	public FilmstripProvider filmstripProvider() {
		return filmstripProvider;
	}

	private void drawFilmstripPlaceholder(GraphicsContext graphics, double x, double y,
			double width, double height) {
		// Filmstrip frames arrive with the media layer; the Phase A block keeps the filmstrip look
		// (alternating cell tint) so the row is identifiable without decoding media.
		graphics.setFill(Color.web("#ffffff", 0.035));
		for (double cell = 0; cell < width; cell += 42) {
			graphics.fillRect(x + cell + 1, y + 3, 40, height - 6);
		}
	}

	private void drawFade(GraphicsContext graphics, TimelineRenderItem item, double x, double y,
			double width, double height) {
		double spanDurationMs = Math.max(1, item.span().duration());
		if (item.fadeInMs() != null && item.fadeInMs() > 0) {
			double fadeWidth = Math.max(6,
					Math.min(width, width * (item.fadeInMs() / spanDurationMs)));
			graphics.setFill(Color.web("#000000", 0.55));
			graphics.fillRoundRect(x, y, fadeWidth, height, 8, 8);
			graphics.setStroke(Color.web("#ffffff", 0.9));
			graphics.setLineWidth(1.5);
			graphics.strokeLine(x + 1, y + height - 2, x + fadeWidth - 1, y + 2);
		}
		if (item.fadeOutMs() != null && item.fadeOutMs() > 0) {
			double fadeWidth = Math.max(6,
					Math.min(width, width * (item.fadeOutMs() / spanDurationMs)));
			graphics.setFill(Color.web("#000000", 0.55));
			graphics.fillRoundRect(x + width - fadeWidth, y, fadeWidth, height, 8, 8);
			graphics.setStroke(Color.web("#ffffff", 0.9));
			graphics.setLineWidth(1.5);
			graphics.strokeLine(x + width - fadeWidth + 1, y + 2, x + width - 1, y + height - 2);
		}
	}

	private void drawClipIndicators(GraphicsContext graphics, TimelineRenderItem item, double x,
			double y, double width, double height) {
		String speedLabel = item.speedValue() == null ? null
				: dev.gravaai.core.project.ClipSpeedChanges.formatClipSpeedLabel(item.speedValue());
		if (speedLabel != null) {
			String text = speedLabel;
			double textWidth = textWidth(text, BLOCK_FONT) + 12;
			if (width > textWidth + 8) {
				graphics.setFill(BADGE_FILL);
				graphics.fillRoundRect(x + width / 2 - textWidth / 2, y + height / 2 - 9, textWidth, 18,
						6, 6);
				graphics.setFont(BLOCK_FONT);
				graphics.setFill(BLOCK_TEXT);
				graphics.fillText(text, x + width / 2 - textWidth / 2 + 6, y + height / 2 + 4);
			}
		}
		boolean sourceBadge = Boolean.TRUE.equals(item.showSourceAudio())
				|| Boolean.TRUE.equals(item.muted());
		if (sourceBadge) {
			String label = Boolean.TRUE.equals(item.muted()) ? "MUTED" : "SRC";
			double badgeWidth = textWidth(label, SMALL_FONT) + 10;
			if (width > badgeWidth + 16) {
				graphics.setFill(BADGE_FILL);
				graphics.fillRoundRect(x + width - badgeWidth - 8, y + height - 19, badgeWidth, 14,
						5, 5);
				graphics.setFont(SMALL_FONT);
				graphics.setFill(Color.WHITE);
				graphics.fillText(label, x + width - badgeWidth - 3, y + height - 8);
			}
		}
		if (item.cameraLayout() != null) {
			String label = item.cameraLayout()
					== dev.gravaai.core.project.ClipCameraLayout.CAMERA_ONLY
							? "Camera only" : "No camera";
			double labelWidth = textWidth(label, SMALL_FONT) + 12;
			if (width > labelWidth + 24) {
				graphics.setFill(BADGE_FILL);
				graphics.fillRoundRect(x + 8, y + 4, labelWidth, 16, 8, 8);
				graphics.setFont(SMALL_FONT);
				graphics.setFill(Color.WHITE);
				graphics.fillText(label, x + 14, y + 16);
			}
		}
	}

	private void drawCaptions(GraphicsContext graphics, RowLayout clipRow, double width) {
		double captionTop = clipRow.top() + 36;
		double captionHeight = 20;
		List<ClipPresentation.Presentation> clips = clipPresentation;
		for (TimelineRenderItem item : items) {
			if (item.variant() != TimelineRenderItem.Variant.CAPTION) {
				continue;
			}
			// Caption items are already projected onto the edited timeline (CaptionTimeline),
			// so their span is the display span inside the clip row.
			Span embedded = item.span();
			if (embedded == null) {
				continue;
			}
			double startX = xForTime(embedded.start());
			double endX = xForTime(embedded.end());
			double blockWidth = Math.max(6, endX - startX);
			if (endX < 0 || startX > width) {
				continue;
			}
			double x = Math.max(-blockWidth, startX);
			graphics.setFill(CAPTION_FILL);
			graphics.fillRoundRect(x, captionTop, blockWidth, captionHeight, 4, 4);
			graphics.setStroke(CAPTION_BORDER);
			graphics.setLineWidth(1);
			graphics.strokeRoundRect(x + 0.5, captionTop + 0.5, Math.max(1, blockWidth - 1),
					captionHeight - 1, 4, 4);
			if (blockWidth > 30) {
				graphics.setFont(SMALL_FONT);
				graphics.setFill(BLOCK_TEXT);
				graphics.fillText(fitText(item.label(), blockWidth - 8, SMALL_FONT), x + 4,
						captionTop + 14);
			}
			if (isSelected(TimelineSelection.CAPTION, captionSourceId(item.id()))) {
				drawSelection(graphics, x, captionTop, blockWidth, captionHeight, CAPTION_SELECTED);
			}
			blockBounds.put(item.id(), new BlockBounds(x, captionTop, blockWidth, captionHeight));
		}
	}

	private void drawZoom(GraphicsContext graphics, TimelineRenderItem item, RowLayout row,
			double width) {
		Span display = getRegionDisplaySpan(item.span());
		double startX = xForTime(display.start());
		double endX = xForTime(display.end());
		double blockWidth = Math.max(6, endX - startX);
		if (endX < 0 || startX > width) {
			return;
		}
		double x = Math.max(-blockWidth, startX);
		double height = row.height() * 0.85;
		double y = row.top() + (row.height() - height) / 2;
		graphics.setFill(ZOOM_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 8, 8);
		graphics.setStroke(ZOOM_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 8, 8);
		String label = zoomLabel(item);
		if (blockWidth > textWidth(label, BLOCK_FONT) + 8) {
			graphics.setFont(BLOCK_FONT);
			graphics.setFill(Color.WHITE);
			graphics.fillText(label, x + 6, y + height / 2 + 4);
		}
		if (isSelected(TimelineSelection.ZOOM, item.id())) {
			drawSelection(graphics, x, y, blockWidth, height, ZOOM_BORDER);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
	}

	private void drawAnnotation(GraphicsContext graphics, TimelineRenderItem item, RowLayout row,
			double width) {
		Span display = getRegionDisplaySpan(item.span());
		double startX = xForTime(display.start());
		double endX = xForTime(display.end());
		double blockWidth = Math.max(6, endX - startX);
		if (endX < 0 || startX > width) {
			return;
		}
		double x = Math.max(-blockWidth, startX);
		double height = row.height() * 0.85;
		double y = row.top() + (row.height() - height) / 2;
		graphics.setFill(ANNOTATION_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 8, 8);
		graphics.setStroke(ANNOTATION_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 8, 8);
		if (blockWidth > 24) {
			graphics.setFont(BLOCK_FONT);
			graphics.setFill(BLOCK_TEXT);
			graphics.fillText(fitText(item.label(), blockWidth - 8, BLOCK_FONT), x + 4,
					y + height / 2 + 4);
		}
		if (isSelected(TimelineSelection.ANNOTATION, item.id())) {
			drawSelection(graphics, x, y, blockWidth, height, ANNOTATION_SELECTED);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
	}

	private void drawAudio(GraphicsContext graphics, TimelineRenderItem item, RowLayout row,
			double width) {
		Span display = getRegionDisplaySpan(item.span());
		double startX = xForTime(display.start());
		double endX = xForTime(display.end());
		double blockWidth = Math.max(6, endX - startX);
		if (endX < 0 || startX > width) {
			return;
		}
		double x = Math.max(-blockWidth, startX);
		double height = row.height() * 0.85;
		double y = row.top() + (row.height() - height) / 2;
		graphics.setFill(AUDIO_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 8, 8);
		drawWaveform(graphics, item, x, y, blockWidth, height);
		graphics.setStroke(AUDIO_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 8, 8);
		if (blockWidth > 30) {
			graphics.setFont(SMALL_FONT);
			graphics.setFill(BLOCK_TEXT);
			graphics.fillText(fitText(item.label(), blockWidth - 8, SMALL_FONT), x + 4,
					y + height / 2 + 4);
		}
		if (isSelected(TimelineSelection.AUDIO, item.id())) {
			drawSelection(graphics, x, y, blockWidth, height, AUDIO_SELECTED);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
	}

	private void drawSourceAudio(GraphicsContext graphics, TimelineRenderItem item, RowLayout row,
			double width) {
		Span display = item.span();
		double startX = xForTime(display.start());
		double endX = xForTime(display.end());
		double blockWidth = Math.max(6, endX - startX);
		if (endX < 0 || startX > width) {
			return;
		}
		double x = Math.max(-blockWidth, startX);
		double height = row.height() * 0.85;
		double y = row.top() + (row.height() - height) / 2;
		graphics.setFill(SOURCE_AUDIO_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 8, 8);
		drawWaveform(graphics, item, x, y, blockWidth, height);
		graphics.setStroke(AUDIO_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 8, 8);
		if (blockWidth > 34) {
			graphics.setFont(SMALL_FONT);
			graphics.setFill(BLOCK_TEXT);
			graphics.fillText(fitText(item.label(), blockWidth - 8, SMALL_FONT), x + 4,
					y + height / 2 + 4);
		}
		double volume = item.audioGain() == null ? 1 : item.audioGain();
		String badge = String.format(java.util.Locale.ROOT, "%.1fx", volume);
		if (Boolean.TRUE.equals(item.audioNormalize())) {
			badge += " N";
		}
		if (Boolean.TRUE.equals(item.muted())) {
			badge += " MUTED";
		}
		double badgeWidth = textWidth(badge, SMALL_FONT) + 10;
		if (blockWidth > badgeWidth + 8) {
			graphics.setFill(BADGE_FILL);
			graphics.fillRoundRect(x + blockWidth - badgeWidth - 4, y + height - 15, badgeWidth,
					13, 5, 5);
			graphics.setFont(SMALL_FONT);
			graphics.setFill(Color.WHITE);
			graphics.fillText(badge, x + blockWidth - badgeWidth + 1, y + height - 5);
		}
		if (isSelected(TimelineSelection.CLIP,
				sourceAudioOwnerClipId(item.rowId(), item.id()))) {
			drawSelection(graphics, x, y, blockWidth, height, CLIP_SELECTED);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
	}

	private java.util.Optional<TimelineSourceAudioTrack> sourceAudioTrackFor(String rowId) {
		String trackId = TimelineRows.getSourceAudioTrackId(rowId);
		for (TimelineSourceAudioTrack track : sourceAudioTracks) {
			if (track.id().equals(trackId)) {
				return java.util.Optional.of(track);
			}
		}
		return java.util.Optional.empty();
	}

	private String sourceAudioOwnerClipId(String rowId, String itemId) {
		String trackId = TimelineRows.getSourceAudioTrackId(rowId);
		String prefix = "source-audio-" + trackId + "-";
		return itemId.startsWith(prefix) ? itemId.substring(prefix.length()) : itemId;
	}

	/** Render items (source-audio lane/volume settings inspection for tests and shells). */
	public List<TimelineRenderItem> renderItems() {
		return items;
	}

	private void drawWaveform(GraphicsContext graphics, TimelineRenderItem item, double x,
			double y, double width, double height) {
		if (item.audioPath() == null) {
			return;
		}
		float[] peaks = dataSource.waveforms().peaks(item.audioPath(), WAVEFORM_BARS);
		if (peaks == null || peaks.length == 0) {
			return;
		}
		double gain = item.audioGain() == null ? 1 : Math.max(0, Math.min(1, item.audioGain()));
		graphics.setFill(Color.web("#ffffff", 0.45));
		double centerY = y + height / 2;
		double maxAmplitude = height * 0.35;
		int bars = Math.min(peaks.length, Math.max(1, (int) Math.floor(width / 3)));
		for (int bar = 0; bar < bars; bar++) {
			double ratio = peaks[(int) ((long) bar * peaks.length / bars)];
			double amplitude = Math.max(0, Math.min(1, ratio)) * gain * maxAmplitude;
			double barX = x + 2 + (width - 4) * bar / bars;
			graphics.fillRect(barX, centerY - amplitude, Math.max(1, (width - 4) / bars - 1),
					amplitude * 2);
		}
	}

	private void drawTrimGaps(GraphicsContext graphics, List<TrimRegion> trims, RowLayout row,
			double width) {
		if (trims == null || trims.isEmpty()) {
			return;
		}
		double y = row.top();
		double height = row.height();
		for (TrimRegion trim : trims) {
			double startX = xForTime(trim.startMs());
			double endX = xForTime(trim.endMs());
			if (endX < 0 || startX > width || endX <= startX) {
				continue;
			}
			double x = Math.max(0, startX);
			double trimWidth = Math.min(width, endX) - x;
			graphics.setFill(TRIM_FILL);
			graphics.fillRect(x, y, trimWidth, height);
			graphics.setStroke(TRIM_BORDER);
			graphics.setLineWidth(1);
			graphics.strokeRect(x + 0.5, y + 0.5, Math.max(1, trimWidth - 1), height - 1);
			graphics.setFont(SMALL_FONT);
			graphics.setFill(Color.web("#ffffff", 0.9));
			double seconds = (trim.endMs() - trim.startMs()) / 1000.0;
			graphics.fillText(String.format(java.util.Locale.ROOT, "%.1fs", seconds), x + 4,
					y + height - 6);
		}
	}

	private void drawSpeedBands(GraphicsContext graphics, List<SpeedRegion> speeds, RowLayout row,
			double width) {
		if (speeds == null || speeds.isEmpty()) {
			return;
		}
		double y = row.top() + row.height() * 0.18;
		double height = row.height() * 0.64;
		for (SpeedRegion speed : speeds) {
			double startX = xForTime(speed.startMs());
			double endX = xForTime(speed.endMs());
			if (endX < 0 || startX > width || endX <= startX) {
				continue;
			}
			double x = Math.max(0, startX);
			double bandWidth = Math.min(width, endX) - x;
			graphics.setFill(SPEED_FILL);
			graphics.fillRoundRect(x, y, bandWidth, height, 6, 6);
			graphics.setStroke(SPEED_BORDER);
			graphics.setLineWidth(1);
			graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, bandWidth - 1), height - 1, 6,
					6);
			if (bandWidth > 34) {
				graphics.setFont(SMALL_FONT);
				graphics.setFill(Color.web("#ffe9c2"));
				graphics.fillText(String.format(java.util.Locale.ROOT, "%.2fx", speed.speed()),
						x + 4, y + height / 2 + 3);
			}
		}
	}

	private void drawRowLabels(GraphicsContext graphics) {
		graphics.setFont(ROW_LABEL_FONT);
		graphics.setFill(ROW_LABEL);
		for (RowLayout row : rowLayouts) {
			String label = switch (row.id()) {
				case TimelineConstants.CLIP_ROW_ID -> "CLIP";
				case TimelineConstants.ZOOM_ROW_ID -> "ZOOM";
				case TimelineConstants.CAPTION_ROW_ID -> "CAPTION";
				default -> {
					if (TimelineRows.isSourceAudioTrackRowId(row.id())) {
						yield sourceAudioTrackFor(row.id())
								.map(track -> track.label().toUpperCase(java.util.Locale.ROOT))
								.orElse("SOURCE");
					}
					if (TimelineRows.isAnnotationTrackRowId(row.id())) {
						yield "NOTE";
					}
					if (TimelineRows.isAudioTrackRowId(row.id())) {
						yield "AUDIO";
					}
					yield row.id().toUpperCase(java.util.Locale.ROOT);
				}
			};
			graphics.fillText(label, 6, row.top() + row.height() / 2 + 3);
		}
	}

	private void drawAgentHighlights(GraphicsContext graphics, double width) {
		if (agentHighlights == null || agentHighlights.isEmpty()) {
			return;
		}
		double top = AXIS_HEIGHT + 2;
		double height = Math.max(6, canvas.getHeight() - AXIS_HEIGHT - 4);
		for (AgentHighlight highlight : agentHighlights) {
			double startX = xForTime(highlight.span().start());
			double endX = xForTime(highlight.span().end());
			if (endX < 0 || startX > width || endX <= startX) {
				continue;
			}
			double x = Math.max(0, startX);
			double highlightWidth = Math.min(width, endX) - x;
			graphics.setFill(Color.web("#a78bfa", 0.16));
			graphics.fillRoundRect(x, top, highlightWidth, height, 8, 8);
			graphics.setStroke(Color.web("#a78bfa", 0.5));
			graphics.setLineWidth(1);
			graphics.strokeRoundRect(x + 0.5, top + 0.5, Math.max(1, highlightWidth - 1),
					Math.max(6, height - 1), 8, 8);
			if (highlight.label() != null && highlightWidth > 48) {
				graphics.setFont(SMALL_FONT);
				graphics.setFill(Color.web("#ddd6fe"));
				graphics.fillText(fitText(highlight.label(), highlightWidth - 8, SMALL_FONT), x + 4,
						top + 14);
			}
		}
	}

	/**
	 * PlaybackCursor: a 1px red line over every lane and a 16px red cap on the axis that grows to
	 * show the playhead time while hovered or dragged (kept inside the viewport at both ends).
	 */
	private void drawPlayhead(GraphicsContext graphics, double height) {
		double x = xForTime(currentTimeMs);
		double width = contentWidth();
		if (x < 0 || x > width) {
			return;
		}
		graphics.setStroke(PLAYHEAD);
		graphics.setLineWidth(1);
		graphics.strokeLine(x, 0, x, height);
		boolean expanded = playheadHovered || scrubbing;
		double capWidth = expanded ? PLAYHEAD_CAP_EXPANDED : PLAYHEAD_CAP_WIDTH;
		double capShift = expanded
				? Math.max(0, PLAYHEAD_CAP_EXPANDED / 2 - x)
						- Math.max(0, PLAYHEAD_CAP_EXPANDED / 2 - (width - x))
				: 0;
		double capX = x - capWidth / 2 + capShift;
		graphics.setFill(PLAYHEAD);
		graphics.fillRoundRect(capX, 0, capWidth, PLAYHEAD_CAP_HEIGHT, PLAYHEAD_CAP_HEIGHT,
				PLAYHEAD_CAP_HEIGHT);
		if (expanded) {
			graphics.setFill(Color.WHITE);
			graphics.setFont(PLAYHEAD_FONT);
			graphics.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
			graphics.setTextBaseline(javafx.geometry.VPos.CENTER);
			graphics.fillText(TimelineScale.formatPlayheadTime(currentTimeMs), capX + capWidth / 2,
					PLAYHEAD_CAP_HEIGHT / 2);
			graphics.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
			graphics.setTextBaseline(javafx.geometry.VPos.BASELINE);
		}
	}

	private boolean isSelected(String kind, String id) {
		if (selectAllBlocksActive && TimelineSelection.ZOOM.equals(kind)) {
			return true;
		}
		return selection != null && kind.equals(selection.kind()) && id.equals(selection.id());
	}

	private void drawSelection(GraphicsContext graphics, double x, double y, double width,
			double height, Color color) {
		graphics.setStroke(color);
		graphics.setLineWidth(2);
		graphics.strokeRoundRect(x + 1, y + 1, Math.max(1, width - 2), Math.max(1, height - 2), 8,
				8);
	}

	private Span displaySpanFor(TimelineRenderItem item) {
		if (TimelineConstants.CLIP_ROW_ID.equals(item.rowId())) {
			for (ClipPresentation.Presentation presentation : clipPresentation) {
				if (presentation.span().equals(item.span())) {
					return presentation.displaySpan();
				}
			}
			return item.span();
		}
		return getRegionDisplaySpan(item.span());
	}

	private Span getRegionDisplaySpan(Span mediaSpan) {
		return ClipPresentation.getRegionDisplaySpan(mediaSpan, clipPresentation);
	}

	private String zoomLabel(TimelineRenderItem item) {
		if (item.zoomDepth() != null) {
			return TimelineModel.formatZoomScale(item.zoomDepth().scale());
		}
		if (item.label() != null && !item.label().isBlank()) {
			return item.label();
		}
		return "Zoom";
	}

	private static double textWidth(String text, Font font) {
		javafx.scene.text.Text measure = new javafx.scene.text.Text(text == null ? "" : text);
		measure.setFont(font);
		return measure.getLayoutBounds().getWidth();
	}

	private static String fitText(String text, double maxWidth, Font font) {
		String value = text == null ? "" : text;
		if (textWidth(value, font) <= maxWidth) {
			return value;
		}
		String ellipsis = "\u2026";
		while (!value.isEmpty() && textWidth(value + ellipsis, font) > maxWidth) {
			value = value.substring(0, value.length() - 1);
		}
		return value + ellipsis;
	}
}