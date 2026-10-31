package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.KeystrokeElement;
import dev.gravaai.core.project.Padding;
import dev.gravaai.core.project.ProjectEditorState;

/**
 * Production mapping from the project editor model ({@code gravaai-core} P2.2) to the media-local
 * compositor inputs (plan §5.8): the single resolver preview, export and filmstrip must share.
 *
 * <p>Ported from the golden/test {@code ProjectFixture}; the only difference is that the wallpaper
 * asset root is passed in, so the app can point at its packaged assets while tests point at
 * {@code public/}. Missing wallpaper assets degrade to black instead of failing a preview frame.</p>
 */
public final class ProjectEditorInputs {

	private static final System.Logger LOG =
			System.getLogger(ProjectEditorInputs.class.getName());

	private ProjectEditorInputs() {
	}

	/**
	 * Resolves the project wallpaper to a compositor background; every unsupported/missing
	 * wallpaper degrades to black.
	 *
	 * @param wallpaperRoot directory holding {@code wallpapers/}, or null when the caller has no
	 *        asset root (bundled wallpapers then degrade to black)
	 */
	public static Background background(ProjectEditorState editor, @Nullable Path wallpaperRoot) {
		String wallpaper = editor.wallpaper();
		if (wallpaper == null || wallpaper.isBlank()) {
			return Background.black();
		}
		if (wallpaper.startsWith("/wallpapers/")) {
			if (wallpaperRoot == null) {
				LOG.log(System.Logger.Level.WARNING,
						"no wallpaper asset root; painting black for " + wallpaper);
				return Background.black();
			}
			Path asset = wallpaperRoot.resolve(wallpaper.substring(1));
			try {
				if (Files.isRegularFile(asset)) {
					return new Background.Image(Files.readAllBytes(asset));
				}
				LOG.log(System.Logger.Level.WARNING, "wallpaper asset not found: " + asset);
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING,
						"could not read wallpaper " + asset + ": " + error);
			}
			return Background.black();
		}
		try {
			return Background.parse(wallpaper);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING,
					"unsupported wallpaper '" + wallpaper + "': " + error);
			return Background.black();
		}
	}

	/**
	 * The file behind a video wallpaper: a bundled {@code /wallpapers/...} asset under
	 * {@code wallpaperRoot}, a {@code file://} URL or an absolute path. Null for image/CSS
	 * wallpapers or a missing file.
	 */
	public static @Nullable Path videoWallpaperPath(ProjectEditorState editor,
			@Nullable Path wallpaperRoot) {
		String wallpaper = editor.wallpaper();
		if (wallpaper == null || !Background.isVideoWallpaper(wallpaper)) {
			return null;
		}
		try {
			Path path;
			if (wallpaper.startsWith("/wallpapers/")) {
				if (wallpaperRoot == null) {
					return null;
				}
				path = wallpaperRoot.resolve(wallpaper.substring(1));
			} else if (wallpaper.startsWith("file:")) {
				path = Path.of(java.net.URI.create(wallpaper));
			} else {
				path = Path.of(wallpaper);
			}
			return Files.isRegularFile(path) ? path : null;
		} catch (RuntimeException invalid) {
			return null;
		}
	}

	/** Maps the padded/cropped layout of the editor state. */
	public static LayoutSpec layout(ProjectEditorState editor) {
		Padding padding = editor.padding();
		LayoutPadding localPadding = padding == null
				? LayoutPadding.NONE
				: new LayoutPadding(padding.top(), padding.bottom(), padding.left(),
						padding.right(), Boolean.TRUE.equals(padding.linked()));
		CropRegion crop = editor.cropRegion();
		LayoutCrop localCrop = crop == null
				? LayoutCrop.FULL
				: new LayoutCrop(crop.x(), crop.y(), crop.width(), crop.height());
		return new LayoutSpec(localPadding, localCrop);
	}

	/** Maps the editor zoom state (regions plus timing/spring settings). */
	public static ZoomSpec zoomSpec(ProjectEditorState editor) {
		List<ZoomRegionSpec> regions = new ArrayList<>();
		for (dev.gravaai.core.project.ZoomRegion region : editor.zoomRegions()) {
			regions.add(new ZoomRegionSpec(region.id(), (int) region.startMs(),
					(int) region.endMs(), ZoomDepth.of(region.depth().wire()),
					new ZoomFocus(region.focus().cx(), region.focus().cy()),
					region.mode() == null ? null : ZoomMode.valueOf(region.mode().name())));
		}
		return new ZoomSpec(regions, editor.zoomSmoothness(), editor.connectZooms(),
				editor.zoomClassicMode(), editor.zoomInDurationMs(), editor.zoomOutDurationMs(),
				editor.cameraSpringStiffnessMultiplier(),
				editor.cameraSpringDampingMultiplier(),
				editor.cameraSpringMassMultiplier(), editor.zoomMotionBlur());
	}

	/** Maps the stage-4 frame style (border radius + video drop shadow). */
	public static FrameStyle frameStyle(ProjectEditorState editor) {
		return new FrameStyle(editor.borderRadius(), true, editor.shadowIntensity());
	}

	/** Maps the editor cursor state to the compositor input. */
	public static CursorSpec cursorSpec(ProjectEditorState editor) {
		return new CursorSpec(editor.showCursor(), CursorStyle.fromWire(editor.cursorStyle()),
				28 * editor.cursorSize(), editor.cursorSmoothing(),
				editor.cursorSpringStiffnessMultiplier(), editor.cursorSpringDampingMultiplier(),
				editor.cursorSpringMassMultiplier(), editor.cursorMotionBlur(),
				cursorClickEffect(editor.cursorClickEffect()),
				parseColour(editor.cursorClickEffectColor()), editor.cursorClickEffectScale(),
				editor.cursorClickEffectOpacity(), editor.cursorClickEffectDurationMs(),
				editor.cursorClickBounce(), editor.cursorClickBounceDuration(),
				editor.cursorSway());
	}

	private static CursorClickEffect cursorClickEffect(
			dev.gravaai.core.project.CursorClickEffectStyle effect) {
		if (effect == null) {
			return CursorClickEffect.NONE;
		}
		String name = effect.name().toUpperCase();
		if (name.contains("RIPPLE")) {
			return CursorClickEffect.RIPPLE;
		}
		if (name.contains("SPOTLIGHT")) {
			return CursorClickEffect.SPOTLIGHT;
		}
		if (name.contains("ECHO")) {
			return CursorClickEffect.ECHO;
		}
		return CursorClickEffect.NONE;
	}

	/** Maps the webcam overlay settings (stage 7). */
	public static WebcamSpec webcamSpec(ProjectEditorState editor) {
		var webcam = editor.webcam();
		if (webcam == null) {
			return WebcamSpec.DISABLED;
		}
		List<WebcamSpec.VisibleRange> ranges = new ArrayList<>();
		if (webcam.visibleRanges() != null) {
			for (var range : webcam.visibleRanges()) {
				ranges.add(new WebcamSpec.VisibleRange(range.startMs(), range.endMs()));
			}
		}
		var crop = webcam.cropRegion() == null ? CropRegion.DEFAULT : webcam.cropRegion();
		String preset = webcam.positionPreset() == null
				? "bottom-right"
				: webcam.positionPreset().wire();
		String corner = webcam.corner() == null ? "bottom-right" : webcam.corner().wire();
		return new WebcamSpec(webcam.enabled(), preset, corner, webcam.positionX(),
				webcam.positionY(), webcam.size(), webcam.width(), webcam.height(),
				webcam.mirror(), webcam.roundness(), webcam.shadow(), webcam.margin(),
				webcam.reactToZoom(),
				new LayoutCrop(crop.x(), crop.y(), crop.width(), crop.height()), ranges,
				webcam.timeOffsetMs());
	}

	/** Maps the editor annotations (stage 8). */
	public static List<AnnotationSpec> annotationSpecs(ProjectEditorState editor) {
		List<AnnotationSpec> specs = new ArrayList<>();
		if (editor.annotationRegions() == null) {
			return List.of();
		}
		for (var region : editor.annotationRegions()) {
			if (region == null) {
				continue;
			}
			var style = region.style() == null
					? dev.gravaai.core.project.AnnotationTextStyle.defaultStyle()
					: region.style();
			var position = region.position() == null
					? dev.gravaai.core.project.AnnotationPosition.DEFAULT
					: region.position();
			var size = region.size() == null
					? dev.gravaai.core.project.AnnotationSize.DEFAULT
					: region.size();
			Integer backgroundColor = parseColourOrNull(style.backgroundColor());
			AnnotationSpec.Figure figure = null;
			if (region.figureData() != null) {
				var data = region.figureData();
				figure = new AnnotationSpec.Figure(
						data.arrowDirection() == null ? null : data.arrowDirection().wire(),
						parseColour(data.color()), data.strokeWidth());
			}
			boolean underline = style.textDecoration()
					== dev.gravaai.core.project.AnnotationTextStyle.TextDecoration.UNDERLINE;
			specs.add(new AnnotationSpec(
					region.id(),
					region.startMs(),
					region.endMs(),
					AnnotationSpec.Type.valueOf(region.type() == null
							? "TEXT"
							: region.type().name()),
					region.textContent() != null ? region.textContent() : region.content(),
					region.imageContent() != null ? region.imageContent() : region.content(),
					new AnnotationSpec.Position(position.x(), position.y()),
					new AnnotationSpec.Size(size.width(), size.height()),
					new AnnotationSpec.TextStyle(
							parseColour(style.color()),
							backgroundColor,
							style.fontSize(),
							style.fontFamily(),
							style.fontWeight()
									== dev.gravaai.core.project.AnnotationTextStyle.FontWeight.BOLD,
							style.fontStyle()
									== dev.gravaai.core.project.AnnotationTextStyle.FontStyle.ITALIC,
							underline,
							style.textAlign() == null ? "center" : style.textAlign().wire(),
							style.borderRadius()),
					region.zIndex(),
					figure,
					region.blurIntensity(),
					region.blurColor()));
		}
		return List.copyOf(specs);
	}

	/** Maps the editor caption settings/cues (stage 9). */
	public static CaptionSpec captionSpec(ProjectEditorState editor) {
		var settings = editor.autoCaptionSettings();
		if (settings == null || !settings.enabled()) {
			return CaptionSpec.DISABLED;
		}
		List<CaptionSpec.Cue> cues = new ArrayList<>();
		if (editor.autoCaptions() != null) {
			for (var cue : editor.autoCaptions()) {
				if (cue == null || cue.text() == null || cue.text().isBlank()) {
					continue;
				}
				cues.add(new CaptionSpec.Cue(cue.id(), cue.startMs(), cue.endMs(), cue.text(),
						cue.words() == null ? List.of() : cue.words()));
			}
		}
		String animationStyle = settings.animationStyle() == null
				? "none"
				: settings.animationStyle().wire();
		String fontFamily = settings.fontFamily();
		if (fontFamily == null || fontFamily.isBlank()) {
			fontFamily = CaptionSpec.DEFAULT_FONT_FAMILY;
		}
		String textColor = settings.textColor() == null
				? CaptionSpec.DEFAULT_TEXT_COLOR
				: settings.textColor();
		String inactiveTextColor = settings.inactiveTextColor() == null
				? CaptionSpec.DEFAULT_INACTIVE_TEXT_COLOR
				: settings.inactiveTextColor();
		return new CaptionSpec(true, fontFamily, settings.fontSize(),
				settings.bottomOffset(), settings.maxWidth(), settings.maxRows(), animationStyle,
				settings.boxRadius(), textColor, inactiveTextColor,
				settings.backgroundOpacity(), cues);
	}

	/**
	 * Maps the keystroke overlay plus the presses. The editable {@code keystrokeElements} of the
	 * state win when present (they are seeded from the sidecar at open time); the
	 * {@code .keys.json} sidecar is the fallback so projects that never loaded elements still
	 * render exactly as before.
	 *
	 * @param videoPath the source recording whose sidecar carries the presses, or null when the
	 *        caller has no source file (then only the settings are mapped)
	 */
	public static KeystrokeSpec keystrokeSpec(ProjectEditorState editor, @Nullable Path videoPath) {
		var settings = editor.keystrokeOverlay();
		if (settings == null || !settings.enabled()) {
			return KeystrokeSpec.DISABLED;
		}
		List<KeystrokeSpec.Press> presses = new ArrayList<>();
		if (editor.keystrokeElements() != null && !editor.keystrokeElements().isEmpty()) {
			for (KeystrokeElement element : editor.keystrokeElements()) {
				presses.add(new KeystrokeSpec.Press(element.startMs(), element.chips()));
			}
		} else if (videoPath != null) {
			Path sidecar = Path.of(videoPath.toString() + ".keys.json");
			if (Files.isRegularFile(sidecar)) {
				try {
					tools.jackson.databind.JsonNode root = tools.jackson.databind.json.JsonMapper
							.shared().readTree(Files.readString(sidecar));
					tools.jackson.databind.JsonNode events = root.path("events");
					if (events.isArray()) {
						for (tools.jackson.databind.JsonNode event : events) {
							String key = event.path("key").isString()
									? event.path("key").asString().trim()
									: "";
							if (key.isEmpty()) {
								continue;
							}
							List<String> chips = new ArrayList<>();
							tools.jackson.databind.JsonNode modifiers = event.path("modifiers");
							if (modifiers.isArray()) {
								for (tools.jackson.databind.JsonNode modifier : modifiers) {
									String label = switch (modifier.asString()) {
										case "ctrl" -> "Ctrl";
										case "alt" -> "Alt";
										case "shift" -> "Shift";
										case "super" -> "Super";
										default -> null;
									};
									if (label != null) {
										chips.add(label);
									}
								}
							}
							chips.add(key);
							presses.add(new KeystrokeSpec.Press(
									Math.round(event.path("timeMs").asDouble()), chips));
						}
					}
				} catch (IOException error) {
					LOG.log(System.Logger.Level.WARNING,
							"could not read keystroke sidecar " + sidecar + ": " + error);
				}
			}
		}
		String position = settings.position() == null
				? "bottom-center"
				: settings.position().wire();
		return new KeystrokeSpec(true, position, settings.fontSize(), settings.lingerMs(),
				settings.fadeMs(), settings.groupingMs(), settings.bottomOffset(), presses);
	}

	/** Resolves the per-clip camera layout state (camera opacity/fill, screen opacity). */
	public static SceneSpec sceneSpec(ProjectEditorState editor, double timelineMs) {
		List<dev.gravaai.core.project.ClipRegion> clips = editor.clipRegions() == null
				? List.of()
				: editor.clipRegions();
		return SceneSpec.resolve(SceneSpec.spans(clips), timelineMs);
	}

	/** Maps the project's fade-through-black clip transitions (stage 11). */
	public static FadeSpec fadeSpec(ProjectEditorState editor) {
		List<FadeSpec.Window> windows = new ArrayList<>();
		for (var window : dev.gravaai.core.project.ClipTransitions.buildFadeWindows(
				editor.clipRegions())) {
			windows.add(new FadeSpec.Window(window.startMs(), window.peakMs(),
					window.holdUntilMs(), window.endMs()));
		}
		return windows.isEmpty() ? FadeSpec.NONE : new FadeSpec(windows);
	}

	/** Parses a CSS colour, falling back to the TS default blue for absent/invalid values. */
	private static int parseColour(@Nullable String value) {
		if (value == null || value.length() < 7) {
			return 0xFF2563EB;
		}
		String hex = value.startsWith("#") ? value.substring(1) : value;
		if (hex.length() == 8) {
			return (int) Long.parseLong(hex, 16);
		}
		if (hex.length() == 6) {
			return 0xFF000000 | (int) Long.parseLong(hex, 16);
		}
		return 0xFF2563EB;
	}

	private static @Nullable Integer parseColourOrNull(@Nullable String value) {
		if (value == null || value.isBlank() || "transparent".equalsIgnoreCase(value.strip())) {
			return null;
		}
		int parsed = parseColour(value);
		return (parsed >>> 24) == 0 ? null : parsed;
	}
}
