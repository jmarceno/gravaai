package dev.gravaai.ui.editor.timeline;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.gravaai.core.os.HostOs;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AudioCleanup;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.CameraLayout;
import dev.gravaai.core.project.CameraLayoutSegment;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionTimeline;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipTransition;
import dev.gravaai.core.project.ClipTransitionKind;
import dev.gravaai.core.project.MarkerTimeline;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.SourceAudioTrackSettings;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.TimelineMarker;
import dev.gravaai.core.project.TrimRegion;
import dev.gravaai.core.project.ZoomRegion;
import dev.gravaai.media.compose.SmartFraming;
import dev.gravaai.media.compose.ZoomSharpen;
import dev.gravaai.ui.editor.timeline.core.ClipPresentation;
import dev.gravaai.ui.editor.timeline.core.SourceAudioCleanupBadge;
import dev.gravaai.ui.editor.timeline.core.Span;
import dev.gravaai.ui.editor.timeline.core.TimelineDndEngine;
import dev.gravaai.ui.editor.timeline.core.TimelineRangeMath;
import dev.gravaai.ui.editor.timeline.core.TimelineRegionSpan;
import dev.gravaai.ui.editor.timeline.core.TimelineShortcuts;
import dev.gravaai.ui.editor.timeline.core.TimelineSourceAudioTrack;
import dev.gravaai.ui.AppFonts;
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
import dev.gravaai.ui.kit.Anchors;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Popup;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.scene.SnapshotParameters;
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
public final class TimelineView extends Region {

	private static final double AXIS_HEIGHT = TimelineLayout.TIMELINE_AXIS_HEIGHT_PX;
	private static final double CLIP_ROW_HEIGHT = TimelineLayout.TIMELINE_CLIP_ROW_HEIGHT_PX;
	private static final double COMPACT_ROW_HEIGHT = TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX;
	private static final double ROW_GAP = 0;
	private static final double MIN_VISIBLE_RANGE_MS = 300;
	/** Draw one waveform bar every so many pixels (the peaks request samples the visible window). */
	private static final double WAVEFORM_BAR_PITCH_PX = 3;
	/** Opacity of a muted source-audio block's waveform (mock .clip--muted half-visibility). */
	private static final double WAVEFORM_MUTED_ALPHA = 0.35;
	private static final double RESIZE_EDGE_PX = 6;
	private static final double DRAG_THRESHOLD_PX = 3;
	private static final double GHOST_ZOOM_MS = 1000;
	private static final double CAPTION_GHOST_MS = 1500;
	/** Fixed left track-header gutter (mock {@code .tlbody}: 112px column). */
	private static final double GUTTER_WIDTH = 112;
	/** Timeline toolbar row above the ruler (mock {@code .tlbar}: 30px). */
	private static final double TLBAR_HEIGHT = 30;
	/** Thickness of the horizontal (time) and vertical (rows) scroll bars. */
	private static final double SCROLL_BAR_SIZE = 10;
	/** Vertical wheel step for the rows scroll, in pixels per wheel unit. */
	private static final double ROW_SCROLL_STEP = 1;

	/* Instrument mock surfaces (docs/mocks/editor-instrument.html). */
	private static final Color LANES_BG = Color.web("#080b0e");
	private static final Color CHROME_BG = Color.web("#0b0f12");
	private static final Color HAIRLINE = Color.web("#171e24");
	private static final Color FAINT = Color.web("#5b6872");
	private static final Color CYAN = Color.web("#2fe6d0");
	private static final Color CYAN_INK = Color.web("#032522");
	private static final Color AMBER = Color.web("#f0b429");
	private static final Color GUTTER_TEXT = Color.web("#e9f1f4");

	/* Instrument mock clip treatments. */
	private static final Paint CLIP_FILL = new LinearGradient(0, 0, 0, 1, true,
			CycleMethod.NO_CYCLE, new Stop(0, Color.web("#153e3b")), new Stop(1,
					Color.web("#10312f")));
	private static final Color CLIP_BORDER = Color.web("#1e5a54");
	private static final Color CLIP_TEXT = Color.web("#9df3e8");
	private static final Color CLIP_SELECTED = Color.web("#2fe6d0");
	private static final Color ZOOM_FILL = Color.web("#2fe6d0", 0.10);
	private static final Color ZOOM_BORDER = Color.web("#2fe6d0", 0.26);
	private static final Color ZOOM_SELECTED = Color.web("#2fe6d0");
	/** The sparkle marking zoom stages that run the deep-zoom sharpening pass ({@link ZoomSharpen}). */
	private static final Color ZOOM_SHARPEN_GLYPH = Color.web("#a5ecff");
	/** The ring marking stages an export will upscale with the optional SR model. */
	private static final Color ZOOM_UPSCALE_GLYPH = Color.web("#c8a6ff");
	private static final Color GRADE_FILL = Color.web("#f0b429", 0.10);
	private static final Color GRADE_BORDER = Color.web("#f0b429", 0.35);
	private static final Color GRADE_SELECTED = Color.web("#f0b429");
	private static final Color ANNOTATION_FILL = Color.web("#141a1f");
	private static final Color ANNOTATION_BORDER = Color.web("#28323a");
	private static final Color ANNOTATION_SELECTED = Color.web("#f0b429");
	/* Loupe blocks: a cool glass tint so a magnifying glass reads apart from text overlays. */
	private static final Color LOUPE_FILL = Color.web("#16242b");
	private static final Color LOUPE_BORDER = Color.web("#2f6d80");
	private static final Color LOUPE_SELECTED = Color.web("#67d4f0");
	private static final Color LOUPE_GLYPH = Color.web("#a5ecff");
	/* Spotlight blocks: a warm dim tint so the lamp reads apart from text overlays. */
	private static final Color SPOTLIGHT_FILL = Color.web("#241f16");
	private static final Color SPOTLIGHT_BORDER = Color.web("#7a5c25");
	private static final Color SPOTLIGHT_SELECTED = Color.web("#f0b429");
	private static final Color SPOTLIGHT_GLYPH = Color.web("#ffd166");
	private static final Color AUDIO_FILL = Color.web("#1a2128");
	private static final Color AUDIO_BORDER = Color.web("#28323b");
	private static final Color AUDIO_TEXT = Color.web("#a9b6c0");
	private static final Color AUDIO_SELECTED = Color.web("#2fe6d0");
	private static final Color WAVEFORM_COLOR = Color.web("#8fa3b0", 0.55);
	private static final Color SOURCE_AUDIO_FILL = Color.web("#12171d");
	/** Edit-time cleanup indicator: amber, the same family as grades and speed blocks. */
	private static final Color CLEANUP_ACCENT = Color.web("#f0b429");
	private static final Color CLEANUP_BADGE_FILL = Color.web("#f0b429", 0.18);
	private static final Color CLEANUP_BADGE_TEXT = Color.web("#f7d77a");
	private static final Color KEYSTROKE_FILL = Color.web("#241d38");
	private static final Color KEYSTROKE_BORDER = Color.web("#8b5cf6", 0.55);
	private static final Color KEYSTROKE_SELECTED = Color.web("#a78bfa");
	private static final Color CAMERA_SPLIT_FILL = Color.web("#12271f");
	private static final Color CAMERA_BUBBLE_FILL = Color.web("#0f2624");
	private static final Color CAMERA_ONLY_FILL = Color.web("#2a2412");
	private static final Color CAMERA_HIDDEN_FILL = Color.web("#1a1d21");
	private static final Color CAMERA_LAYOUT_BORDER = Color.web("#2dd4bf", 0.6);
	private static final Color CAMERA_LAYOUT_SELECTED = Color.web("#5eead4");
	private static final Color CAMERA_LAYOUT_LANE = Color.web("#2dd4bf", 0.35);
	private static final Color CAMERA_LAYOUT_RAMP = Color.web("#2dd4bf", 0.25);
	private static final Color ELEMENT_FILL = Color.web("#221a38");
	private static final Color ELEMENT_BORDER = Color.web("#a78bfa", 0.6);
	private static final Color ELEMENT_SELECTED = Color.web("#ddd6fe");
	private static final Color ELEMENT_ERROR = Color.web("#ff5f6d");
	private static final Color ELEMENT_RAMP = Color.web("#c4b5fd", 0.35);
	private static final Color CAPTION_FILL = Color.web("#1b1c25");
	private static final Color CAPTION_BORDER = Color.web("#2a2c39");
	private static final Color CAPTION_TEXT = Color.web("#b3b7c8");
	private static final Color CAPTION_SELECTED = Color.web("#2fe6d0");
	private static final Color TRIM_FILL = Color.web("#ff5f6d", 0.12);
	private static final Color TRIM_BORDER = Color.web("#ff5f6d", 0.4);
	/** Pending transcript cut: the trim red, at preview weight. */
	private static final Color CUT_PREVIEW_FILL = Color.web("#ff5f6d", 0.26);
	private static final Color CUT_PREVIEW_BORDER = Color.web("#ff8b92", 0.85);
	private static final Color CUT_PREVIEW_HATCH = Color.web("#ff5f6d", 0.32);
	private static final Color SPEED_FILL = Color.web("#f0b429", 0.14);
	private static final Color SPEED_BORDER = Color.web("#f0b429", 0.45);
	private static final Color SPEED_TEXT = Color.web("#f0b429");
	private static final Color PLAYHEAD = Color.web("#2fe6d0");
	/** Smart-framing dodge markers: the playhead cyan plus its faded glide band. */
	private static final Color SMART_FRAMING_MOVE = Color.web("#2fe6d0");
	private static final Color SMART_FRAMING_MOVE_BAND = Color.web("#2fe6d0", 0.22);
	private static final Color SMART_FRAMING_RETURN = Color.web("#9fb3bd");
	private static final Color SMART_FRAMING_RETURN_BAND = Color.web("#9fb3bd", 0.18);
	/** Eye-contact strip: corrected, faded, analysing (track + progress) and failed. */
	private static final Color EYE_CONTACT_ON = Color.web("#7aa2ff");
	/** The pen bands of the clip row (live ink drawn while recording). */
	private static final Color INK_BAND = Color.web("#ffb020");
	private static final Color INK_BAND_FILL = Color.web("#ffb020", 0.16);
	private static final Color INK_TAG_TEXT = Color.web("#1b1407");
	private static final Color EYE_CONTACT_FADED = Color.web("#7aa2ff", 0.35);
	private static final Color EYE_CONTACT_TRACK = Color.web("#9fb3bd", 0.25);
	private static final Color EYE_CONTACT_PROGRESS = Color.web("#9fb3bd", 0.8);
	private static final Color EYE_CONTACT_FAILED = Color.web("#ff6b6b");
	private static final double PLAYHEAD_CAP_WIDTH = 8;
	private static final double PLAYHEAD_CAP_HEIGHT = 8;
	private static final double PLAYHEAD_TAG_HEIGHT = 15;
	/** Grab tolerance around the playhead line in the lanes. */
	private static final double PLAYHEAD_GRAB_PX = 4;
	private static final Font PLAYHEAD_FONT = AppFonts.monoFont(javafx.scene.text.FontWeight.MEDIUM, 9);
	private static final Color GRID_LINE = Color.web("#171e24");
	/* Timeline markers: a violet flag on the ruler, chapters a filled one. */
	private static final Color MARKER_COLOR = Color.web("#a78bfa");
	private static final Color MARKER_CHAPTER_COLOR = Color.web("#c4b5fd");
	private static final Color MARKER_CHAPTER_TEXT = Color.web("#e9e2ff");
	private static final Color MARKER_TEXT = Color.web("#0d0a17");
	private static final Color MARKER_PILL_FILL = Color.web("#141020", 0.85);
	private static final Color MARKER_LINE = Color.web("#a78bfa", 0.35);
	private static final double MARKER_FLAG_WIDTH = 9;
	private static final double MARKER_FLAG_HEIGHT = 11;
	/** Grab tolerance around a marker flag on the ruler, in pixels. */
	private static final double MARKER_GRAB_PX = 6;
	private static final Color BLOCK_TEXT = Color.web("#e9f1f4", 0.92);
	/** Dark pill behind clip labels (mock {@code .clip__lbl}). */
	private static final Color BADGE_FILL = Color.web("#060a0d", 0.55);
	private static final Color BADGE_TEXT = Color.web("#e9f1f4", 0.92);
	/** The fade diamond at a clip's outgoing boundary: light fill over a dark outline. */
	private static final Color FADE_MARK_FILL = Color.web("#e9f1f4");
	private static final Color FADE_MARK_OUTLINE = Color.web("#060a0d", 0.8);
	/** Quick fade durations in the clip context menu, between the transition min and max. */
	private static final long[] FADE_DURATION_CHOICES_MS = {200, 500, 1000, 2000};

	private static final Font AXIS_FONT = AppFonts.monoFont(9);
	private static final Font MARKER_FONT = AppFonts.monoFont(javafx.scene.text.FontWeight.MEDIUM,
			8.5);
	private static final Font BLOCK_FONT = AppFonts.monoFont(javafx.scene.text.FontWeight.MEDIUM, 9.5);
	private static final Font SMALL_FONT = AppFonts.monoFont(9);
	private static final Font ROW_LABEL_FONT = AppFonts.monoFont(javafx.scene.text.FontWeight.MEDIUM, 9.5);

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
	/** Toolbar above the ruler (mock {@code .tlbar}): split, suggest-zooms, live scale readout. */
	private final HBox toolbar = new HBox(2);
	private final Label scaleLabel = new Label("");
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
	/**
	 * Source ranges a transcript edit would cut, drawn as warning bands before the edit is applied
	 * ({@link #setCutPreview}); empty when no preview is pending.
	 */
	private List<Span> cutPreview = List.of();
	private double currentTimeMs;
	private List<RowLayout> rowLayouts = List.of();
	private List<TimelineRenderItem> items = List.of();
	/** Camera layout segment id to its layout, for the block tint. */
	private java.util.Map<String, CameraLayoutSegment> cameraLayouts = java.util.Map.of();
	private Runnable onCameraLayoutShortcut = () -> {};
	/** The suggest-zooms action the toolbar Auto button fires (the app's guarded action). */
	private Runnable onSuggestZooms = () -> {};
	private java.util.Map<String, dev.gravaai.core.project.SceneElement> elementsById = java.util.Map.of();
	private java.util.Set<String> elementErrorIds = java.util.Set.of();
	/** Switch diamonds drawn at Camera layout segment starts (click toggles cut/smooth). */
	private final Map<String, BlockBounds> cameraSwitchBounds = new LinkedHashMap<>();
	/** Switch-out diamonds at Camera layout segment ends followed by a gap (click toggles). */
	private final Map<String, BlockBounds> cameraSwitchOutBounds = new LinkedHashMap<>();
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
	/**
	 * Drag over empty Camera-lane space: the press point, the live span of the block being drawn
	 * (null until the pointer crosses the drag threshold), and whether the press started there.
	 */
	private boolean pressedCameraLane;
	private double cameraLanePressMs;
	private @Nullable Span cameraLaneDrawSpan;
	/** Seek-drag in progress (empty lane press or playhead grab, React isSeeking / cap drag). */
	private boolean scrubbing;
	/** Pointer over the playhead cap/line: the cap expands and shows the time (React isHovered). */
	private boolean playheadHovered;
	private @Nullable Span hoverGhost;
	private @Nullable String hoverGhostKind;
	private double minItemDurationMs = 100;
	private List<CaptionTimeline.Fragment> captionFragments = List.of();
	private List<TimelineSourceAudioTrack> sourceAudioTracks = List.of();
	/** Per-track cleanup of the project defaults; drives the source lane indicator. */
	private SourceAudioTrackSettings defaultTrackSettings = SourceAudioTrackSettings.EMPTY;
	private FilmstripProvider filmstripProvider = FilmstripProvider.empty();
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
	/** Row-header icons rendered once (kit SVG snapshots), keyed by icon constant. */
	private final Map<String, Image> gutterIconCache = new LinkedHashMap<>();
	private javafx.collections.@Nullable ListChangeListener<AgentHighlightView> agentHighlightsListener;
	private java.util.function.@Nullable Consumer<String> onCaptionSelected;
	private @Nullable Runnable onItemPressed;
	/**
	 * Marker gestures that need a dialog or a menu live in the shell (rename prompt, chapter
	 * toggle menu); the view routes the gesture and keeps the painting.
	 */
	private java.util.function.@Nullable Consumer<String> onMarkerRenameRequested;
	/** A marker was selected on the ruler: the shell scrolls to it and refreshes the inspector. */
	private java.util.function.@Nullable Consumer<String> onMarkerSelected;
	/** The marker under the pointer, highlighted while the pointer is over its flag. */
	private @Nullable String hoveredMarkerId;
	/** Markers projected onto the edited timeline (source-time markers whose footage is kept). */
	private List<MarkerTimeline.Fragment> markerFragments = List.of();
	/** Marker id → ruler flag bounds, for the ruler hit test and the drag ghost. */
	private final Map<String, BlockBounds> markerBounds = new LinkedHashMap<>();
	/** Hit boxes of the pen bands, by index into {@link TimelineDataSource#inkMarks()}. */
	private final Map<Integer, BlockBounds> inkBounds = new LinkedHashMap<>();
	private int hoveredInkIndex = -1;
	private @Nullable String dragMarkerId;
	private double dragMarkerMs;
	private boolean markerDragged;
	/** Ids of every multi-selected element (Ctrl/Cmd+click toggles; Ctrl/Cmd+A selects all zooms). */
	private final java.util.LinkedHashSet<String> selectedIds = new java.util.LinkedHashSet<>();
	/** Live spans of the same-row group during a multi-selection move drag (id → translated span). */
	private java.util.@Nullable LinkedHashMap<String, Span> dragGroupSpans;
	private TimelineShortcuts.Bindings shortcutBindings = TimelineShortcuts.defaults();
	/**
	 * Zoom stages at this depth are upscaled by an export (the optional SR model). Zero disables
	 * the marker: the feature is off, or the model is not installed.
	 */
	private double exportUpscaleFromScale;

	public TimelineView(TimelineDataSource dataSource, TimelineCommands commands) {
		this.dataSource = dataSource;
		this.commands = commands == null ? TimelineCommands.NONE : commands;
		buildToolbar();
		getChildren().addAll(toolbar, canvas, timeScrollBar, rowScrollBar);
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
		// The toolbar, ruler and one lane are the minimum; the rest scrolls inside the panel.
		setMinHeight(TLBAR_HEIGHT + AXIS_HEIGHT + COMPACT_ROW_HEIGHT + SCROLL_BAR_SIZE);
		setFocusTraversable(false);
		canvas.setOnScroll(this::handleScroll);
		canvas.setOnMousePressed(this::handleMousePressed);
		canvas.setOnMouseDragged(this::handleMouseDragged);
		canvas.setOnMouseReleased(this::handleMouseReleased);
		canvas.setOnMouseMoved(this::handleMouseMoved);
		canvas.setOnMouseExited(event -> {
			hoverGhost = null;
			hoverGhostKind = null;
			hoveredMarkerId = null;
			canvas.setCursor(Cursor.DEFAULT);
			redraw();
		});
		canvas.setFocusTraversable(true);
		canvas.setOnKeyPressed(this::handleKeyPressed);
		widthProperty().addListener((observable, oldValue, value) -> requestLayout());
		heightProperty().addListener((observable, oldValue, value) -> requestLayout());
		// Waveforms computed in the background repaint once they land (the provider calls any thread).
		dataSource.waveforms().setOnPeaksReady(() -> javafx.application.Platform.runLater(this::redraw));
		refreshData();
	}

	/**
	 * The timeline toolbar (mock {@code .tlbar}): split at the playhead and the zoom suggester —
	 * the editing commands the transport used to host — plus the live ruler-division readout.
	 */
	private void buildToolbar() {
		toolbar.getStyleClass().add("timeline-toolbar");
		toolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
		dev.gravaai.ui.kit.Button split = toolbarButton(Icons.Scissors, "Split clip at the playhead",
				this::splitAtPlayhead);
		dev.gravaai.ui.kit.Button suggest = toolbarButton(Icons.MagicWand, "Suggest zooms",
				this::suggestZoomsAction);
		javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		scaleLabel.getStyleClass().add("timeline-toolbar__scale");
		toolbar.getChildren().addAll(split, suggest, spacer, scaleLabel);
	}

	/** One ghost icon button in the timeline toolbar, wired to a timeline action. */
	private static dev.gravaai.ui.kit.Button toolbarButton(String icon, String tooltip,
			Runnable action) {
		dev.gravaai.ui.kit.Button button = new dev.gravaai.ui.kit.Button("",
				dev.gravaai.ui.kit.Button.Variant.GHOST, dev.gravaai.ui.kit.Button.Size.ICON);
		button.icon(Icon.of(icon, 12));
		button.setOnAction(event -> action.run());
		dev.gravaai.ui.kit.Tooltip.install(button, tooltip);
		return button;
	}

	/** The C-key split path: cut the clip under the playhead at the playhead time. */
	private void splitAtPlayhead() {
		TimelineRenderItem clip = clipAtPlayhead();
		if (clip != null) {
			commands.splitClip(clip.id(), currentTimeMs);
		}
	}

	/** The toolbar's suggest button: the app seam keeps the guards and toasts of the action. */
	private void suggestZoomsAction() {
		onSuggestZooms.run();
	}

	/** Replaces the suggest-zooms action (the app owns the guards and the result toast). */
	public void setOnSuggestZooms(Runnable action) {
		this.onSuggestZooms = action == null ? () -> { } : action;
	}

	/* ---------- public API (tests / shell wiring) ---------- */

	public Span range() {
		return range;
	}

	public Span fullRange() {
		return fullRange;
	}

	/** Visible milliseconds per screen pixel, across the lanes right of the header gutter. */
	public double msPerPixel() {
		double width = Math.max(1, lanesWidth());
		return range.duration() / width;
	}

	/** Screen x for a timeline time (offset by the track-header gutter). */
	public double xForTime(double timeMs) {
		return GUTTER_WIDTH + (timeMs - range.start()) / msPerPixel();
	}

	/** Timeline time for a screen x. */
	public double timeAtX(double x) {
		return Math.max(0, Math.min(range.start() + (x - GUTTER_WIDTH) * msPerPixel(),
				fullRange.end()));
	}

	/** The drawable width right of the fixed header gutter. */
	private double lanesWidth() {
		return Math.max(1, contentWidth() - GUTTER_WIDTH);
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

	/**
	 * Pending cut preview: source-cut spans (already mapped to timeline time) drawn as warning
	 * bands over the clip row before a transcript edit is applied. Empty clears it; applying or
	 * cancelling the edit does, and the split clips show the result afterwards.
	 */
	public void setCutPreview(List<Span> spans) {
		List<Span> next = spans == null ? List.of() : List.copyOf(spans);
		if (next.equals(cutPreview)) {
			return;
		}
		cutPreview = next;
		redraw();
	}

	/** The spans currently previewed as pending cuts (tests/shell). */
	public List<Span> cutPreview() {
		return cutPreview;
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
		// Tracks first: the items below include one block per track per source-audio clip.
		sourceAudioTracks = dataSource.sourceAudioTracks();
		defaultTrackSettings = project.defaultSourceAudioTrackSettingsTyped();
		items = TimelineModel.buildTimelineItems(new TimelineModel.ItemsRequest(
				project.zoomRegions(), project.clipRegions(), project.annotationRegions(),
				project.audioRegions(), project.autoCaptions(),
				project.sourceAudioTrackSettings(), sourceAudioTracks,
				project.keystrokeElements(), project.cameraLayoutSegments(),
				project.gradeRegions()));
		items = withElementItems(items, project.elements());
		cameraLayouts = new java.util.HashMap<>();
		for (CameraLayoutSegment segment : project.cameraLayoutSegments()) {
			cameraLayouts.put(segment.id(), segment);
		}
		FilmstripProvider provider = dataSource.filmstripProvider();
		filmstripProvider = provider == null ? FilmstripProvider.empty() : provider;
		filmstripProvider.setOnFrameReady(
				() -> javafx.application.Platform.runLater(this::redraw));
		captionFragments = CaptionTimeline.projectCaptionCues(project.autoCaptions(),
				project.clipRegions());
		// Markers are stored in source time; project them through the retained clips so trims and
		// cuts move them with the footage (a marker whose footage is gone is not drawn).
		markerFragments = MarkerTimeline.projectMarkers(project.markers(), project.clipRegions());
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
		return TLBAR_HEIGHT + contentHeight() + SCROLL_BAR_SIZE;
	}

	/**
	 * Only the toolbar, the ruler and one lane are required: the rest scrolls inside the panel
	 * (React's {@code timeline-scroll} is {@code overflow-y-auto}), so the timeline never grows
	 * past it.
	 */
	@Override
	protected double computeMinHeight(double width) {
		return TLBAR_HEIGHT + AXIS_HEIGHT + COMPACT_ROW_HEIGHT + SCROLL_BAR_SIZE;
	}

	@Override
	protected void layoutChildren() {
		double width = Math.max(1, getWidth());
		double lanesHeight = Math.max(AXIS_HEIGHT, getHeight() - SCROLL_BAR_SIZE - TLBAR_HEIGHT);
		boolean rowsOverflow = contentHeight() > lanesHeight + 0.5;
		double lanesWidth = Math.max(1, width - (rowsOverflow ? SCROLL_BAR_SIZE : 0));
		toolbar.resizeRelocate(0, 0, lanesWidth, TLBAR_HEIGHT);
		canvas.setWidth(lanesWidth);
		canvas.setHeight(lanesHeight);
		canvas.resizeRelocate(0, TLBAR_HEIGHT, lanesWidth, lanesHeight);
		timeScrollBar.resizeRelocate(0, TLBAR_HEIGHT + lanesHeight, lanesWidth, SCROLL_BAR_SIZE);
		rowScrollBar.setVisible(rowsOverflow);
		rowScrollBar.resizeRelocate(lanesWidth, TLBAR_HEIGHT + AXIS_HEIGHT, SCROLL_BAR_SIZE,
				Math.max(1, lanesHeight - AXIS_HEIGHT));
		scrollY = Math.max(0, Math.min(scrollY, maxScrollY()));
		if (agentOverlay != null) {
			agentOverlay.resizeRelocate(0, TLBAR_HEIGHT, lanesWidth, lanesHeight);
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
		// Animated elements get their own lanes (overlaps stack), right under the clip rows.
		List<String> elementLanes = items.stream()
				.filter(item -> item.variant() == TimelineRenderItem.Variant.ELEMENT)
				.map(TimelineRenderItem::rowId).distinct().sorted().toList();

		double top = AXIS_HEIGHT;
		rows.add(new RowLayout(TimelineConstants.CLIP_ROW_ID, top, CLIP_ROW_HEIGHT));
		top += CLIP_ROW_HEIGHT + ROW_GAP;
		// Captions get their own lane (mock CAP row) right under the footage they caption.
		if (!captionFragments.isEmpty()) {
			rows.add(new RowLayout(TimelineConstants.CAPTION_ROW_ID, top, COMPACT_ROW_HEIGHT));
			top += COMPACT_ROW_HEIGHT + ROW_GAP;
		}
		// The Camera layout track sits right under the clips it re-frames. It is always present:
		// its empty stretches are the project layout (dashed lane), and clicking one returns the
		// Webcam panel to the project scope.
		rows.add(new RowLayout(TimelineConstants.CAMERA_LAYOUT_ROW_ID, top, COMPACT_ROW_HEIGHT));
		top += COMPACT_ROW_HEIGHT + ROW_GAP;
		for (String lane : elementLanes) {
			rows.add(new RowLayout(lane, top, COMPACT_ROW_HEIGHT));
			top += COMPACT_ROW_HEIGHT + ROW_GAP;
		}
		for (TimelineSourceAudioTrack track : sourceAudioTracks) {
			rows.add(new RowLayout(TimelineRows.getSourceAudioTrackRowId(track.id()), top,
					COMPACT_ROW_HEIGHT));
			top += COMPACT_ROW_HEIGHT + ROW_GAP;
		}

		// Every lane is one 44px row in the Instrument layout.
		rows.add(new RowLayout(TimelineConstants.ZOOM_ROW_ID, top, COMPACT_ROW_HEIGHT));
		top += COMPACT_ROW_HEIGHT + ROW_GAP;

		// The Color track sits under the zooms: like them it is always present, and clicking the
		// empty lane drops a grade over the footage at that time.
		rows.add(new RowLayout(TimelineConstants.GRADE_ROW_ID, top, COMPACT_ROW_HEIGHT));
		top += COMPACT_ROW_HEIGHT + ROW_GAP;

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

		// The Keystrokes lanes go last, below every recorded and effect row: presses overlap
		// freely (each lasts the overlay linger), so the lanes multiply and would otherwise push
		// the zoom and audio rows off-screen behind a scroll.
		List<Integer> keystrokeLaneIndexes = new ArrayList<>();
		for (TimelineRenderItem item : items) {
			if (TimelineRows.isKeystrokeLaneRowId(item.rowId())) {
				int index = TimelineRows.getKeystrokeLaneIndex(item.rowId());
				if (!keystrokeLaneIndexes.contains(index)) {
					keystrokeLaneIndexes.add(index);
				}
			}
		}
		keystrokeLaneIndexes.sort(Comparator.naturalOrder());
		for (int index : keystrokeLaneIndexes) {
			rows.add(new RowLayout(TimelineRows.getKeystrokeLaneRowId(index), top, COMPACT_ROW_HEIGHT));
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
		if (event.getButton() == MouseButton.SECONDARY) {
			// A right click on a marker flag opens its chapter menu instead of the clip fade menu.
			String markerId = markerAt(event.getX(), event.getY());
			if (markerId != null) {
				selectMarker(markerId);
				showMarkerContextMenu(markerId, event.getX(), event.getY());
				event.consume();
				return;
			}
			openClipContextMenu(event.getX(), event.getY());
			event.consume();
			return;
		}
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		canvas.requestFocus();
		if (event.getClickCount() >= 2 && markerAt(event.getX(), event.getY()) != null) {
			// Double click on a flag renames it, like the caption strip's double click edits text.
			String markerId = java.util.Objects.requireNonNull(markerAt(event.getX(), event.getY()));
			selectMarker(markerId);
			if (onMarkerRenameRequested != null) {
				onMarkerRenameRequested.accept(markerId);
			}
			event.consume();
			return;
		}
		String markerId = markerAt(event.getX(), event.getY());
		if (markerId != null) {
			// A marker press selects it and starts a ruler drag: the flag follows the pointer and
			// the store commits the new position on release.
			pressMarker(markerId, event.getX());
			event.consume();
			return;
		}
		pressAt(event.getX(), contentY(event.getY()),
				event.isControlDown() || event.isMetaDown());
	}

	/** Starts a ruler drag on a marker; also what {@link #pressAt} does over a flag. */
	private void pressMarker(String markerId, double x) {
		selectMarker(markerId);
		dragMarkerId = markerId;
		markerDragged = false;
		dragStartX = x;
	}

	/** The marker flag under a canvas point (ruler strip or the flag's grab box), if any. */
	public @Nullable String markerAt(double x, double y) {
		for (Map.Entry<String, BlockBounds> entry : markerBounds.entrySet()) {
			if (entry.getValue().contains(x, y)) {
				return entry.getKey();
			}
		}
		return null;
	}

	private void selectMarker(String markerId) {
		commands.selectMarker(markerId);
		if (onMarkerSelected != null) {
			onMarkerSelected.accept(markerId);
		}
		redraw();
	}

	/** Picks a block edge for resize, or starts a move; the TestFX harness uses this. */
	public void pressAt(double x, double y) {
		pressAt(x, y, false);
	}

	/**
	 * Picks a block edge for resize, or starts a move; the TestFX harness uses this. With
	 * {@code additive} (Ctrl/Cmd pressed) a multi-selectable block toggles in the multi-selection
	 * instead of replacing it.
	 */
	public void pressAt(double x, double y, boolean additive) {
		resetDrag();
		if (y <= AXIS_HEIGHT) {
			// Ruler presses: a marker flag starts its drag; the playhead cap keeps its scrub grab.
			String markerId = markerAt(x, y);
			if (markerId != null) {
				pressMarker(markerId, x);
				return;
			}
		}
		if (isOnPlayhead(x, y)) {
			// PlaybackCursor cap drag: scrub without changing the selection.
			scrubbing = true;
			redraw();
			return;
		}
		Optional<InkMark> ink = inkMarkAt(x, y);
		if (ink.isPresent()) {
			commands.seek(ink.get().startMs());
			setCurrentTimeMs(ink.get().startMs());
			return;
		}
		Optional<String> cameraSwitch = cameraSwitchAt(x, y);
		if (cameraSwitch.isPresent()) {
			commands.toggleCameraLayoutTransition(cameraSwitch.get());
			return;
		}
		Optional<String> cameraSwitchOut = cameraSwitchOutAt(x, y);
		if (cameraSwitchOut.isPresent()) {
			commands.toggleCameraLayoutTransitionOut(cameraSwitchOut.get());
			return;
		}
		if (hasCaptions() && inCaptionStrip(y)) {
			// The caption strip overlays the filmstrip. A caption block selects/edits that cue;
			// empty strip space starts the add-caption gesture.
			TimelineSelection captionHit = captionHitAt(x, y);
			if (captionHit != null) {
				selectedIds.clear();
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
			if (inGradeRow(y)) {
				// The Color lane drops a grade over the footage at that time.
				pressedEmpty = true;
				dragStartX = x;
				return;
			}
			if (inCameraLayoutRow(y)) {
				// Empty Camera-lane space is the project layout: a click returns the Webcam panel
				// to that scope, a drag draws a new block over the range instead.
				selectedIds.clear();
				setSelection(null);
				commands.clearSelection();
				pressedCameraLane = true;
				dragStartX = x;
				cameraLanePressMs = Math.max(0, Math.min(timeAtX(x), fullRange.end()));
				redraw();
				return;
			}
			// handleTimelineMouseDown: clear the block selection, seek at once, then keep seeking
			// while the pointer drags (the preview follows in real time).
			selectedIds.clear();
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
			selectedIds.clear();
			TimelineSelection clipSelection = new TimelineSelection(TimelineSelection.CLIP,
					sourceAudioOwnerClipId(item.rowId(), item.id()));
			setSelection(clipSelection);
			commands.select(clipSelection);
			fireItemPressed();
			redraw();
			return;
		}
		if (additive && isMultiSelectableKind(hit.kind())) {
			// Ctrl/Cmd+click toggles the block in the multi-selection; pressing an already selected
			// block keeps the group move gesture, pressing a deselected one just toggles it out.
			boolean added = selectedIds.contains(hit.id())
					? !selectedIds.remove(hit.id()) : selectedIds.add(hit.id());
			setSelection(hit);
			if (!added || selectedIds.isEmpty()) {
				commands.select(hit);
				fireItemPressed();
				redraw();
				return;
			}
			commands.selectMany(hit.kind(), hit.id(), List.copyOf(selectedIds));
		} else {
			selectedIds.clear();
			setSelection(hit);
			if (isMultiSelectableKind(hit.kind())) {
				// Single blocks of a multi-selectable kind join the multi-selection so a follow-up
				// Ctrl+click extends it and Delete uses the same batched path.
				selectedIds.add(hit.id());
				commands.selectMany(hit.kind(), hit.id(), List.copyOf(selectedIds));
			} else {
				commands.select(hit);
			}
		}
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

	/** Whether a selection kind supports Ctrl/Cmd+click multi-selection. */
	private static boolean isMultiSelectableKind(String kind) {
		return TimelineSelection.ZOOM.equals(kind) || TimelineSelection.ANNOTATION.equals(kind)
				|| TimelineSelection.AUDIO.equals(kind);
	}

	/**
	 * Right-click on a clip: select it and open the fade menu at the pointer. A right-click
	 * anywhere else keeps the plain OS behaviour (nothing to open on empty lanes yet).
	 */
	private void openClipContextMenu(double x, double canvasY) {
		ProjectEditorState project = dataSource.project();
		if (project == null) {
			return;
		}
		TimelineSelection hit = hitTest(x, contentY(canvasY));
		if (hit == null || !TimelineSelection.CLIP.equals(hit.kind())) {
			return;
		}
		if (project.clipRegions().stream().noneMatch(clip -> clip.id().equals(hit.id()))) {
			return;
		}
		selectedIds.clear();
		setSelection(hit);
		commands.select(hit);
		fireItemPressed();
		redraw();
		showFadeMenu(hit.id(), x, canvasY + TLBAR_HEIGHT);
	}

	/**
	 * The clip's outgoing fade picker as a kit menu at the pointer: None / Fade through black, plus
	 * duration presets once a fade is set (the inspector slider covers fine tuning). Edits go
	 * through {@code TimelineCommands.setClipFade}, so undo/redo and the store publish like any
	 * other timeline gesture.
	 */
	private void showFadeMenu(String clipId, double canvasX, double viewY) {
		ClipRegion clip = null;
		for (ClipRegion candidate : dataSource.project().clipRegions()) {
			if (candidate.id().equals(clipId)) {
				clip = candidate;
				break;
			}
		}
		if (clip == null) {
			return;
		}
		ClipTransition transition = clip.transitionOut();
		boolean fade = transition != null && transition.kind() == ClipTransitionKind.FADE_BLACK;

		// Invisible anchor at the pointer: the kit Popup places content beside its owner node.
		Region anchor = new Region();
		anchor.setManaged(false);
		anchor.resizeRelocate(canvasX, viewY, 1, 1);
		getChildren().add(anchor);

		VBox menu = new VBox();
		menu.getStyleClass().add("menu");
		VBox content = new VBox(menu);
		content.getStyleClass().add("popover");
		Label heading = new Label("Fade out");
		heading.getStyleClass().add("menu__label");
		menu.getChildren().add(heading);

		Popup[] holder = new Popup[1];
		menu.getChildren().add(fadeMenuRow("None", !fade, holder,
				() -> commands.setClipFade(clipId, null)));
		menu.getChildren().add(fadeMenuRow("Fade through black", fade, holder,
				() -> commands.setClipFade(clipId, new ClipTransition(ClipTransitionKind.FADE_BLACK,
						transition != null ? transition.durationMs()
								: ClipTransition.DEFAULT_CLIP_TRANSITION_MS))));
		if (transition != null) {
			Region separator = new Region();
			separator.getStyleClass().add("menu__separator");
			separator.setMaxWidth(Double.MAX_VALUE);
			VBox.setMargin(separator, new Insets(4, 0, 4, 0));
			Label duration = new Label("Duration");
			duration.getStyleClass().add("menu__label");
			menu.getChildren().addAll(separator, duration);
			for (long ms : fadeDurationChoices(transition.durationMs())) {
				menu.getChildren().add(fadeMenuRow(ms + " ms", transition.durationMs() == ms,
						holder, () -> commands.setClipFade(clipId,
								new ClipTransition(ClipTransitionKind.FADE_BLACK, ms))));
			}
		}

		Popup popup = new Popup(anchor, content);
		popup.side(Anchors.Side.BOTTOM).align(Anchors.Align.START).offset(4).modal(true);
		popup.onHide(closed -> getChildren().remove(anchor));
		holder[0] = popup;
		popup.show();
	}

	/** The preset durations plus the current one when the agent set a custom value. */
	private static List<Long> fadeDurationChoices(long currentMs) {
		List<Long> choices = new ArrayList<>();
		for (long preset : FADE_DURATION_CHOICES_MS) {
			choices.add(preset);
		}
		if (!choices.contains(currentMs)) {
			choices.add(currentMs);
			choices.sort(Comparator.naturalOrder());
		}
		return choices;
	}

	/** The marker context menu: rename, promote/demote to chapter, delete. */
	private void showMarkerContextMenu(String markerId, double canvasX, double viewY) {
		TimelineMarker marker = null;
		for (TimelineMarker candidate : dataSource.project().markers()) {
			if (candidate.id().equals(markerId)) {
				marker = candidate;
				break;
			}
		}
		if (marker == null) {
			return;
		}
		TimelineMarker target = marker;
		Region anchor = new Region();
		anchor.setManaged(false);
		anchor.resizeRelocate(canvasX, viewY, 1, 1);
		getChildren().add(anchor);

		VBox menu = new VBox();
		menu.getStyleClass().add("menu");
		VBox content = new VBox(menu);
		content.getStyleClass().add("popover");
		Label heading = new Label(target.name());
		heading.getStyleClass().add("menu__label");
		menu.getChildren().add(heading);

		Popup[] holder = new Popup[1];
		menu.getChildren().add(fadeMenuRow("Rename…", false, holder, () -> {
			if (onMarkerRenameRequested != null) {
				onMarkerRenameRequested.accept(markerId);
			}
		}));
		menu.getChildren().add(fadeMenuRow(target.chapter() ? "Remove chapter"
				: "Promote to chapter", target.chapter(), holder,
				() -> commands.setMarkerChapter(markerId, !target.chapter())));
		Region separator = new Region();
		separator.getStyleClass().add("menu__separator");
		separator.setMaxWidth(Double.MAX_VALUE);
		VBox.setMargin(separator, new Insets(4, 0, 4, 0));
		menu.getChildren().add(separator);
		menu.getChildren().add(fadeMenuRow("Delete marker", false, holder,
				() -> commands.deleteMarker(markerId)));

		Popup popup = new Popup(anchor, content);
		popup.side(Anchors.Side.BOTTOM).align(Anchors.Align.START).offset(4).modal(true);
		popup.onHide(closed -> getChildren().remove(anchor));
		holder[0] = popup;
		popup.show();
	}

	/** One menu row with an optional check mark; clicking hides the popup and runs the action. */
	private HBox fadeMenuRow(String label, boolean checked, Popup[] popup, Runnable action) {
		HBox row = new HBox();
		row.getStyleClass().add("menu-item");
		row.setAlignment(Pos.CENTER_LEFT);
		row.setSpacing(12);
		Label text = new Label(label);
		text.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(text, Priority.ALWAYS);
		Icon check = Icon.of(Icons.Check, 12, "bold");
		check.getStyleClass().add("list-box-item__indicator");
		check.setVisible(checked);
		row.getChildren().addAll(text, check);
		row.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
			popup[0].hide();
			action.run();
		});
		return row;
	}

	private void handleMouseDragged(MouseEvent event) {
		dragTo(event.getX(), contentY(event.getY()));
	}

	/** Updates the live preview during a drag; the TestFX harness uses this. */
	public void dragTo(double x, double y) {
		if (dragMarkerId != null) {
			// Ruler drag: the flag follows the pointer; the store commits on release.
			if (!markerDragged && Math.abs(x - dragStartX) <= DRAG_THRESHOLD_PX) {
				return;
			}
			markerDragged = true;
			hoveredMarkerId = dragMarkerId;
			dragMarkerMs = Math.max(0, Math.min(timeAtX(x), fullRange.end()));
			redraw();
			return;
		}
		if (scrubbing) {
			double time = scrubTimeAtX(x);
			if (time != currentTimeMs) {
				setCurrentTimeMs(time);
				commands.scrub(time);
			}
			return;
		}
		if (pressedCameraLane) {
			double time = Math.max(0, Math.min(timeAtX(x), fullRange.end()));
			if (Math.abs(x - dragStartX) > DRAG_THRESHOLD_PX) {
				double start = Math.max(0, Math.min(cameraLanePressMs, time));
				cameraLaneDrawSpan = new Span(start,
						Math.min(fullRange.end(), Math.max(cameraLanePressMs, time)));
				redraw();
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
		Span original = dragOriginalSpan;
		if (original == null) {
			return;
		}
		double deltaMs = (x - dragStartX) * msPerPixel();
		Span proposed = switch (dragMode) {
			case MOVE -> new Span(original.start() + deltaMs, original.end() + deltaMs);
			case RESIZE_LEFT -> new Span(Math.min(original.end() - minItemDurationMs,
					original.start() + deltaMs), original.end());
			case RESIZE_RIGHT -> new Span(original.start(),
					Math.max(original.start() + minItemDurationMs, original.end() + deltaMs));
			case NONE -> original;
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
			if (isGroupMoveDrag()) {
				// Group move: one shared delta clamped against the row's non-selected neighbours
				// (the per-item clamp would pin the dragged block against the moving group).
				double achievedDelta = proposed.start() - original.start();
				double groupDelta = TimelineDndEngine.clampGroupDelta(groupMoveItems(),
						regionSpans(), achievedDelta, fullRange.end());
				proposed = new Span(original.start() + groupDelta, original.end() + groupDelta);
				dragGroupSpans = new java.util.LinkedHashMap<>();
				for (TimelineRenderItem groupItem : items) {
					if (selectedIds.contains(groupItem.id())
							&& item.rowId().equals(groupItem.rowId())) {
						dragGroupSpans.put(groupItem.id(),
								new Span(groupItem.span().start() + groupDelta,
										groupItem.span().end() + groupDelta));
					}
				}
			} else {
				proposed = TimelineDndEngine.clampDraggedSpanToNeighbours(proposed, dragItemId,
						dragRowId, neighbourConfig()).span();
			}
		}
		dragLiveSpan = proposed;
		redraw();
	}

	/** Whether the active drag moves every selected block of the row (multi-selection move). */
	private boolean isGroupMoveDrag() {
		return dragItemId != null && dragRowId != null && selectedIds.size() > 1
				&& selectedIds.contains(dragItemId)
				&& !TimelineConstants.CLIP_ROW_ID.equals(dragRowId);
	}

	/** Original spans of the multi-selected blocks on the dragged row. */
	private List<TimelineRegionSpan> groupMoveItems() {
		List<TimelineRegionSpan> group = new ArrayList<>();
		for (TimelineRenderItem item : items) {
			if (selectedIds.contains(item.id()) && item.rowId().equals(dragRowId)) {
				group.add(new TimelineRegionSpan(item.id(), item.span().start(), item.span().end(),
						item.rowId()));
			}
		}
		return group;
	}

	private void handleMouseReleased(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		releaseAt(event.getX(), contentY(event.getY()));
	}

	/** Commits the drag or treats the press as a click; the TestFX harness uses this. */
	public void releaseAt(double x, double y) {
		if (dragMarkerId != null) {
			String markerId = dragMarkerId;
			boolean moved = markerDragged;
			double time = Math.max(0, Math.min(timeAtX(x), fullRange.end()));
			resetDrag();
			if (moved) {
				commands.moveMarker(markerId, time);
			}
			redraw();
			return;
		}
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
					String rowId = dragRowId;
					if (dragGroupSpans != null && dragGroupSpans.size() > 1) {
						// Multi-selection move: every selected block of the row shifts together.
						commands.moveMany(rowId, dragGroupSpans);
					} else if (rowId != null) {
						commands.move(dragItemId, rowId, dragLiveSpan);
					}
				} else {
					commands.resizeEnd(dragItemId, dragLiveSpan);
				}
			}
			resetDrag();
			redraw();
			return;
		}
		if (pressedCameraLane) {
			Span drawn = cameraLaneDrawSpan;
			resetDrag();
			if (drawn != null) {
				// A drag across empty camera-lane space draws a block over that range.
				commands.addCameraLayout(drawn.start(), drawn.end());
			} else {
				// A plain click is the project-scope gesture: the Webcam panel shows the project
				// settings again and the playhead moves there.
				double time = scrubTimeAtX(x);
				commands.clearSelection();
				setCurrentTimeMs(time);
				commands.seek(time);
			}
			redraw();
			return;
		}
		boolean wasEmpty = pressedEmpty;
		resetDrag();
		if (wasEmpty) {
			selectedIds.clear();
			handleEmptyClick(x, y);
		}
	}

	private void resetDrag() {
		dragMode = DragMode.NONE;
		dragItemId = null;
		dragRowId = null;
		dragOriginalSpan = null;
		dragLiveSpan = null;
		dragGroupSpans = null;
		dragged = false;
		pressedEmpty = false;
		pressedCameraLane = false;
		cameraLaneDrawSpan = null;
		scrubbing = false;
		dragMarkerId = null;
		dragMarkerMs = 0;
		markerDragged = false;
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

	private boolean inGradeRow(double y) {
		RowLayout gradeRow = rowById(TimelineConstants.GRADE_ROW_ID);
		return gradeRow != null && y >= gradeRow.top() && y <= gradeRow.top() + gradeRow.height();
	}

	private boolean inCameraLayoutRow(double y) {
		RowLayout cameraRow = rowById(TimelineConstants.CAMERA_LAYOUT_ROW_ID);
		return cameraRow != null && y >= cameraRow.top()
				&& y <= cameraRow.top() + cameraRow.height();
	}

	/**
	 * The playhead cap on the axis, or the line itself within a few pixels where no block sits
	 * under it (a block under the line keeps its own move/trim gesture).
	 */
	private boolean isOnPlayhead(double x, double y) {
		double playheadX = xForTime(currentTimeMs);
		if (playheadX < GUTTER_WIDTH || playheadX > contentWidth()) {
			return false;
		}
		double capHalfWidth = PLAYHEAD_CAP_WIDTH / 2;
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
		if (inGradeRow(y)) {
			double displayTime = timeAtX(x);
			double start = ClipPresentation.getTimeAtClipSeam(
					Math.max(0, Math.min(displayTime, fullRange.end())), clipPresentation);
			start = Math.max(0, Math.min(start, fullRange.end()));
			double end = Math.min(fullRange.end(), start + Math.min(GHOST_ZOOM_MS, fullRange.end()));
			commands.addGrade(start, end);
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
		// Marker flags live on the ruler: hovering one highlights it, pressing drags it.
		String overMarker = markerAt(x, y);
		if (!java.util.Objects.equals(overMarker, hoveredMarkerId)) {
			hoveredMarkerId = overMarker;
			redraw();
		}
		if (overMarker != null) {
			canvas.setCursor(Cursor.H_RESIZE);
			return;
		}
		int overInk = inkIndexAt(x, y);
		if (overInk != hoveredInkIndex) {
			hoveredInkIndex = overInk;
			redraw();
		}
		if (overInk >= 0) {
			canvas.setCursor(Cursor.HAND);
			return;
		}
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
			} else if (inGradeRow(y)) {
				double displayTime = timeAtX(x);
				double start = ClipPresentation.getTimeAtClipSeam(
						Math.max(0, Math.min(displayTime, fullRange.end())), clipPresentation);
				start = Math.max(0, Math.min(start, fullRange.end()));
				double end = Math.min(fullRange.end(),
						start + Math.min(GHOST_ZOOM_MS, fullRange.end()));
				hoverGhost = ClipPresentation.getRegionDisplaySpan(new Span(start, end),
						clipPresentation);
				hoverGhostKind = "grade";
			} else if (inCameraLayoutRow(y)) {
				// Empty camera-lane space: the drag here draws a block; the click returns to the
				// project layout, so the lane reads as "the project layout applies".
				cursor = Cursor.CROSSHAIR;
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

	/** Port of {@code activateSelectAllZooms}: Ctrl/Cmd+A selects every zoom block. */
	public void selectAllZooms() {
		List<String> zoomIds = new ArrayList<>();
		String primary = selection != null && TimelineSelection.ZOOM.equals(selection.kind())
				? selection.id()
				: null;
		for (TimelineRenderItem item : items) {
			if (TimelineConstants.ZOOM_ROW_ID.equals(item.rowId())) {
				zoomIds.add(item.id());
				if (primary == null) {
					primary = item.id();
				}
			}
		}
		if (zoomIds.isEmpty() || primary == null) {
			return;
		}
		selectedIds.clear();
		selectedIds.addAll(zoomIds);
		setSelection(new TimelineSelection(TimelineSelection.ZOOM, primary));
		commands.selectMany(TimelineSelection.ZOOM, primary, zoomIds);
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
						Math.min(1_000, total), reserved).withInk(dataSource.inkFocus()));
	}

	/** How many zooms and loupes one suggest-zooms run actually added. */
	public record SuggestedAdditions(int zooms, int loupes) {
		public int total() {
			return zooms + loupes;
		}

		/** Toast copy, e.g. "Added 2 zooms and 3 loupes". */
		public String summary() {
			String zoomText = zooms + (zooms == 1 ? " zoom" : " zooms");
			String loupeText = loupes + (loupes == 1 ? " loupe" : " loupes");
			if (loupes == 0) {
				return "Added " + zoomText;
			}
			return zooms == 0 ? "Added " + loupeText : "Added " + zoomText + " and " + loupeText;
		}
	}

	/** Applies the suggest-zooms result through {@link TimelineCommands#addSuggestedZoom}. */
	public SuggestedAdditions addSuggestedZooms() {
		ZoomSuggestionUtils.SuggestionResult result = suggestZooms();
		int zooms = 0;
		for (ZoomSuggestionUtils.SuggestedZoomRegion suggestion : result.suggestions()) {
			commands.addSuggestedZoom(suggestion.startMs(), suggestion.endMs(), suggestion.focus());
			zooms++;
		}
		// Held presses become magnifying-glass loupes instead of click zooms; a span an existing
		// loupe already covers is skipped, so re-running the suggestions adds nothing twice.
		int loupes = 0;
		for (ZoomSuggestionUtils.SuggestedLoupeRegion loupe : result.loupeSuggestions()) {
			if (commands.addSuggestedLoupe(loupe.startMs(), loupe.endMs())) {
				loupes++;
			}
		}
		return new SuggestedAdditions(zooms, loupes);
	}

	private void keyAction(KeyCode code, boolean ctrl, boolean meta, boolean shift, boolean alt,
			@Nullable KeyEvent event) {
		// One host lookup per keystroke: matchesShortcut needs it to pick Ctrl vs Cmd.
		boolean mac = HostOs.current().isMac();
		boolean primary = mac ? meta : ctrl;
		boolean deleteShortcut = TimelineShortcuts.matches(code, ctrl, meta, shift, alt,
				shortcutBindings.deleteSelected(), mac)
				|| ((code == KeyCode.DELETE || code == KeyCode.BACK_SPACE) && !ctrl && !meta
						&& !alt && !shift);
		if (deleteShortcut) {
			if (selection != null || !selectedIds.isEmpty()) {
				commands.deleteSelection();
			}
			selectedIds.clear();
			consume(event);
			return;
		}
		if (code == KeyCode.L && !ctrl && !meta && !alt && !shift) {
			// L: the Camera layout menu, to switch the layout from the playhead on.
			onCameraLayoutShortcut.run();
			consume(event);
			return;
		}
		if (code == KeyCode.ESCAPE) {
			selectedIds.clear();
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
				TimelineShortcuts.Binding.of("a", true, false, false), mac)) {
			selectAllZooms();
			consume(event);
			return;
		}
		if (TimelineShortcuts.matches(code, ctrl, meta, shift, alt, shortcutBindings.addZoom(),
				mac)) {
			addZoomAtPlayhead();
			consume(event);
			return;
		}
		if (code == KeyCode.M && !ctrl && !meta && !alt && !shift) {
			// M: drop a marker at the playhead (the Descript/Screen Studio gesture).
			commands.addMarker(Math.round(currentTimeMs));
			consume(event);
			return;
		}
		if (code == KeyCode.G && !ctrl && !meta && !alt && !shift) {
			// G: drop a Color-track grade at the playhead (same gesture as the empty-lane click).
			addGradeAtPlayhead();
			consume(event);
			return;
		}
		if (TimelineShortcuts.matches(code, ctrl, meta, shift, alt, shortcutBindings.splitClip(),
				mac)) {
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

	/** Places a default-length grade at the playhead if it fits inside the active clip. */
	public void addGradeAtPlayhead() {
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
		double nextGradeStart = total;
		boolean overlapping = false;
		for (TimelineRenderItem item : items) {
			if (!TimelineConstants.GRADE_ROW_ID.equals(item.rowId())) {
				continue;
			}
			if (startPos >= item.span().start() && startPos < item.span().end()) {
				overlapping = true;
			}
			if (item.span().start() > startPos) {
				nextGradeStart = Math.min(nextGradeStart, item.span().start());
			}
		}
		double available = Math.min(clipEnd - startPos, nextGradeStart - startPos);
		if (overlapping || available < defaultDuration) {
			return;
		}
		commands.addGrade(startPos, startPos + defaultDuration);
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
		// The former strip under the clips is now the CAP lane itself.
		RowLayout captionRow = rowById(TimelineConstants.CAPTION_ROW_ID);
		return captionRow != null && y >= captionRow.top()
				&& y <= captionRow.top() + captionRow.height();
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
		AgentTimelineHighlights overlay = agentOverlay;
		if (overlay == null) {
			overlay = new AgentTimelineHighlights();
			overlay.setMouseTransparent(true);
			overlay.setPickOnBounds(false);
			agentOverlay = overlay;
			getChildren().add(overlay);
		}
		final AgentTimelineHighlights mounted = overlay;
		mounted.timeAxis(new AgentTimelineHighlights.TimeAxis() {
			@Override
			public double xForMs(double timeMs) {
				return xForTime(timeMs);
			}

			@Override
			public double widthForMs(double durationMs) {
				return msPerPixel() <= 0 ? 0 : durationMs / msPerPixel();
			}
		});
		mounted.range((long) range.start(), (long) range.end());
		agentHighlightsListener = change -> {
			mounted.range((long) range.start(), (long) range.end());
			mounted.setHighlights(controller.highlights());
		};
		controller.highlights().addListener(agentHighlightsListener);
		mounted.setHighlights(controller.highlights());
		requestLayout();
	}

	/** The mounted agent overlay, or null (tests/shell inspection). */
	public @Nullable AgentTimelineHighlights agentOverlay() {
		return agentOverlay;
	}

	/** What the L key does (opens the Camera layout menu in the editor). */
	public void setOnCameraLayoutShortcut(Runnable action) {
		this.onCameraLayoutShortcut = action;
	}

	/** Reports the source cue when a caption block is selected on the timeline. */
	public void setOnCaptionSelected(java.util.function.Consumer<String> listener) {
		this.onCaptionSelected = listener;
	}

	/** Asks the shell to open the rename prompt for a marker (double click / Enter / context menu). */
	public void setOnMarkerRenameRequested(java.util.function.Consumer<String> listener) {
		this.onMarkerRenameRequested = listener;
	}

	/** Reports a marker selected on the ruler (so the shell can seek to it and refresh panels). */
	public void setOnMarkerSelected(java.util.function.Consumer<String> listener) {
		this.onMarkerSelected = listener;
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
			selectedIds.clear();
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
	public double rowHeight(String rowId) {
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
		if (pressedCameraLane && cameraLaneDrawSpan != null) {
			// The block being drawn on the empty Camera lane.
			drawDragGhost(graphics, cameraLaneDrawSpan,
					rowById(TimelineConstants.CAMERA_LAYOUT_ROW_ID));
		}
		if (dragMode != DragMode.NONE && dragLiveSpan != null) {
			RowLayout row = rowById(dragRowId);
			if (dragMode == DragMode.MOVE && dragGroupSpans != null && dragGroupSpans.size() > 1) {
				// Multi-selection move: every block of the group previews its translated span.
				for (Span groupSpan : dragGroupSpans.values()) {
					drawDragGhost(graphics, groupSpan, row);
				}
			} else {
				drawDragGhost(graphics, dragLiveSpan, row);
			}
		}
		if (hoverGhost != null) {
			double startX = xForTime(hoverGhost.start());
			double width = Math.max(4, xForTime(hoverGhost.end()) - startX);
			double top;
			double height;
			if ("caption".equals(hoverGhostKind)) {
				RowLayout captionRow = rowById(TimelineConstants.CAPTION_ROW_ID);
				top = captionRow == null ? AXIS_HEIGHT : captionRow.top();
				height = captionRow == null ? 24 : captionRow.height();
			} else if ("grade".equals(hoverGhostKind)) {
				RowLayout gradeRow = rowById(TimelineConstants.GRADE_ROW_ID);
				top = gradeRow == null ? AXIS_HEIGHT : gradeRow.top() + gradeRow.height() * 0.1;
				height = gradeRow == null ? 24 : gradeRow.height() * 0.8;
			} else {
				RowLayout zoomRow = rowById(TimelineConstants.ZOOM_ROW_ID);
				top = zoomRow == null ? AXIS_HEIGHT : zoomRow.top() + zoomRow.height() * 0.1;
				height = zoomRow == null ? 24 : zoomRow.height() * 0.8;
			}
			graphics.setFill(Color.web("#2fb3a3", 0.18));
			graphics.fillRoundRect(startX, top, width, height, 3, 3);
			graphics.setStroke(Color.web("#3fd4c1", 0.7));
			graphics.setLineWidth(1);
			graphics.setLineDashes(4, 4);
			graphics.strokeRoundRect(startX + 0.5, top + 0.5, Math.max(1, width - 1), height - 1, 3,
					3);
			graphics.setLineDashes(null);
			double centerX = startX + width / 2;
			double centerY = top + height / 2;
			graphics.setFill(Color.web("#2fb3a3", 0.9));
			graphics.fillOval(centerX - 9, centerY - 9, 18, 18);
			graphics.setFont(Font.font("DM Sans", FontWeight.BOLD, 13));
			graphics.setFill(CYAN_INK);
			graphics.fillText("+", centerX - 4.5, centerY + 5);
		}
	}

	/** The translucent drag preview of one span inside its row. */
	private void drawDragGhost(GraphicsContext graphics, Span span, @Nullable RowLayout row) {
		double startX = xForTime(span.start());
		double width = Math.max(4, xForTime(span.end()) - startX);
		double top = row == null ? AXIS_HEIGHT : row.top() + 2;
		double height = row == null ? 24 : row.height() - 4;
		graphics.setFill(Color.web("#2fb3a3", 0.25));
		graphics.fillRoundRect(startX, top, width, height, 3, 3);
		graphics.setStroke(Color.web("#3fd4c1", 0.8));
		graphics.setLineWidth(1.5);
		graphics.setLineDashes(5, 4);
		graphics.strokeRoundRect(startX + 0.5, top + 0.5, Math.max(1, width - 1), height - 1, 3,
				3);
		graphics.setLineDashes(null);
	}

	/** Simulates a primary click at a view-local point (press + release; click semantics). */
	public void clickAt(double x, double y) {
		pressAt(x, y);
		releaseAt(x, y);
	}

	/** Which block is under a point, if any (top-most rows win; the header gutter is inert). */
	public @Nullable TimelineSelection hitTest(double x, double y) {
		if (x < GUTTER_WIDTH) {
			return null;
		}
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
			case ANNOTATION, LOUPE, SPOTLIGHT -> TimelineSelection.ANNOTATION;
			case AUDIO -> TimelineSelection.AUDIO;
			case CAPTION -> TimelineSelection.CAPTION;
			case KEYSTROKE -> TimelineSelection.KEYSTROKE;
			case CAMERA_LAYOUT -> TimelineSelection.CAMERA_LAYOUT;
			case ELEMENT -> TimelineSelection.ELEMENT;
			case GRADE -> TimelineSelection.GRADE;
			case TRIM, SPEED -> TimelineSelection.CLIP;
		};
	}

	/* ---------- painting ---------- */

	private void redraw() {
		GraphicsContext graphics = canvas.getGraphicsContext2D();
		double width = contentWidth();
		double height = canvas.getHeight();
		graphics.setFill(LANES_BG);
		graphics.fillRect(0, 0, width, height);
		// The ruler strip (mock .ruler) and the header corner share the chrome surface.
		graphics.setFill(CHROME_BG);
		graphics.fillRect(0, 0, width, AXIS_HEIGHT);
		blockBounds.clear();
		cameraSwitchBounds.clear();
		cameraSwitchOutBounds.clear();
		markerBounds.clear();
		if (agentOverlay != null) {
			agentOverlay.range((long) range.start(), (long) range.end());
		}
		if (items.isEmpty() && dataSource.project() == null) {
			drawGutterChrome(graphics, width, height);
			return;
		}
		syncScrollBars();
		drawGrid(graphics, width, height);
		drawRuler(graphics, width);
		// Marker flags go over the ruler's own ticks and time labels (their pills cover them).
		drawMarkers(graphics, width);
		// Lanes scroll under the pinned ruler: clip right of the gutter, below the axis.
		graphics.save();
		graphics.beginPath();
		graphics.rect(GUTTER_WIDTH, AXIS_HEIGHT, Math.max(0, width - GUTTER_WIDTH),
				Math.max(0, height - AXIS_HEIGHT));
		graphics.clip();
		graphics.translate(0, -scrollY);
		drawRows(graphics, width);
		drawLaneSeparators(graphics, width);
		drawInteractionOverlays(graphics);
		drawCutPreview(graphics, width);
		drawSmartFramingMoves(graphics, width);
		drawEyeContactStrip(graphics, width);
		drawInkMarks(graphics, width);
		graphics.restore();
		// Track headers (mock .tlheads) live in the fixed gutter and scroll with the lanes.
		graphics.save();
		graphics.beginPath();
		graphics.rect(0, AXIS_HEIGHT, GUTTER_WIDTH, Math.max(0, height - AXIS_HEIGHT));
		graphics.clip();
		graphics.translate(0, -scrollY);
		drawTrackHeaders(graphics);
		graphics.restore();
		drawAgentHighlights(graphics, width);
		drawGutterChrome(graphics, width, height);
		drawPlayhead(graphics, height);
	}

	private void drawGrid(GraphicsContext graphics, double width, double height) {
		TimelineScale.AxisScale axis = TimelineScale.calculateAxisScale(range.duration(), width);
		graphics.setStroke(GRID_LINE);
		graphics.setLineWidth(1);
		double first = Math.floor(range.start() / Math.max(1, axis.gridMs())) * axis.gridMs();
		for (double time = first; time <= range.end(); time += axis.gridMs()) {
			double x = Math.round(xForTime(time)) + 0.5;
			if (x >= GUTTER_WIDTH && x <= width) {
				graphics.strokeLine(x, AXIS_HEIGHT, x, height);
			}
		}
	}

	private void drawRuler(GraphicsContext graphics, double width) {
		TimelineScale.AxisScale axis = TimelineScale.calculateAxisScale(range.duration(), width);
		updateScaleLabel(axis);
		graphics.setFont(AXIS_FONT);
		graphics.setFill(FAINT);
		graphics.setLineWidth(1);
		double first = Math.max(0,
				Math.floor(range.start() / Math.max(1, axis.intervalMs())) * axis.intervalMs());
		for (double time = first; time <= range.end(); time += axis.intervalMs()) {
			double x = Math.round(xForTime(time)) + 0.5;
			if (x < GUTTER_WIDTH || x > width) {
				continue;
			}
			// Mock .ruler: a 9px big tick under each label, 5px small ticks between (drawGrid).
			graphics.setStroke(Color.web("#39454e"));
			graphics.strokeLine(x, AXIS_HEIGHT - 9, x, AXIS_HEIGHT);
			graphics.fillText(TimelineScale.formatTimeLabel(time, axis.intervalMs()), x + 4, 14);
		}
		// Small ticks (mock .ruler i) at the grid positions between labels.
		double gridFirst = Math.floor(range.start() / Math.max(1, axis.gridMs())) * axis.gridMs();
		graphics.setStroke(Color.web("#28323a"));
		for (double time = gridFirst; time <= range.end(); time += axis.gridMs()) {
			double x = Math.round(xForTime(time)) + 0.5;
			if (x < GUTTER_WIDTH || x > width) {
				continue;
			}
			graphics.strokeLine(x, AXIS_HEIGHT - 5, x, AXIS_HEIGHT);
		}
	}

	/**
	 * Markers on the ruler: a small flag at each projected marker, filled when it is a chapter.
	 * Chapter flags carry their title in the ruler strip; markers only get a tooltip-ish short
	 * label on hover so a busy ruler stays readable.
	 */
	private void drawMarkers(GraphicsContext graphics, double width) {
		markerBounds.clear();
		if (markerFragments.isEmpty()) {
			return;
		}
		graphics.setFont(MARKER_FONT);
		double flagTop = AXIS_HEIGHT - MARKER_FLAG_HEIGHT;
		for (int index = 0; index < markerFragments.size(); index++) {
			MarkerTimeline.Fragment fragment = markerFragments.get(index);
			TimelineMarker marker = fragment.marker();
			boolean dragged = marker.id().equals(dragMarkerId) && markerDragged;
			// The dragged flag previews at the pointer until the store commits.
			double markerMs = dragged ? dragMarkerMs : fragment.timelineMs();
			double x = xForTime(markerMs);
			if (x < GUTTER_WIDTH || x > width) {
				continue;
			}
			// The label stops before the next flag (and before the playhead-style edge of the view).
			double nextMarkerX = width;
			for (int next = index + 1; next < markerFragments.size(); next++) {
				double nextX = xForTime(markerFragments.get(next).timelineMs());
				if (nextX > x) {
					nextMarkerX = nextX;
					break;
				}
			}
			boolean hovered = marker.id().equals(hoveredMarkerId) || dragged;
			boolean chapter = marker.chapter();
			// The flag is a small pennant: a vertical hairline down through the ruler, then the
			// head to the right of it, so two markers at the same pixel stay countable.
			graphics.setStroke(chapter ? MARKER_CHAPTER_COLOR : MARKER_LINE);
			graphics.setLineWidth(chapter ? 1.5 : 1);
			graphics.strokeLine(x, flagTop, x, AXIS_HEIGHT);
			graphics.setFill(chapter ? MARKER_CHAPTER_COLOR : MARKER_COLOR);
			graphics.fillPolygon(new double[] {x, x + MARKER_FLAG_WIDTH, x + MARKER_FLAG_WIDTH,
					x}, new double[] {flagTop, flagTop, flagTop + MARKER_FLAG_HEIGHT * 0.75,
							flagTop + MARKER_FLAG_HEIGHT * 0.5}, 4);
			double halfWidth = Math.max(MARKER_GRAB_PX, MARKER_FLAG_WIDTH / 2 + 2);
			markerBounds.put(marker.id(), new BlockBounds(x - halfWidth, 0, halfWidth * 2,
					AXIS_HEIGHT));
			// Chapter titles sit on the ruler strip in a small pill (the ruler's own time labels are
			// underneath them); plain markers show their name only while hovered. The pill is
			// clipped before the next marker so two chapter titles never run into each other.
			String label = chapter || hovered ? marker.name() : null;
			if (label != null && !label.isBlank()) {
				double labelX = x + MARKER_FLAG_WIDTH + 3;
				double available = Math.max(24, Math.min(width, nextMarkerX) - labelX - 4);
				String fitted = fitText(label, Math.max(24, available), MARKER_FONT);
				double textWidth = textWidth(fitted, MARKER_FONT);
				graphics.setFill(MARKER_PILL_FILL);
				graphics.fillRoundRect(labelX - 2, flagTop - 1, textWidth + 4,
						MARKER_FLAG_HEIGHT, 2, 2);
				graphics.setFill(hovered ? MARKER_TEXT : MARKER_CHAPTER_TEXT);
				graphics.fillText(fitted, labelX, flagTop + MARKER_FLAG_HEIGHT - 3);
			}
		}
	}

	/** The toolbar's live readout of the ruler division (mock "1 lane = 5 s", renamed). */
	private void updateScaleLabel(TimelineScale.AxisScale axis) {
		long interval = axis.intervalMs();
		String text = interval >= 1000
				? "1 div = " + (interval / 1000) + " s"
				: "1 div = " + interval + " ms";
		if (!text.equals(scaleLabel.getText())) {
			scaleLabel.setText(text);
		}
	}

	/** Gutter edge, ruler hairline and the TRACKS corner (mock .tlheads .corner). */
	private void drawGutterChrome(GraphicsContext graphics, double width, double height) {
		graphics.setStroke(HAIRLINE);
		graphics.setLineWidth(1);
		graphics.strokeLine(GUTTER_WIDTH - 0.5, AXIS_HEIGHT, GUTTER_WIDTH - 0.5, height);
		graphics.strokeLine(0, AXIS_HEIGHT - 0.5, width, AXIS_HEIGHT - 0.5);
		graphics.setFont(AXIS_FONT);
		graphics.setFill(FAINT);
		graphics.fillText("TRACKS", 8, 14);
	}

	/** 1px lane separators (mock .lane border-bottom) at every row bottom. */
	private void drawLaneSeparators(GraphicsContext graphics, double width) {
		graphics.setStroke(HAIRLINE);
		graphics.setLineWidth(1);
		for (RowLayout row : rowLayouts) {
			double y = Math.round(row.top() + row.height()) - 0.5;
			graphics.strokeLine(GUTTER_WIDTH, y, width, y);
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
			} else if (TimelineConstants.CAPTION_ROW_ID.equals(row.id())) {
				drawCaptions(graphics, row, width);
			} else if (TimelineConstants.ZOOM_ROW_ID.equals(row.id())) {
				for (TimelineRenderItem item : items) {
					if (TimelineConstants.ZOOM_ROW_ID.equals(item.rowId())) {
						drawZoom(graphics, item, row, width);
					}
				}
			} else if (TimelineConstants.GRADE_ROW_ID.equals(row.id())) {
				for (TimelineRenderItem item : items) {
					if (TimelineConstants.GRADE_ROW_ID.equals(item.rowId())) {
						drawGrade(graphics, item, row, width);
					}
				}
			} else if (row.id().startsWith(TimelineConstants.ELEMENTS_ROW_ID)) {
				for (TimelineRenderItem item : items) {
					if (row.id().equals(item.rowId())) {
						drawElement(graphics, item, row, width);
					}
				}
			} else if (TimelineConstants.CAMERA_LAYOUT_ROW_ID.equals(row.id())) {
				drawProjectLayoutLane(graphics, row, width);
				for (TimelineRenderItem item : items) {
					if (row.id().equals(item.rowId())) {
						drawCameraLayout(graphics, item, row, width);
					}
				}
			} else if (TimelineRows.isKeystrokeLaneRowId(row.id())) {
				for (TimelineRenderItem item : items) {
					if (row.id().equals(item.rowId())) {
						drawKeystroke(graphics, item, row, width);
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
		// Mock .clip: inset 5px top/bottom, 3px radius, label on a dark pill.
		double blockHeight = row.height() - 10;
		double blockTop = row.top() + 5;
		List<TimelineRenderItem> clips = items.stream()
				.filter(item -> TimelineConstants.CLIP_ROW_ID.equals(item.rowId()))
				.toList();
		for (int index = 0; index < clips.size(); index++) {
			TimelineRenderItem item = clips.get(index);
			Span display = displaySpanFor(item);
			double startX = xForTime(display.start());
			double endX = xForTime(display.end());
			double blockWidth = Math.max(6, endX - startX);
			if (endX < GUTTER_WIDTH || startX > width) {
				continue;
			}
			double x = Math.max(-blockWidth, startX);
			boolean muted = Boolean.TRUE.equals(item.muted());
			if (muted) {
				// Mock .clip--muted: the whole block at half opacity.
				graphics.setGlobalAlpha(0.5);
			}
			graphics.setFill(CLIP_FILL);
			graphics.fillRoundRect(x, blockTop, blockWidth, blockHeight, 3, 3);
			drawFilmstrip(graphics, item, x, blockTop, blockWidth, blockHeight);
			drawFade(graphics, item, x, blockTop, blockWidth, blockHeight);
			drawFadeBoundaryMark(graphics, item, endX, blockTop, blockHeight);
			graphics.setStroke(CLIP_BORDER);
			graphics.setLineWidth(1);
			graphics.strokeRoundRect(x + 0.5, blockTop + 0.5, Math.max(1, blockWidth - 1),
					blockHeight - 1, 3, 3);
			drawClipIndicators(graphics, item, x, blockTop, blockWidth, blockHeight);
			drawBlockLabel(graphics, item.label(), x + 6, blockTop + blockHeight / 2,
					blockWidth - 12, CLIP_TEXT);
			if (isSelected(TimelineSelection.CLIP, item.id())) {
				drawSelection(graphics, x, blockTop, blockWidth, blockHeight, CLIP_SELECTED);
			}
			graphics.setGlobalAlpha(1);
			blockBounds.put(item.id(), new BlockBounds(x, blockTop, blockWidth, blockHeight));
		}
		for (int index = 0; index + 1 < clips.size(); index++) {
			Span left = displaySpanFor(clips.get(index));
			Span right = displaySpanFor(clips.get(index + 1));
			if (left.end() == right.start()) {
				double seam = (xForTime(left.end()) + xForTime(right.start())) / 2;
				graphics.setFill(Color.web("#e9f1f4", 0.88));
				graphics.fillRoundRect(seam - 2, blockTop + blockHeight * 0.2, 4,
						blockHeight * 0.6, 2, 2);
			}
		}
	}

	/**
	 * A clip label on the mock's dark pill ({@code .clip__lbl}): mono 9.5px, fitted, centered
	 * vertically at {@code centerY}.
	 */
	private void drawBlockLabel(GraphicsContext graphics, String text, double x, double centerY,
			double maxWidth, Color textColor) {
		if (text == null || text.isBlank() || maxWidth < 24) {
			return;
		}
		String fitted = fitText(text, maxWidth, BLOCK_FONT);
		double textWidth = textWidth(fitted, BLOCK_FONT);
		graphics.setFill(BADGE_FILL);
		graphics.fillRoundRect(x - 4, centerY - 8, textWidth + 8, 15, 2, 2);
		graphics.setFont(BLOCK_FONT);
		graphics.setFill(textColor);
		graphics.fillText(fitted, x, centerY + 3.5);
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

	/**
	 * The fade diamond at a clip's outgoing boundary: a light marker on the seam so a boundary with
	 * a transition reads at a glance (the shading inside the clip comes from {@link #drawFade}).
	 */
	private void drawFadeBoundaryMark(GraphicsContext graphics, TimelineRenderItem item,
			double endX, double blockTop, double blockHeight) {
		if (item.fadeOutMs() == null || item.fadeOutMs() <= 0) {
			return;
		}
		double centerY = blockTop + blockHeight / 2;
		double half = 4.5;
		double[] xs = {endX, endX + half, endX, endX - half};
		double[] ys = {centerY - half, centerY, centerY + half, centerY};
		graphics.setFill(FADE_MARK_FILL);
		graphics.fillPolygon(xs, ys, 4);
		graphics.setStroke(FADE_MARK_OUTLINE);
		graphics.setLineWidth(1);
		graphics.strokePolygon(xs, ys, 4);
	}

	private void drawFade(GraphicsContext graphics, TimelineRenderItem item, double x, double y,
			double width, double height) {
		double spanDurationMs = Math.max(1, item.span().duration());
		if (item.fadeInMs() != null && item.fadeInMs() > 0) {
			double fadeWidth = Math.max(6,
					Math.min(width, width * (item.fadeInMs() / spanDurationMs)));
			graphics.setFill(Color.web("#000000", 0.55));
			graphics.fillRoundRect(x, y, fadeWidth, height, 3, 3);
			graphics.setStroke(Color.web("#e9f1f4", 0.9));
			graphics.setLineWidth(1.5);
			graphics.strokeLine(x + 1, y + height - 2, x + fadeWidth - 1, y + 2);
		}
		if (item.fadeOutMs() != null && item.fadeOutMs() > 0) {
			double fadeWidth = Math.max(6,
					Math.min(width, width * (item.fadeOutMs() / spanDurationMs)));
			graphics.setFill(Color.web("#000000", 0.55));
			graphics.fillRoundRect(x + width - fadeWidth, y, fadeWidth, height, 3, 3);
			graphics.setStroke(Color.web("#e9f1f4", 0.9));
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
						2, 2);
				graphics.setFont(SMALL_FONT);
				graphics.setFill(BADGE_TEXT);
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
				graphics.fillRoundRect(x + 8, y + 4, labelWidth, 16, 2, 2);
				graphics.setFont(SMALL_FONT);
				graphics.setFill(BADGE_TEXT);
				graphics.fillText(label, x + 14, y + 16);
			}
		}
	}

	private void drawCaptions(GraphicsContext graphics, RowLayout captionRow, double width) {
		// Caption cues are blocks on their own CAP lane (mock .clip--cap).
		double captionTop = captionRow.top() + 5;
		double captionHeight = captionRow.height() - 10;
		for (TimelineRenderItem item : items) {
			if (item.variant() != TimelineRenderItem.Variant.CAPTION) {
				continue;
			}
			// Caption items are already projected onto the edited timeline (CaptionTimeline),
			// so their span is the display span inside the caption lane.
			Span embedded = item.span();
			if (embedded == null) {
				continue;
			}
			double startX = xForTime(embedded.start());
			double endX = xForTime(embedded.end());
			double blockWidth = Math.max(6, endX - startX);
			if (endX < GUTTER_WIDTH || startX > width) {
				continue;
			}
			double x = Math.max(-blockWidth, startX);
			graphics.setFill(CAPTION_FILL);
			graphics.fillRoundRect(x, captionTop, blockWidth, captionHeight, 3, 3);
			graphics.setStroke(CAPTION_BORDER);
			graphics.setLineWidth(1);
			graphics.strokeRoundRect(x + 0.5, captionTop + 0.5, Math.max(1, blockWidth - 1),
					captionHeight - 1, 3, 3);
			drawBlockLabel(graphics, item.label(), x + 6, captionTop + captionHeight / 2,
					blockWidth - 12, CAPTION_TEXT);
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
		if (endX < GUTTER_WIDTH || startX > width) {
			return;
		}
		double x = Math.max(-blockWidth, startX);
		double height = row.height() - 10;
		double y = row.top() + 5;
		graphics.setFill(ZOOM_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 3, 3);
		graphics.setStroke(ZOOM_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 3, 3);
		String label = zoomLabel(item);
		double labelX = x + 6;
		double labelWidth = blockWidth - 12;
		// Deep-zoom stages run the sharpening pass; a sparkle before the scale marks them. Stages
		// the export will additionally upscale get the ring, so the timeline says which are which.
		if (item.zoomDepth() != null && ZoomSharpen.isSharpened(item.zoomDepth().scale())) {
			drawSharpenSparkle(graphics, x + 12, y + height / 2);
			labelX = x + 19;
			labelWidth = blockWidth - 25;
			if (isExportUpscaled(item.zoomDepth().scale())) {
				drawUpscaleRing(graphics, x + 12, y + height / 2);
			}
		}
		drawBlockLabel(graphics, label, labelX, y + height / 2, labelWidth, CYAN);
		if (isSelected(TimelineSelection.ZOOM, item.id())) {
			drawSelection(graphics, x, y, blockWidth, height, ZOOM_SELECTED);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
	}

	/** Whether an export will upscale a stage at this depth scale (0 = marker off). */
	private boolean isExportUpscaled(double scale) {
		return exportUpscaleFromScale > 0 && scale >= exportUpscaleFromScale;
	}

	/**
	 * Marks the zoom stages an export upscales with the optional super-resolution model. The
	 * preview never runs that pass, so the ring is a promise about the exported file, not about
	 * what is on screen right now.
	 *
	 * @param fromScale the camera scale from which exports upscale, or 0 to remove the marker
	 */
	public void setExportUpscaleFromScale(double fromScale) {
		double next = Double.isFinite(fromScale) && fromScale > 0 ? fromScale : 0;
		if (next == exportUpscaleFromScale) {
			return;
		}
		exportUpscaleFromScale = next;
		redraw();
	}

	/** The ring around the sharpen sparkle: an export will upscale this stage. */
	private void drawUpscaleRing(GraphicsContext graphics, double cx, double cy) {
		graphics.setStroke(ZOOM_UPSCALE_GLYPH);
		graphics.setLineWidth(1.2);
		graphics.strokeOval(cx - 6.5, cy - 6.5, 13, 13);
	}

	/** The 4-point sparkle marking zoom stages that run the deep-zoom sharpening pass. */
	private void drawSharpenSparkle(GraphicsContext graphics, double cx, double cy) {
		double r = 4;
		double pull = r * 0.22;
		graphics.setFill(ZOOM_SHARPEN_GLYPH);
		graphics.beginPath();
		graphics.moveTo(cx, cy - r);
		graphics.quadraticCurveTo(cx + pull, cy - pull, cx + r, cy);
		graphics.quadraticCurveTo(cx + pull, cy + pull, cx, cy + r);
		graphics.quadraticCurveTo(cx - pull, cy + pull, cx - r, cy);
		graphics.quadraticCurveTo(cx - pull, cy - pull, cx, cy - r);
		graphics.fill();
	}

	/** The Color-track grade block: a warm-edged card whose label names the applied sliders. */
	private void drawGrade(GraphicsContext graphics, TimelineRenderItem item, RowLayout row,
			double width) {
		Span display = getRegionDisplaySpan(item.span());
		double startX = xForTime(display.start());
		double endX = xForTime(display.end());
		double blockWidth = Math.max(6, endX - startX);
		if (endX < GUTTER_WIDTH || startX > width) {
			return;
		}
		double x = Math.max(-blockWidth, startX);
		double height = row.height() - 10;
		double y = row.top() + 5;
		graphics.setFill(GRADE_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 3, 3);
		graphics.setStroke(GRADE_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 3, 3);
		drawBlockLabel(graphics, item.label(), x + 6, y + height / 2, blockWidth - 12, AMBER);
		if (isSelected(TimelineSelection.GRADE, item.id())) {
			drawSelection(graphics, x, y, blockWidth, height, GRADE_SELECTED);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
	}

	/**
	 * The Camera layout lane behind its blocks: a dashed outline over the whole timeline, so the
	 * gaps read as "the project layout applies here".
	 */
	private void drawProjectLayoutLane(GraphicsContext graphics, RowLayout row, double width) {
		double startX = Math.max(GUTTER_WIDTH, xForTime(fullRange.start()));
		double endX = Math.min(width, xForTime(fullRange.end()));
		if (endX <= startX) {
			return;
		}
		double height = row.height() - 10;
		double y = row.top() + 5;
		graphics.setStroke(CAMERA_LAYOUT_LANE);
		graphics.setLineWidth(1);
		graphics.setLineDashes(4, 4);
		graphics.strokeRoundRect(startX + 0.5, y + 0.5, endX - startX - 1, height - 1, 3, 3);
		graphics.setLineDashes();
	}

	/**
	 * Marks the elements whose component failed (red badge on their blocks); the app feeds this
	 * from the session's element diagnostics.
	 */
	public void setElementErrors(java.util.Set<String> elementIds) {
		elementErrorIds = java.util.Set.copyOf(elementIds);
		redraw();
	}

	/** Adds one block per element, greedily packed into lanes so overlaps stay readable. */
	private List<TimelineRenderItem> withElementItems(List<TimelineRenderItem> base,
			List<dev.gravaai.core.project.SceneElement> elements) {
		java.util.Map<String, dev.gravaai.core.project.SceneElement> byId = new java.util.HashMap<>();
		if (elements.isEmpty()) {
			elementsById = byId;
			return base;
		}
		List<TimelineRenderItem> result = new ArrayList<>(base);
		List<Long> laneEnds = new ArrayList<>();
		for (dev.gravaai.core.project.SceneElement element
				: dev.gravaai.core.project.SceneElement.byStart(elements)) {
			byId.put(element.id(), element);
			int lane = 0;
			while (lane < laneEnds.size() && laneEnds.get(lane) > element.startMs()) {
				lane++;
			}
			if (lane == laneEnds.size()) {
				laneEnds.add(element.endMs());
			} else {
				laneEnds.set(lane, element.endMs());
			}
			String label = element.component().startsWith(
					dev.gravaai.core.project.ElementComponent.BUILTIN_PREFIX)
							? element.component().substring(
									dev.gravaai.core.project.ElementComponent.BUILTIN_PREFIX.length())
							: element.component();
			result.add(new TimelineRenderItem(element.id(), TimelineConstants.ELEMENTS_ROW_ID + "-"
					+ lane, new Span(element.startMs(), element.endMs()), null, label + elementHint(element),
					null, null, null, null, null, null, null, null, null, null, null,
					TimelineRenderItem.Variant.ELEMENT));
		}
		elementsById = byId;
		return List.copyOf(result);
	}

	/** The first string prop (title, name, label, text) as a hint after the component name. */
	private static String elementHint(dev.gravaai.core.project.SceneElement element) {
		for (String key : List.of("title", "name", "label", "text")) {
			var value = element.props().get(key);
			if (value != null && value.isString() && !value.asString().isBlank()) {
				return " · " + value.asString();
			}
		}
		return "";
	}

	private void drawElement(GraphicsContext graphics, TimelineRenderItem item, RowLayout row,
			double width) {
		Span display = getRegionDisplaySpan(item.span());
		double startX = xForTime(display.start());
		double endX = xForTime(display.end());
		if (endX < GUTTER_WIDTH || startX > width) {
			return;
		}
		double blockWidth = Math.max(6, endX - startX);
		double x = Math.max(-blockWidth, startX);
		double height = row.height() - 10;
		double y = row.top() + 5;
		boolean failing = elementErrorIds.contains(item.id());
		graphics.setFill(ELEMENT_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 3, 3);
		// A spark glyph marks animated content; the block border turns red when it fails.
		graphics.setStroke(failing ? ELEMENT_ERROR : ELEMENT_BORDER);
		graphics.setLineWidth(failing ? 2 : 1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 3, 3);
		double labelX = x + 6;
		if (blockWidth > 18) {
			graphics.setFill(ELEMENT_BORDER);
			double cx = x + 10;
			double cy = y + height / 2;
			graphics.fillPolygon(new double[] {cx, cx + 2, cx + 5, cx + 2, cx, cx - 2, cx - 5, cx - 2},
					new double[] {cy - 5, cy - 2, cy, cy + 2, cy + 5, cy + 2, cy, cy - 2}, 8);
			labelX = x + 18;
		}
		// Motion: shaded ramps where the box animates in and out, so presets are visible on the
		// lane; a trails glyph marks motion blur and a target marks "follows zoom" (scene space).
		dev.gravaai.core.project.SceneElement model = elementsById.get(item.id());
		double glyphs = 0;
		if (model != null) {
			long duration = Math.max(1, model.durationMs());
			double pxPerMs = blockWidth / duration;
			long entrance = 0;
			long exit = 0;
			for (dev.gravaai.core.project.ElementMotion.Track track : model.motion()) {
				for (dev.gravaai.core.project.ElementMotion.Keyframe key : track.keyframes()) {
					if (key.fromEnd()) {
						exit = Math.max(exit, Math.min(duration / 2, key.atMs()));
					} else {
						entrance = Math.max(entrance, Math.min(duration / 2, key.atMs()));
					}
				}
			}
			graphics.setFill(ELEMENT_RAMP);
			if (entrance > 0) {
				double w = Math.max(3, entrance * pxPerMs);
				graphics.fillPolygon(new double[] {x, x + w, x}, new double[] {y + height, y, y}, 3);
			}
			if (exit > 0) {
				double w = Math.max(3, exit * pxPerMs);
				double right = x + blockWidth;
				graphics.fillPolygon(new double[] {right, right - w, right},
						new double[] {y + height, y, y}, 3);
			}
			double gx = x + blockWidth - 10 - (failing ? 16 : 0);
			double cy = y + height / 2;
			graphics.setStroke(ELEMENT_BORDER);
			graphics.setLineWidth(1.2);
			if (model.blur().enabled() && blockWidth > 60) {
				for (int line = 0; line < 3; line++) {
					double ly = cy - 3 + line * 3;
					graphics.strokeLine(gx - 6 + line, ly, gx + 2, ly);
				}
				gx -= 14;
				glyphs += 14;
			}
			if (model.space() == dev.gravaai.core.project.SceneElement.Space.SCENE && blockWidth > 60) {
				graphics.strokeOval(gx - 4, cy - 4, 8, 8);
				graphics.fillOval(gx - 1.5, cy - 1.5, 3, 3);
				glyphs += 14;
			}
		}
		double badge = failing ? 16 : 0;
		double labelWidth = blockWidth - (labelX - x) - 6 - badge - glyphs;
		if (labelWidth > 12) {
			graphics.setFont(BLOCK_FONT);
			graphics.setFill(BLOCK_TEXT);
			graphics.fillText(fitText(item.label(), labelWidth, BLOCK_FONT), labelX,
					y + height / 2 + 4);
		}
		if (failing && blockWidth > 14) {
			double cx = x + blockWidth - 10;
			double cy = y + height / 2;
			graphics.setFill(ELEMENT_ERROR);
			graphics.fillOval(cx - 6, cy - 6, 12, 12);
			graphics.setFill(Color.WHITE);
			graphics.setFont(BLOCK_FONT);
			graphics.fillText("!", cx - 2, cy + 4);
		}
		if (isSelected(TimelineSelection.ELEMENT, item.id())) {
			drawSelection(graphics, x, y, blockWidth, height, ELEMENT_SELECTED);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
	}

	/** One Camera layout block: tinted by kind (split, bubble, camera only, no camera), named. */
	private void drawCameraLayout(GraphicsContext graphics, TimelineRenderItem item, RowLayout row,
			double width) {
		Span display = getRegionDisplaySpan(item.span());
		double startX = xForTime(display.start());
		double endX = xForTime(display.end());
		double blockWidth = Math.max(6, endX - startX);
		if (endX < GUTTER_WIDTH || startX > width) {
			return;
		}
		CameraLayoutSegment segment = cameraLayouts.get(item.id());
		if (segment == null) {
			return;
		}
		boolean gapAfter = segment.followedByGap(java.util.List.copyOf(cameraLayouts.values()));
		Color fill = switch (segment.layout()) {
			case CameraLayout.Position(var preset) when preset.isSplit() -> CAMERA_SPLIT_FILL;
			case CameraLayout.Position(var preset) -> CAMERA_BUBBLE_FILL;
			case CameraLayout.CameraOnly() -> CAMERA_ONLY_FILL;
			case CameraLayout.HideCamera() -> CAMERA_HIDDEN_FILL;
		};
		double x = Math.max(-blockWidth, startX);
		double height = row.height() - 10;
		double y = row.top() + 5;
		graphics.setFill(fill);
		graphics.fillRoundRect(x, y, blockWidth, height, 3, 3);
		graphics.setStroke(CAMERA_LAYOUT_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 3, 3);
		if (segment.transition() == CameraLayoutSegment.Transition.SMOOTH) {
			// The smooth move's length, shaded at the block start.
			double rampWidth = Math.min(blockWidth,
					xForTime(display.start() + CameraLayoutSegment.SMOOTH_MS) - startX);
			graphics.setFill(CAMERA_LAYOUT_RAMP);
			graphics.fillRoundRect(x, y, Math.max(0, rampWidth), height, 8, 8);
		}
		if (gapAfter && segment.transitionOut() == CameraLayoutSegment.Transition.SMOOTH) {
			// The smooth switch-out's length, shaded in the gap after the block.
			double rampWidth = Math.min(width - endX,
					xForTime(display.end() + CameraLayoutSegment.SMOOTH_MS) - endX);
			graphics.setFill(CAMERA_LAYOUT_RAMP);
			graphics.fillRect(endX, y, Math.max(0, rampWidth), height);
		}
		// Label between the switch diamonds; a dot marks a segment with its own size or style.
		String label = segment.style().isInherit() ? item.label() : item.label() + " •";
		// An eye marks a segment that overrides eye contact: filled on, hollow off.
		if (segment.style().eyeContact().isPresent()) {
			label += segment.style().eyeContact().getAsDouble() > 0 ? " ◉" : " ○";
		}
		double labelWidth = blockWidth - (gapAfter ? 44 : 22);
		if (labelWidth > 12) {
			graphics.setFont(BLOCK_FONT);
			graphics.setFill(BLOCK_TEXT);
			graphics.fillText(fitText(label, labelWidth, BLOCK_FONT), x + 16,
					y + height / 2 + 4);
		}
		if (isSelected(TimelineSelection.CAMERA_LAYOUT, item.id())) {
			drawSelection(graphics, x, y, blockWidth, height, CAMERA_LAYOUT_SELECTED);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
		drawCameraSwitch(graphics, segment, startX, y + height / 2);
		if (gapAfter) {
			drawCameraSwitchOut(graphics, segment, endX, y + height / 2);
		}
	}

	/**
	 * The switch diamond at a segment start: hollow for a cut, filled for a smooth move. Clicking
	 * it toggles between the two.
	 */
	private void drawCameraSwitch(GraphicsContext graphics, CameraLayoutSegment segment,
			double startX, double centerY) {
		drawSwitchDiamond(graphics, segment.transition(), startX + 7, centerY,
				cameraSwitchBounds, segment.id());
	}

	/**
	 * The project's smart-framing moves, marked on the Camera layout track: a diamond where the
	 * camera starts to move (cyan for a dodge away, gray for the eased return) over the glide's
	 * shaded band. Nothing without the plan — smart framing off draws a bare track.
	 */
	private void drawSmartFramingMoves(GraphicsContext graphics, double width) {
		SmartFraming.Plan plan = dataSource.smartFramingPlan();
		if (plan == null || plan.isEmpty()) {
			return;
		}
		RowLayout row = rowById(TimelineConstants.CAMERA_LAYOUT_ROW_ID);
		if (row == null) {
			return;
		}
		double top = row.top() + 5;
		double height = row.height() - 10;
		for (SmartFraming.Move move : plan.moves()) {
			double startX = xForTime(move.startMs());
			if (startX < GUTTER_WIDTH - 8 || startX > width) {
				continue;
			}
			double bandWidth = Math.max(2,
					xForTime(move.startMs() + SmartFraming.MOVE_MS) - startX);
			Color diamond = move.returning() ? SMART_FRAMING_RETURN : SMART_FRAMING_MOVE;
			graphics.setFill(move.returning() ? SMART_FRAMING_RETURN_BAND
					: SMART_FRAMING_MOVE_BAND);
			graphics.fillRect(startX, top, Math.min(bandWidth, width - startX), height);
			double half = 4;
			double centerY = top + height / 2;
			double[] xs = {startX, startX + half, startX, startX - half};
			double[] ys = {centerY - half, centerY, centerY + half, centerY};
			if (move.returning()) {
				graphics.setFill(CAMERA_HIDDEN_FILL);
				graphics.fillPolygon(xs, ys, 4);
				graphics.setStroke(diamond);
				graphics.setLineWidth(1.2);
				graphics.strokePolygon(xs, ys, 4);
			} else {
				graphics.setFill(diamond);
				graphics.fillPolygon(xs, ys, 4);
			}
		}
	}

	/**
	 * The pen bands over the clip row: where the presenter drew on screen while recording. An
	 * amber line along the top of the clips with a light wash under it, and a pen tag at the start
	 * that names the span and its strokes when hovered. Clicking the band seeks to its start.
	 */
	private void drawInkMarks(GraphicsContext graphics, double width) {
		inkBounds.clear();
		List<InkMark> marks = dataSource.inkMarks();
		RowLayout row = rowById(TimelineConstants.CLIP_ROW_ID);
		if (marks.isEmpty() || row == null) {
			return;
		}
		double top = row.top() + 5;
		double height = row.height() - 10;
		for (int index = 0; index < marks.size(); index++) {
			InkMark mark = marks.get(index);
			double x0 = Math.max(GUTTER_WIDTH, xForTime(mark.startMs()));
			double x1 = Math.min(width, xForTime(mark.endMs()));
			if (x1 <= x0) {
				continue;
			}
			double bandWidth = Math.max(2, x1 - x0);
			graphics.setFill(INK_BAND_FILL);
			graphics.fillRect(x0, top, bandWidth, height);
			graphics.setFill(INK_BAND);
			graphics.fillRect(x0, top, bandWidth, 2.5);
			boolean hovered = index == hoveredInkIndex;
			String label = hovered ? inkLabel(mark) : "✎";
			graphics.setFont(SMALL_FONT);
			double tagWidth = textWidth(label, SMALL_FONT) + 8;
			double tagHeight = 13;
			graphics.setFill(INK_BAND);
			graphics.fillRoundRect(x0, top + 3, tagWidth, tagHeight, 3, 3);
			graphics.setFill(INK_TAG_TEXT);
			graphics.fillText(label, x0 + 4, top + 3 + tagHeight - 3.5);
			// Only the tag is clickable: the band lies over the clips, which keep their own clicks.
			inkBounds.put(index, new BlockBounds(x0, top + 3, tagWidth, tagHeight));
		}
	}

	private static String inkLabel(InkMark mark) {
		return dev.gravaai.ui.i18n.UiI18n.t("editor:timeline.inkBand", java.util.Map.of(
				"start", formatClock(mark.startMs()),
				"end", formatClock(mark.endMs()),
				"count", String.valueOf(mark.strokes())));
	}

	private static String formatClock(long ms) {
		long seconds = Math.max(0, ms) / 1000;
		return (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
	}

	/** The pen band under a content point, if any. */
	private Optional<InkMark> inkMarkAt(double x, double y) {
		List<InkMark> marks = dataSource.inkMarks();
		for (Map.Entry<Integer, BlockBounds> entry : inkBounds.entrySet()) {
			if (entry.getValue().contains(x, y) && entry.getKey() < marks.size()) {
				return Optional.of(marks.get(entry.getKey()));
			}
		}
		return Optional.empty();
	}

	private int inkIndexAt(double x, double y) {
		for (Map.Entry<Integer, BlockBounds> entry : inkBounds.entrySet()) {
			if (entry.getValue().contains(x, y)) {
				return entry.getKey();
			}
		}
		return -1;
	}

	/**
	 * The eye-contact strip along the bottom of the Camera layout track: blue where the eyes are
	 * corrected (pale where the correction fades for a blink, a head turn or a look away), a gray
	 * progress bar over the whole track while the webcam is analysed, red when that failed.
	 */
	private void drawEyeContactStrip(GraphicsContext graphics, double width) {
		java.util.Optional<EyeContactStrip> found = dataSource.eyeContactStrip();
		RowLayout row = rowById(TimelineConstants.CAMERA_LAYOUT_ROW_ID);
		if (found.isEmpty() || row == null) {
			return;
		}
		EyeContactStrip strip = found.get();
		double y = row.top() + row.height() - 4;
		double height = 2.5;
		double startX = Math.max(GUTTER_WIDTH, xForTime(0));
		double endX = Math.min(width, xForTime(Math.max(0, dataSource.totalDurationMs())));
		switch (strip.state()) {
			case ANALYSING -> {
				graphics.setFill(EYE_CONTACT_TRACK);
				graphics.fillRect(startX, y, Math.max(0, endX - startX), height);
				double doneX = Math.min(endX, xForTime(
						dataSource.totalDurationMs() * Math.clamp(strip.progress(), 0, 1)));
				graphics.setFill(EYE_CONTACT_PROGRESS);
				graphics.fillRect(startX, y, Math.max(0, doneX - startX), height);
			}
			case FAILED -> {
				graphics.setFill(EYE_CONTACT_FAILED);
				graphics.fillRect(startX, y, Math.max(0, endX - startX), height);
			}
			case READY -> {
				for (EyeContactStrip.Span span : strip.spans()) {
					double x0 = Math.max(GUTTER_WIDTH, xForTime(span.startMs()));
					double x1 = Math.min(width, xForTime(span.endMs()));
					if (x1 <= x0) {
						continue;
					}
					graphics.setFill(span.full() ? EYE_CONTACT_ON : EYE_CONTACT_FADED);
					graphics.fillRect(x0, y, Math.max(1, x1 - x0), height);
				}
			}
		}
	}

	/**
	 * The switch-out diamond at a segment end followed by a gap: hollow for a cut, filled for a
	 * smooth move back to the project layout. Clicking it toggles between the two.
	 */
	private void drawCameraSwitchOut(GraphicsContext graphics, CameraLayoutSegment segment,
			double endX, double centerY) {
		drawSwitchDiamond(graphics, segment.transitionOut(), endX - 7, centerY,
				cameraSwitchOutBounds, segment.id());
	}

	private void drawSwitchDiamond(GraphicsContext graphics,
			CameraLayoutSegment.Transition transition, double cx, double centerY,
			Map<String, BlockBounds> bounds, String segmentId) {
		double half = 5;
		double[] xs = {cx, cx + half, cx, cx - half};
		double[] ys = {centerY - half, centerY, centerY + half, centerY};
		if (transition == CameraLayoutSegment.Transition.SMOOTH) {
			graphics.setFill(CAMERA_LAYOUT_BORDER);
			graphics.fillPolygon(xs, ys, 4);
		} else {
			graphics.setFill(CAMERA_HIDDEN_FILL);
			graphics.fillPolygon(xs, ys, 4);
			graphics.setStroke(CAMERA_LAYOUT_BORDER);
			graphics.setLineWidth(1.2);
			graphics.strokePolygon(xs, ys, 4);
		}
		bounds.put(segmentId,
				new BlockBounds(cx - half - 2, centerY - half - 2, half * 2 + 4, half * 2 + 4));
	}

	/** The segment whose switch diamond is under the point, if any. */
	private Optional<String> cameraSwitchAt(double x, double y) {
		return cameraSwitchAt(x, y, cameraSwitchBounds);
	}

	/** The segment whose switch-out diamond is under the point, if any. */
	private Optional<String> cameraSwitchOutAt(double x, double y) {
		return cameraSwitchAt(x, y, cameraSwitchOutBounds);
	}

	private Optional<String> cameraSwitchAt(double x, double y,
			Map<String, BlockBounds> bounds) {
		for (var entry : bounds.entrySet()) {
			BlockBounds block = entry.getValue();
			if (x >= block.minX() && x <= block.minX() + block.width() && y >= block.minY()
					&& y <= block.minY() + block.height()) {
				return Optional.of(entry.getKey());
			}
		}
		return Optional.empty();
	}

	/** The Keystrokes track: a keycap-chip block whose label is the pressed key combination. */
	private void drawKeystroke(GraphicsContext graphics, TimelineRenderItem item, RowLayout row,
			double width) {
		Span display = getRegionDisplaySpan(item.span());
		double startX = xForTime(display.start());
		double endX = xForTime(display.end());
		double blockWidth = Math.max(6, endX - startX);
		if (endX < GUTTER_WIDTH || startX > width) {
			return;
		}
		double x = Math.max(-blockWidth, startX);
		double height = row.height() - 10;
		double y = row.top() + 5;
		graphics.setFill(KEYSTROKE_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 3, 3);
		graphics.setStroke(KEYSTROKE_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 3, 3);
		drawBlockLabel(graphics, item.label(), x + 6, y + height / 2, blockWidth - 12, BLOCK_TEXT);
		if (isSelected(TimelineSelection.KEYSTROKE, item.id())) {
			drawSelection(graphics, x, y, blockWidth, height, KEYSTROKE_SELECTED);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
	}

	private void drawAnnotation(GraphicsContext graphics, TimelineRenderItem item, RowLayout row,
			double width) {
		Span display = getRegionDisplaySpan(item.span());
		double startX = xForTime(display.start());
		double endX = xForTime(display.end());
		double blockWidth = Math.max(6, endX - startX);
		if (endX < GUTTER_WIDTH || startX > width) {
			return;
		}
		double x = Math.max(-blockWidth, startX);
		double height = row.height() - 10;
		double y = row.top() + 5;
		boolean loupe = item.variant() == TimelineRenderItem.Variant.LOUPE;
		boolean spotlight = item.variant() == TimelineRenderItem.Variant.SPOTLIGHT;
		graphics.setFill(spotlight ? SPOTLIGHT_FILL : loupe ? LOUPE_FILL : ANNOTATION_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 3, 3);
		graphics.setStroke(spotlight ? SPOTLIGHT_BORDER : loupe ? LOUPE_BORDER : ANNOTATION_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 3, 3);
		double textX = x + 4;
		if (spotlight && blockWidth > 18) {
			// A small lamp glyph marks the block as a spotlight at any zoom level.
			double bulbRadius = Math.min(3.5, height * 0.16);
			double bulbX = x + 6 + bulbRadius;
			double bulbY = y + height / 2 - 1;
			graphics.setFill(SPOTLIGHT_GLYPH);
			graphics.fillOval(bulbX - bulbRadius, bulbY - bulbRadius, bulbRadius * 2,
					bulbRadius * 2);
			textX = bulbX + bulbRadius + 4;
		}
		if (loupe && blockWidth > 18) {
			// A small lens glyph marks the block as a magnifying glass at any zoom level.
			double lensRadius = Math.min(4.5, height * 0.2);
			double lensX = x + 6 + lensRadius;
			double lensY = y + height / 2 - 1;
			graphics.setStroke(LOUPE_GLYPH);
			graphics.setLineWidth(1.5);
			graphics.strokeOval(lensX - lensRadius, lensY - lensRadius, lensRadius * 2,
					lensRadius * 2);
			double handle = lensRadius * 0.7071;
			graphics.strokeLine(lensX + handle, lensY + handle, lensX + handle + 3.5,
					lensY + handle + 3.5);
			textX = lensX + lensRadius + 6;
		}
		drawBlockLabel(graphics, item.label(), textX + 4, y + height / 2,
				blockWidth - (textX - x) - 8, BLOCK_TEXT);
		if (isSelected(TimelineSelection.ANNOTATION, item.id())) {
			drawSelection(graphics, x, y, blockWidth, height,
					spotlight ? SPOTLIGHT_SELECTED : loupe ? LOUPE_SELECTED : ANNOTATION_SELECTED);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
	}

	private void drawAudio(GraphicsContext graphics, TimelineRenderItem item, RowLayout row,
			double width) {
		Span display = getRegionDisplaySpan(item.span());
		double startX = xForTime(display.start());
		double endX = xForTime(display.end());
		double blockWidth = Math.max(6, endX - startX);
		if (endX < GUTTER_WIDTH || startX > width) {
			return;
		}
		double x = Math.max(-blockWidth, startX);
		double height = row.height() - 10;
		double y = row.top() + 5;
		graphics.setFill(AUDIO_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 3, 3);
		drawWaveform(graphics, item, display, startX, endX, y, height, width);
		graphics.setStroke(AUDIO_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 3, 3);
		drawBlockLabel(graphics, item.label(), x + 6, y + height / 2, blockWidth - 12, AUDIO_TEXT);
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
		if (endX < GUTTER_WIDTH || startX > width) {
			return;
		}
		double x = Math.max(-blockWidth, startX);
		double height = row.height() - 10;
		double y = row.top() + 5;
		boolean muted = Boolean.TRUE.equals(item.muted());
		if (muted) {
			// Mock .clip--muted: the whole block at half opacity.
			graphics.setGlobalAlpha(0.5);
		}
		graphics.setFill(SOURCE_AUDIO_FILL);
		graphics.fillRoundRect(x, y, blockWidth, height, 3, 3);
		drawWaveform(graphics, item, display, startX, endX, y, height, width);
		graphics.setStroke(AUDIO_BORDER);
		graphics.setLineWidth(1);
		graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, blockWidth - 1), height - 1, 3, 3);
		drawBlockLabel(graphics, item.label(), x + 6, y + height / 2, blockWidth - 12, AUDIO_TEXT);
		AudioCleanup cleanup = cleanupForSourceTrack(item.rowId());
		String cleanupBadge = SourceAudioCleanupBadge.blockBadge(cleanup,
				TimelineRows.isSourceAudioTrackRowId(item.rowId())
						? dataSource.cleanupRenderStatus(
								TimelineRows.getSourceAudioTrackId(item.rowId()))
						: "");
		if (!cleanupBadge.isEmpty()) {
			double cleanupWidth = textWidth(cleanupBadge, SMALL_FONT) + 10;
			if (blockWidth > cleanupWidth + 8) {
				graphics.setFill(CLEANUP_BADGE_FILL);
				graphics.fillRoundRect(x + blockWidth - cleanupWidth - 4, y + 2, cleanupWidth,
						13, 2, 2);
				graphics.setFont(SMALL_FONT);
				graphics.setFill(CLEANUP_BADGE_TEXT);
				graphics.fillText(cleanupBadge, x + blockWidth - cleanupWidth + 1, y + 12);
			}
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
					13, 2, 2);
			graphics.setFont(SMALL_FONT);
			graphics.setFill(BADGE_TEXT);
			graphics.fillText(badge, x + blockWidth - badgeWidth + 1, y + height - 5);
		}
		if (isSelected(TimelineSelection.CLIP,
				sourceAudioOwnerClipId(item.rowId(), item.id()))) {
			drawSelection(graphics, x, y, blockWidth, height, CLIP_SELECTED);
		}
		if (muted) {
			graphics.setGlobalAlpha(1);
		}
		blockBounds.put(item.id(), new BlockBounds(x, y, blockWidth, height));
	}

	/**
	 * The lane marker for a cleaned source track: a dot plus the short NR marker right of the row
	 * name, so a cleaned lane reads at a glance without opening the inspector.
	 */
	private void drawCleanupMarker(GraphicsContext graphics, String rowId, String label,
			double centerY) {
		AudioCleanup cleanup = cleanupForSourceTrack(rowId);
		String marker = SourceAudioCleanupBadge.laneMarker(cleanup);
		if (marker.isEmpty()) {
			return;
		}
		double markerX = 8 + 18 + textWidth(label, ROW_LABEL_FONT) + 6;
		graphics.setFill(CLEANUP_ACCENT);
		graphics.fillOval(markerX, centerY - 2.5, 5, 5);
		if (markerX + 12 < GUTTER_WIDTH - 4) {
			graphics.setFont(SMALL_FONT);
			graphics.setFill(CLEANUP_ACCENT);
			graphics.fillText(marker, markerX + 8, centerY + 3);
		}
	}

	/** The project-defaults cleanup of the source track behind a lane row. */
	private AudioCleanup cleanupForSourceTrack(String rowId) {
		if (!TimelineRows.isSourceAudioTrackRowId(rowId)) {
			return AudioCleanup.NONE;
		}
		return defaultTrackSettings
				.trackOrDefault(TimelineRows.getSourceAudioTrackId(rowId)).cleanup();
	}

	/** The source-audio tracks currently rendered (the inspector's Audio section reads this). */
	public List<TimelineSourceAudioTrack> sourceAudioTracks() {
		return sourceAudioTracks;
	}

	/** The eye-contact strip of the Camera track (the inspector shows the same state). */
	public java.util.Optional<EyeContactStrip> eyeContactStrip() {
		return dataSource.eyeContactStrip();
	}

	/** The render state of a source lane's denoised copy (the inspector shows it too). */
	public String cleanupRenderStatus(String sourceTrackId) {
		return dataSource.cleanupRenderStatus(sourceTrackId);
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

	/**
	 * One bar per ~3px of the block's <em>visible</em> width, sampled from the audio file's own
	 * time axis: the caller's display span maps onto the clip's source span (trims and speed move
	 * the mapping, not the wave) or onto the file from its start for imported audio, and the
	 * portion under the header gutter is never requested. Muted blocks render dimmed.
	 */
	private void drawWaveform(GraphicsContext graphics, TimelineRenderItem item, Span displaySpan,
			double startX, double endX, double y, double height, double width) {
		if (item.audioPath() == null || endX <= startX) {
			return;
		}
		double audioStartMs;
		double audioEndMs;
		Span sourceSpan = item.sourceSpan();
		Span itemSpan = item.span();
		if (sourceSpan != null && itemSpan.duration() > 0) {
			// The sidecar tracks share the recording's time axis: the clip's source span, offset by
			// how much of the block the (fade-trimmed) display span skips.
			double scale = sourceSpan.duration() / itemSpan.duration();
			audioStartMs = sourceSpan.start() + (displaySpan.start() - itemSpan.start()) * scale;
			audioEndMs = sourceSpan.start() + (displaySpan.end() - itemSpan.start()) * scale;
		} else {
			// Imported audio plays the file at 1x from its start; the display span is its sub-window.
			audioStartMs = Math.max(0, displaySpan.start() - itemSpan.start());
			audioEndMs = audioStartMs + displaySpan.duration();
		}
		if (audioEndMs <= audioStartMs) {
			return;
		}
		double visibleX0 = Math.max(startX, GUTTER_WIDTH);
		double visibleX1 = Math.min(endX, width);
		if (visibleX1 - visibleX0 < 1) {
			return;
		}
		double blockX = Math.max(1, endX - startX);
		double audioStart = audioStartMs
				+ (visibleX0 - startX) / blockX * (audioEndMs - audioStartMs);
		double audioEnd = audioStartMs + (visibleX1 - startX) / blockX * (audioEndMs - audioStartMs);
		int bars = Math.max(1, (int) Math.ceil((visibleX1 - visibleX0) / WAVEFORM_BAR_PITCH_PX));
		float @Nullable [] peaks = dataSource.waveforms().peaks(item.audioPath(), audioStart,
				audioEnd, bars);
		if (peaks == null || peaks.length == 0) {
			return;
		}
		double gain = item.audioGain() == null ? 1 : Math.max(0, Math.min(1, item.audioGain()));
		boolean muted = Boolean.TRUE.equals(item.muted());
		double blockAlpha = graphics.getGlobalAlpha();
		if (muted) {
			// Dim on top of whatever the block already draws at (drawSourceAudio dims muted blocks).
			graphics.setGlobalAlpha(blockAlpha * WAVEFORM_MUTED_ALPHA);
		}
		graphics.setFill(WAVEFORM_COLOR);
		double centerY = y + height / 2;
		double maxAmplitude = height * 0.35;
		double visibleWidth = visibleX1 - visibleX0;
		double barWidth = Math.max(1, visibleWidth / bars - 1);
		for (int bar = 0; bar < bars && bar < peaks.length; bar++) {
			double amplitude = Math.max(0, Math.min(1, peaks[bar])) * gain * maxAmplitude;
			if (amplitude <= 0) {
				continue;
			}
			double barX = visibleX0 + visibleWidth * bar / bars;
			graphics.fillRect(barX, centerY - amplitude, barWidth, amplitude * 2);
		}
		if (muted) {
			graphics.setGlobalAlpha(blockAlpha);
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
			if (endX < GUTTER_WIDTH || startX > width || endX <= startX) {
				continue;
			}
			double x = Math.max(GUTTER_WIDTH, startX);
			double trimWidth = Math.min(width, endX) - x;
			graphics.setFill(TRIM_FILL);
			graphics.fillRect(x, y, trimWidth, height);
			graphics.setStroke(TRIM_BORDER);
			graphics.setLineWidth(1);
			graphics.strokeRect(x + 0.5, y + 0.5, Math.max(1, trimWidth - 1), height - 1);
			graphics.setFont(SMALL_FONT);
			graphics.setFill(Color.web("#e9f1f4", 0.9));
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
			if (endX < GUTTER_WIDTH || startX > width || endX <= startX) {
				continue;
			}
			double x = Math.max(GUTTER_WIDTH, startX);
			double bandWidth = Math.min(width, endX) - x;
			graphics.setFill(SPEED_FILL);
			graphics.fillRoundRect(x, y, bandWidth, height, 3, 3);
			graphics.setStroke(SPEED_BORDER);
			graphics.setLineWidth(1);
			graphics.strokeRoundRect(x + 0.5, y + 0.5, Math.max(1, bandWidth - 1), height - 1, 3,
					3);
			if (bandWidth > 34) {
				graphics.setFont(SMALL_FONT);
				graphics.setFill(SPEED_TEXT);
				graphics.fillText(String.format(java.util.Locale.ROOT, "%.2fx", speed.speed()),
						x + 4, y + height / 2 + 3);
			}
		}
	}

	/**
	 * Track headers (mock {@code .tlheads}): a faint icon and the mono uppercase row name in the
	 * fixed 112px gutter, scrolling vertically with the lanes.
	 */
	private void drawTrackHeaders(GraphicsContext graphics) {
		for (RowLayout row : rowLayouts) {
			double centerY = row.top() + row.height() / 2;
			Image icon = gutterIcon(row.id());
			if (icon != null) {
				graphics.drawImage(icon, 8, Math.round(centerY) - 6);
			}
			String label = rowLabel(row.id());
			if (!label.isEmpty()) {
				graphics.setFont(ROW_LABEL_FONT);
				graphics.setFill(GUTTER_TEXT);
				graphics.fillText(label, 8 + 18, centerY + 3.5);
			}
			if (TimelineRows.isSourceAudioTrackRowId(row.id())) {
				drawCleanupMarker(graphics, row.id(), label, centerY);
			}
		}
	}

	/** The row name shown in its track header (the previous on-lane labels, kept). */
	private String rowLabel(String rowId) {
		return switch (rowId) {
			case TimelineConstants.CLIP_ROW_ID -> "CLIP";
			case TimelineConstants.ZOOM_ROW_ID -> "ZOOM";
			case TimelineConstants.GRADE_ROW_ID -> "COLOR";
			case TimelineConstants.CAPTION_ROW_ID -> "CAPTION";
			case TimelineConstants.CAMERA_LAYOUT_ROW_ID -> "CAMERA";
			default -> {
				if (rowId.startsWith(TimelineConstants.ELEMENTS_ROW_ID + "-")) {
					yield (TimelineConstants.ELEMENTS_ROW_ID + "-0").equals(rowId) ? "ANIM" : "";
				}
				if (TimelineRows.isSourceAudioTrackRowId(rowId)) {
					yield sourceAudioTrackFor(rowId)
							.map(track -> track.label().toUpperCase(java.util.Locale.ROOT))
							.orElse("SOURCE");
				}
				if (TimelineRows.isKeystrokeLaneRowId(rowId)) {
					yield "KEYS";
				}
				if (TimelineRows.isAnnotationTrackRowId(rowId)) {
					yield "NOTE";
				}
				if (TimelineRows.isAudioTrackRowId(rowId)) {
					yield "AUDIO";
				}
				yield rowId.toUpperCase(java.util.Locale.ROOT);
			}
		};
	}

	/** The header icon per row type, rendered once from the kit's SVG set; null when nameless. */
	private @Nullable Image gutterIcon(String rowId) {
		String iconKey = rowIconKey(rowId);
		if (iconKey.isEmpty()) {
			return null;
		}
		Image cached = gutterIconCache.get(iconKey);
		if (cached != null) {
			return cached;
		}
		Icon icon = Icon.of(iconKey, 12);
		icon.setIconColor(Color.web("#5b6872"));
		SnapshotParameters params = new SnapshotParameters();
		params.setFill(javafx.scene.paint.Color.TRANSPARENT);
		WritableImage image = icon.snapshot(params, new WritableImage(12, 12));
		gutterIconCache.put(iconKey, image);
		return image;
	}

	private String rowIconKey(String rowId) {
		if (TimelineConstants.CLIP_ROW_ID.equals(rowId)) {
			return Icons.FilmStrip;
		}
		if (TimelineConstants.CAPTION_ROW_ID.equals(rowId)) {
			return Icons.ClosedCaptioning;
		}
		if (TimelineConstants.CAMERA_LAYOUT_ROW_ID.equals(rowId)) {
			return Icons.VideoCamera;
		}
		if (rowId.startsWith(TimelineConstants.ELEMENTS_ROW_ID + "-")) {
			return Icons.PuzzlePiece;
		}
		if (TimelineRows.isSourceAudioTrackRowId(rowId)) {
			return Icons.MusicNotes;
		}
		if (TimelineConstants.ZOOM_ROW_ID.equals(rowId)) {
			return Icons.MagnifyingGlass;
		}
		if (TimelineConstants.GRADE_ROW_ID.equals(rowId)) {
			return Icons.Palette;
		}
		if (TimelineRows.isAnnotationTrackRowId(rowId)) {
			return Icons.TextT;
		}
		if (TimelineRows.isAudioTrackRowId(rowId)) {
			return Icons.SpeakerHigh;
		}
		if (TimelineRows.isKeystrokeLaneRowId(rowId)) {
			return Icons.Keyboard;
		}
		return "";
	}

	/**
	 * Pending transcript cuts: red warning bands over the clip row, one per source range. They are
	 * what the transcript panel is about to remove, so the user sees the edit before committing it.
	 */
	private void drawCutPreview(GraphicsContext graphics, double width) {
		if (cutPreview.isEmpty()) {
			return;
		}
		RowLayout clipRow = rowById(TimelineConstants.CLIP_ROW_ID);
		double top = clipRow == null ? AXIS_HEIGHT + 2 : clipRow.top() + 2;
		double height = clipRow == null ? 24 : Math.max(6, clipRow.height() - 4);
		for (Span span : cutPreview) {
			double startX = xForTime(span.start());
			double endX = xForTime(span.end());
			if (endX < GUTTER_WIDTH || startX > width || endX <= startX) {
				continue;
			}
			double x = Math.max(GUTTER_WIDTH, startX);
			double bandWidth = Math.min(width, endX) - x;
			graphics.setFill(CUT_PREVIEW_FILL);
			graphics.fillRect(x, top, bandWidth, height);
			graphics.setStroke(CUT_PREVIEW_BORDER);
			graphics.setLineWidth(1);
			graphics.setLineDashes(3, 2);
			graphics.strokeRect(x + 0.5, top + 0.5, Math.max(1, bandWidth - 1), height - 1);
			graphics.setLineDashes(null);
			// A diagonal hatch so a narrow band still reads as "this goes away" at small zoom.
			graphics.setStroke(CUT_PREVIEW_HATCH);
			double step = 6;
			for (double hatchX = x - height; hatchX < x + bandWidth; hatchX += step) {
				graphics.strokeLine(hatchX, top + height, hatchX + height, top);
			}
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
			if (endX < GUTTER_WIDTH || startX > width || endX <= startX) {
				continue;
			}
			double x = Math.max(GUTTER_WIDTH, startX);
			double highlightWidth = Math.min(width, endX) - x;
			graphics.setFill(Color.web("#a78bfa", 0.16));
			graphics.fillRoundRect(x, top, highlightWidth, height, 3, 3);
			graphics.setStroke(Color.web("#a78bfa", 0.5));
			graphics.setLineWidth(1);
			graphics.strokeRoundRect(x + 0.5, top + 0.5, Math.max(1, highlightWidth - 1),
					Math.max(6, height - 1), 3, 3);
			if (highlight.label() != null && highlightWidth > 48) {
				graphics.setFont(SMALL_FONT);
				graphics.setFill(Color.web("#ddd6fe"));
				graphics.fillText(fitText(highlight.label(), highlightWidth - 8, SMALL_FONT), x + 4,
						top + 14);
			}
		}
	}

	/**
	 * PlaybackCursor, Instrument style: a 1px cyan line over ruler and lanes, an 8px cap at the
	 * ruler top and a mono timecode tag always visible beside it (mock {@code .playhead}).
	 */
	private void drawPlayhead(GraphicsContext graphics, double height) {
		double x = xForTime(currentTimeMs);
		double width = contentWidth();
		if (x < GUTTER_WIDTH || x > width) {
			return;
		}
		graphics.setStroke(PLAYHEAD);
		graphics.setLineWidth(1);
		graphics.strokeLine(Math.round(x) + 0.5, 0, Math.round(x) + 0.5, height);
		double capX = Math.max(GUTTER_WIDTH - 1, Math.round(x) - 3);
		graphics.setFill(PLAYHEAD);
		graphics.fillRoundRect(capX, 0, PLAYHEAD_CAP_WIDTH, PLAYHEAD_CAP_HEIGHT, 2, 2);
		// The tag (mock .playhead .tag): cyan pill, dark mono text, pinned right of the line.
		String tag = TimelineScale.formatPlayheadTime(currentTimeMs);
		double tagWidth = textWidth(tag, PLAYHEAD_FONT) + 8;
		double tagX = Math.min(x + 6, width - tagWidth);
		graphics.fillRoundRect(tagX, 3, tagWidth, PLAYHEAD_TAG_HEIGHT, 2, 2);
		graphics.setFill(CYAN_INK);
		graphics.setFont(PLAYHEAD_FONT);
		graphics.fillText(tag, tagX + 4, 3 + 11);
	}

	private boolean isSelected(String kind, String id) {
		if (selectedIds.contains(id)) {
			return true;
		}
		return selection != null && kind.equals(selection.kind()) && id.equals(selection.id());
	}

	private void drawSelection(GraphicsContext graphics, double x, double y, double width,
			double height, Color color) {
		graphics.setStroke(color);
		graphics.setLineWidth(2);
		graphics.strokeRoundRect(x + 1, y + 1, Math.max(1, width - 2), Math.max(1, height - 2), 3,
				3);
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