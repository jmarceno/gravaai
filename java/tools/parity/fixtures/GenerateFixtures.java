import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generates the P2.1 golden-frame fixture projects (plan §6.2, §7.2 P2.1).
 *
 * <p>The fixtures are authored as JSON patches on top of the committed real project
 * {@code java/gravaai-core/src/test/resources/project/Untitled Project 1.gravaai}, then normalized by
 * the production {@link ProjectPersistence} normaliser and written through {@link ProjectDocument}, so
 * they load in the Electron reference app and in the Java app exactly like a saved project.
 *
 * <p>It also writes the cursor and keystroke telemetry sidecars next to the shared media (the sidecar
 * location is {@code <videoPath>.cursor.json} / {@code <videoPath>.keys.json}, the recording layout),
 * a small PNG used by the image annotation, and {@code fixtures/capture-plan.json}, which
 * {@code java/tools/parity/capture-fixtures.sh} uses to capture the reference frames.
 *
 * <p>Media paths are absolute for local capture (lead decision for P2.1); a portable scheme can come
 * later. Run through {@code java/tools/parity/generate-fixtures.sh}.
 */
public final class GenerateFixtures {

	private static final String CAPTION_FONT =
			"\"SF Pro Text\", \"SF Pro Display\", \"Helvetica Neue\", sans-serif";
	private static final int DURATION_MS = 2000;
	private static final int CAPTURE_WIDTH = 1280;

	private record PlanEntry(String name, String project, String video, int[] timesSeconds,
			List<String> features) {}

	private static Path repo;
	private static Path media;
	private static Path projects;
	private static ObjectNode templateEditor;
	private static String annotationImage;
	private static final List<PlanEntry> PLAN = new ArrayList<>();

	private GenerateFixtures() {}

	public static void main(String[] args) throws Exception {
		repo = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
		media = repo.resolve("java/parity/fixtures/media");
		projects = repo.resolve("java/parity/fixtures/projects");
		Files.createDirectories(projects);
		if (!Files.isDirectory(media)) {
			throw new IllegalStateException("missing media directory: " + media);
		}

		String video = requireMedia("h264-1080p60.mp4").toString();
		String vp9 = requireMedia("vp9-720p30.webm").toString();

		writeCursorTelemetry(media.resolve("h264-1080p60.mp4.cursor.json"));
		writeKeystrokeTelemetry(media.resolve("h264-1080p60.mp4.keys.json"));
		annotationImage = annotationImageDataUrl();

		Path templatePath = repo.resolve(
				"java/gravaai-core/src/test/resources/project/Untitled Project 1.gravaai");
		ProjectDocument template = ProjectDocument.load(templatePath);
		JsonNode rawEditor = template.raw().get("editor");
		if (rawEditor == null || !rawEditor.isObject()) {
			throw new IllegalStateException("template project has no editor object: " + templatePath);
		}
		templateEditor = (ObjectNode) rawEditor;

		// 01 — wallpaper + blur, linked padding, shadow, rounded corners, macOS cursor with a
		// spotlight click, fade captions.
		fixture("01-wallpaper-blur", video, new int[] {350, 900, 1650},
				List.of("wallpaper", "background blur", "padding (linked)", "shadow", "border radius",
						"cursor style macos", "click effect spotlight", "captions / fade"),
				editor -> {
					editor.put("wallpaper", "/wallpapers/tahoe-light.jpg");
					editor.put("backgroundBlur", 45);
					editor.put("shadowIntensity", 0.85);
					editor.put("borderRadius", 26);
					editor.set("padding", padding(24, 24, 24, 24, true));
					cursor(editor, "macos", "spotlight", "#FF3B30", 1.6, 1.0, 700);
					captions(editor, "fade", "Fade captions over a blurred wallpaper");
				});

		// 02 — portrait aspect ratio, asymmetric padding, crop, large radius, tahoe cursor with a
		// ripple click, rise captions.
		fixture("02-padding-crop-aspect", video, new int[] {500, 1000, 1600},
				List.of("padding (per side)", "crop region", "aspect ratio 9:16", "border radius",
						"cursor style tahoe", "click effect ripple", "captions / rise"),
				editor -> {
					editor.put("wallpaper", "/wallpapers/cityscape.jpg");
					editor.put("aspectRatio", "9:16");
					editor.put("borderRadius", 42);
					editor.set("padding", padding(12, 96, 48, 140, false));
					editor.set("cropRegion", crop(0.08, 0.12, 0.84, 0.76));
					cursor(editor, "tahoe", "ripple", "#22C55E", 2.2, 0.9, 650);
					captions(editor, "rise", "Rise captions in a 9:16 crop");
				});

		// 03 — two zooms with motion blur and different focus points, tahoe-inverted cursor with an
		// echo click, pop captions.
		fixture("03-zoom-motion-blur", video, new int[] {250, 600, 1050, 1350, 1800},
				List.of("zoom regions", "zoom depth/focus", "zoom motion blur",
						"cursor style tahoe-inverted", "click effect echo", "captions / pop"),
				editor -> {
					editor.put("zoomMotionBlur", 0.9);
					editor.put("zoomInDurationMs", 420);
					editor.put("zoomOutDurationMs", 380);
					editor.set("padding", padding(32, 32, 32, 32, true));
					editor.put("borderRadius", 18);
					editor.set("zoomRegions", zooms(
							zoom("zoom-1", 150, 850, 5, 0.3, 0.35, "manual"),
							zoom("zoom-2", 950, 1750, 3, 0.72, 0.62, "manual")));
					cursor(editor, "tahoe-inverted", "echo", "#A855F7", 1.8, 1.0, 800);
					captions(editor, "pop", "Pop captions while zooming with motion blur");
				});

		// 04 — windows11 cursor, spotlight in a second colour, captions with no animation.
		fixture("04-cursor-windows11", video, new int[] {500, 720, 1250},
				List.of("cursor style windows11", "click effect spotlight", "captions / none"),
				editor -> {
					editor.put("wallpaper", "/wallpapers/sequoia-blue-orange.jpg");
					editor.set("zoomRegions", zooms(zoom("zoom-1", 700, 1500, 2, 0.6, 0.5, "manual")));
					cursor(editor, "windows11", "spotlight", "#2563EB", 2.0, 1.0, 900);
					captions(editor, "none", "Static captions with a windows11 cursor");
				});

		// 05 — dot cursor with a large ripple, and the keystroke overlay fed by the keys sidecar.
		fixture("05-cursor-dot-keystrokes", video, new int[] {350, 750, 1150, 1550},
				List.of("cursor style dot", "click effect ripple", "keystroke overlay", "captions / fade"),
				editor -> {
					cursor(editor, "dot", "ripple", "#F59E0B", 2.6, 0.95, 750);
					ObjectNode keystrokes = ((ObjectNode) editor.get("keystrokeOverlay")).deepCopy();
					keystrokes.put("enabled", true);
					keystrokes.put("position", "bottom-center");
					keystrokes.put("fontSize", 40);
					keystrokes.put("lingerMs", 900);
					keystrokes.put("fadeMs", 150);
					keystrokes.put("groupingMs", 600);
					keystrokes.put("bottomOffset", 24);
					editor.set("keystrokeOverlay", keystrokes);
					captions(editor, "fade", "Dot cursor, ripple click and keystroke chips");
				});

		// 06 — figma cursor with no click effect, rise captions, dark wallpaper.
		fixture("06-cursor-figma", video, new int[] {500, 1250},
				List.of("cursor style figma", "click effect none", "wallpaper (dark)",
						"captions / rise"),
				editor -> {
					editor.put("wallpaper", "/wallpapers/tahoe-dark.jpg");
					cursor(editor, "figma", "none", "#2563EB", 1.0, 1.0, 600);
					captions(editor, "rise", "Figma cursor with rise captions");
				});

		// 07 — webcam bottom-right preset that reacts to a zoom.
		fixture("07-webcam-bottom-right", video, new int[] {350, 900, 1500},
				List.of("webcam overlay (bottom-right preset)", "webcam reacts to zoom",
						"cursor style tahoe", "click effect ripple", "captions / pop"),
				editor -> {
					editor.set("webcam", webcam(vp9, "bottom-right", "bottom-right", 32, true, true, 100,
							0.35));
					editor.set("zoomRegions", zooms(zoom("zoom-1", 400, 1400, 3, 0.6, 0.5, "manual")));
					cursor(editor, "tahoe", "ripple", "#22C55E", 1.6, 1.0, 650);
					captions(editor, "pop", "Webcam bottom-right reacts to zoom");
				});

		// 08 — three clips: default camera layout, hide-camera, camera-only, with fade-black
		// transitions between them; webcam top-left preset.
		fixture("08-webcam-layouts-fade", video, new int[] {350, 820, 1050, 1550, 1800},
				List.of("webcam overlay (top-left preset)", "clip camera layout default",
						"clip camera layout hide-camera", "clip camera layout camera-only",
						"clip transition fade-black"),
				editor -> {
					editor.set("webcam", webcam(vp9, "top-left", "top-left", 28, false, false, 80, 0.3));
					editor.set("clipRegions", clips(
							clip("clip-1", 0, 700, 0, null, 250),
							clip("clip-2", 700, 1400, 700, "hide-camera", 250),
							clip("clip-3", 1400, DURATION_MS, 1400, "camera-only", null)));
					cursor(editor, "tahoe", "none", "#2563EB", 1.0, 1.0, 600);
				});

		// 09 — one of each annotation type, staggered over the timeline.
		fixture("09-annotations", video, new int[] {250, 600, 1000, 1500},
				List.of("annotation text", "annotation image", "annotation figure (arrow)",
						"annotation blur", "cursor style macos", "click effect spotlight",
						"captions / fade"),
				editor -> {
					editor.set("annotationRegions", annotations());
					editor.set("zoomRegions", zooms(zoom("zoom-1", 1150, 1900, 2, 0.5, 0.5, "manual")));
					cursor(editor, "macos", "spotlight", "#FF3B30", 1.4, 1.0, 700);
					captions(editor, "fade", "Annotations: text, image, figure and blur");
				});

		// 10 — connected zooms in classic mode on a VP9 source (covers the second codec).
		fixture("10-connected-zoom-classic-vp9", vp9, new int[] {350, 600, 1000, 1250},
				List.of("connected zooms", "classic zoom mode", "zoom motion blur",
						"VP9 source decode"),
				editor -> {
					editor.put("connectZooms", true);
					editor.put("connectedZoomGapMs", 250);
					editor.put("connectedZoomDurationMs", 500);
					editor.put("zoomClassicMode", true);
					editor.put("zoomMotionBlur", 0.5);
					editor.put("wallpaper", "/wallpapers/cityscape.jpg");
					editor.set("padding", padding(16, 16, 16, 16, true));
					editor.set("zoomRegions", zooms(
							zoom("zoom-1", 300, 700, 3, 0.4, 0.4, "manual"),
							zoom("zoom-2", 750, 1300, 2, 0.65, 0.55, "manual")));
					cursor(editor, "tahoe", "none", "#2563EB", 1.0, 1.0, 600);
				});

		writeCapturePlan();
		System.out.println("GenerateFixtures: " + PLAN.size() + " projects, "
				+ repo.relativize(projects) + ", " + repo.relativize(media));
	}

	private static void fixture(String name, String videoPath, int[] timesSeconds, List<String> features,
			Patch patch) throws Exception {
		ObjectNode editor = baseEditor();
		patch.apply(editor);
		ProjectEditorState state = ProjectPersistence.normalizeProjectEditor(editor);
		ProjectDocument document = ProjectDocument.create(videoPath, state).withProjectId(null);
		Path file = projects.resolve(name + ".gravaai");
		document.write(file);

		// Fail fast when a fixture does not pass the production loader + normaliser.
		ProjectDocument reloaded = ProjectDocument.load(file);
		if (!reloaded.videoPath().equals(videoPath)) {
			throw new IllegalStateException(name + ": videoPath was not preserved");
		}
		PLAN.add(new PlanEntry(name, name + ".gravaai", videoPath, timesSeconds, features));
		System.out.println("wrote " + repo.relativize(file) + " (" + Files.size(file) + " bytes, "
				+ timesSeconds.length + " captures)");
	}

	private static ObjectNode baseEditor() {
		ObjectNode editor = templateEditor.deepCopy();
		editor.set("clipRegions", clips(clip("clip-1", 0, DURATION_MS, 0, null, null)));
		editor.set("zoomRegions", arr());
		editor.set("trimRegions", arr());
		editor.set("speedRegions", arr());
		editor.set("annotationRegions", arr());
		editor.set("audioRegions", arr());
		editor.set("autoCaptions", arr());
		editor.set("padding", padding(20, 20, 20, 20, true));
		editor.set("cropRegion", crop(0, 0, 1, 1));
		editor.put("aspectRatio", "16:9");
		editor.put("borderRadius", 0);
		editor.put("backgroundBlur", 0);
		editor.put("shadowIntensity", 0.67);
		editor.put("wallpaper", "/wallpapers/sequoia-blue.jpg");
		editor.put("zoomMotionBlur", 0.0);
		editor.put("connectZooms", false);
		editor.put("zoomClassicMode", false);
		editor.put("showCursor", true);
		editor.put("loopCursor", false);
		ObjectNode keystrokes = ((ObjectNode) editor.get("keystrokeOverlay")).deepCopy();
		keystrokes.put("enabled", false);
		editor.set("keystrokeOverlay", keystrokes);
		ObjectNode webcam = ((ObjectNode) editor.get("webcam")).deepCopy();
		webcam.put("enabled", false);
		webcam.putNull("sourcePath");
		webcam.set("visibleRanges", arr());
		editor.set("webcam", webcam);
		cursor(editor, "tahoe", "none", "#2563EB", 1.0, 1.0, 600);
		return editor;
	}

	private static void cursor(ObjectNode editor, String style, String clickEffect, String color,
			double scale, double opacity, int durationMs) {
		editor.put("showCursor", true);
		editor.put("cursorStyle", style);
		editor.put("cursorClickEffect", clickEffect);
		editor.put("cursorClickEffectColor", color);
		editor.put("cursorClickEffectScale", scale);
		editor.put("cursorClickEffectOpacity", opacity);
		editor.put("cursorClickEffectDurationMs", durationMs);
	}

	private static void captions(ObjectNode editor, String animation, String text) {
		ObjectNode settings = ((ObjectNode) editor.get("autoCaptionSettings")).deepCopy();
		settings.put("enabled", true);
		settings.put("animationStyle", animation);
		editor.set("autoCaptionSettings", settings);
		ArrayNode cues = arr();
		ObjectNode cue = obj();
		cue.put("id", "cue-1");
		cue.put("startMs", 0);
		cue.put("endMs", DURATION_MS);
		cue.put("text", text);
		cues.add(cue);
		editor.set("autoCaptions", cues);
	}

	private static ObjectNode webcam(String sourcePath, String preset, String corner, double size,
			boolean mirror, boolean reactToZoom, double roundness, double shadow) {
		ObjectNode webcam = obj();
		ArrayNode ranges = arr();
		ObjectNode range = obj();
		range.put("startMs", 0);
		range.put("endMs", DURATION_MS);
		ranges.add(range);
		webcam.set("visibleRanges", ranges);
		webcam.put("enabled", true);
		webcam.put("sourcePath", sourcePath);
		webcam.put("mirror", mirror);
		webcam.set("cropRegion", crop(0, 0, 1, 1));
		webcam.put("positionPreset", preset);
		webcam.put("positionX", 1);
		webcam.put("positionY", 1);
		webcam.put("corner", corner);
		webcam.put("size", size);
		webcam.put("width", size);
		webcam.put("height", size);
		webcam.put("reactToZoom", reactToZoom);
		webcam.put("roundness", roundness);
		webcam.put("shadow", shadow);
		webcam.put("timeOffsetMs", 0);
		webcam.put("margin", 24);
		return webcam;
	}

	private static ObjectNode zoom(String id, int startMs, int endMs, int depth, double cx, double cy,
			String mode) {
		ObjectNode zoom = obj();
		zoom.put("id", id);
		zoom.put("startMs", startMs);
		zoom.put("endMs", endMs);
		zoom.put("depth", depth);
		ObjectNode focus = obj();
		focus.put("cx", cx);
		focus.put("cy", cy);
		zoom.set("focus", focus);
		zoom.put("mode", mode);
		return zoom;
	}

	private static ArrayNode zooms(ObjectNode... regions) {
		ArrayNode array = arr();
		for (ObjectNode region : regions) {
			array.add(region);
		}
		return array;
	}

	private static ObjectNode clip(String id, int startMs, int endMs, int sourceStartMs,
			String cameraLayout, Integer transitionMs) {
		ObjectNode clip = obj();
		clip.put("id", id);
		clip.put("startMs", startMs);
		clip.put("endMs", endMs);
		clip.put("sourceStartMs", sourceStartMs);
		clip.put("speed", 1);
		clip.put("muted", false);
		clip.put("showSourceAudio", false);
		if (cameraLayout != null) {
			clip.put("cameraLayout", cameraLayout);
		}
		if (transitionMs != null) {
			ObjectNode transition = obj();
			transition.put("kind", "fade-black");
			transition.put("durationMs", transitionMs);
			clip.set("transitionOut", transition);
		}
		return clip;
	}

	private static ArrayNode clips(ObjectNode... regions) {
		ArrayNode array = arr();
		for (ObjectNode region : regions) {
			array.add(region);
		}
		return array;
	}

	private static ArrayNode annotations() {
		ArrayNode array = arr();

		ObjectNode text = annotation("ann-text", 0, 450, "text", 50, 24, 44, 12);
		text.put("content", "Golden text annotation");
		text.put("textContent", "Golden text annotation");
		ObjectNode textStyle = (ObjectNode) text.get("style");
		textStyle.put("fontSize", 44);
		textStyle.put("color", "#FFFFFF");
		textStyle.put("backgroundColor", "#111827");
		array.add(text);

		ObjectNode image = annotation("ann-image", 400, 900, "image", 50, 50, 34, 19);
		image.put("content", annotationImage);
		image.put("imageContent", annotationImage);
		array.add(image);

		ObjectNode figure = annotation("ann-figure", 850, 1350, "figure", 40, 68, 30, 18);
		ObjectNode figureData = obj();
		figureData.put("arrowDirection", "down-right");
		figureData.put("color", "#F97316");
		figureData.put("strokeWidth", 6);
		figure.set("figureData", figureData);
		array.add(figure);

		ObjectNode blur = annotation("ann-blur", 1300, 1750, "blur", 38, 62, 36, 26);
		blur.put("blurIntensity", 45);
		blur.put("blurColor", "#000000");
		array.add(blur);

		return array;
	}

	private static ObjectNode annotation(String id, int startMs, int endMs, String type, double x,
			double y, double width, double height) {
		ObjectNode annotation = obj();
		annotation.put("id", id);
		annotation.put("startMs", startMs);
		annotation.put("endMs", endMs);
		annotation.put("type", type);
		annotation.put("content", "");
		ObjectNode position = obj();
		position.put("x", x);
		position.put("y", y);
		annotation.set("position", position);
		ObjectNode size = obj();
		size.put("width", width);
		size.put("height", height);
		annotation.set("size", size);
		ObjectNode style = obj();
		style.put("color", "#FFFFFF");
		style.put("backgroundColor", "transparent");
		style.put("fontSize", 32);
		style.put("fontFamily", CAPTION_FONT);
		style.put("fontWeight", "bold");
		style.put("fontStyle", "normal");
		style.put("textDecoration", "none");
		style.put("textAlign", "center");
		style.put("borderRadius", 8);
		annotation.set("style", style);
		annotation.put("zIndex", 1);
		annotation.put("trackIndex", 0);
		annotation.put("blurIntensity", 20);
		annotation.put("blurColor", "#000000");
		return annotation;
	}

	private static ObjectNode padding(double top, double bottom, double left, double right,
			boolean linked) {
		ObjectNode padding = obj();
		padding.put("top", top);
		padding.put("bottom", bottom);
		padding.put("left", left);
		padding.put("right", right);
		padding.put("linked", linked);
		return padding;
	}

	private static ObjectNode crop(double x, double y, double width, double height) {
		ObjectNode crop = obj();
		crop.put("x", x);
		crop.put("y", y);
		crop.put("width", width);
		crop.put("height", height);
		return crop;
	}

	private static void writeCursorTelemetry(Path file) throws IOException {
		TreeMap<Integer, ObjectNode> samples = new TreeMap<>();
		for (int time = 0; time <= DURATION_MS; time += 33) {
			samples.put(time, cursorSample(time, null));
		}
		samples.put(600, cursorSample(600, "click"));
		samples.put(1150, cursorSample(1150, "click"));
		samples.put(1500, cursorSample(1500, "double-click"));
		samples.put(1800, cursorSample(1800, "mouseup"));

		ObjectNode root = obj();
		root.put("version", 2);
		ArrayNode array = arr();
		samples.values().forEach(array::add);
		root.set("samples", array);
		Json.writeFile(file, root);
		System.out.println("wrote " + repo.relativize(file) + " (" + samples.size()
				+ " cursor samples, clicks at 600/1150/1500 ms)");
	}

	private static ObjectNode cursorSample(int timeMs, String interactionType) {
		double progress = Math.min(1.0, timeMs / (double) DURATION_MS);
		double eased = progress * progress * (3 - 2 * progress);
		double cx = clamp01(0.18 + 0.62 * eased + 0.015 * Math.sin(progress * 20));
		double cy = clamp01(0.30 + 0.36 * eased + 0.015 * Math.cos(progress * 15));
		ObjectNode sample = obj();
		sample.put("timeMs", timeMs);
		sample.put("cx", round(cx));
		sample.put("cy", round(cy));
		sample.put("cursorType", timeMs >= 800 && timeMs < 1100 ? "pointer" : "arrow");
		if (interactionType != null) {
			sample.put("interactionType", interactionType);
		}
		return sample;
	}

	private static void writeKeystrokeTelemetry(Path file) throws IOException {
		ObjectNode root = obj();
		root.put("version", 1);
		ArrayNode events = arr();
		events.add(keystroke(300, "S", "ctrl"));
		events.add(keystroke(700, "Enter"));
		events.add(keystroke(1100, "A", "shift", "super"));
		events.add(keystroke(1500, "Escape"));
		root.set("events", events);
		Json.writeFile(file, root);
		System.out.println("wrote " + repo.relativize(file) + " (4 key presses)");
	}

	private static ObjectNode keystroke(int timeMs, String key, String... modifiers) {
		ObjectNode event = obj();
		event.put("timeMs", timeMs);
		event.put("key", key);
		ArrayNode array = arr();
		for (String modifier : modifiers) {
			array.add(modifier);
		}
		event.set("modifiers", array);
		return event;
	}

	private static String annotationImageDataUrl() throws IOException {
		BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(new Color(0x1D4ED8));
			graphics.fillRoundRect(0, 0, 320, 180, 28, 28);
			graphics.setColor(new Color(0x93C5FD));
			graphics.fillOval(36, 36, 108, 108);
			graphics.setColor(Color.WHITE);
			graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
			graphics.drawString("GOLDEN", 156, 106);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(image, "png", png);
		byte[] bytes = png.toByteArray();
		Files.write(media.resolve("annotation-image.png"), bytes);
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
	}

	private static void writeCapturePlan() throws IOException {
		ObjectNode plan = obj();
		plan.put("width", CAPTURE_WIDTH);
		plan.put("mediaDirectory", "java/parity/fixtures/media");
		plan.put("ffmpeg", "java/native/build/ffmpeg/bin/ffmpeg");
		ArrayNode entries = arr();
		for (PlanEntry entry : PLAN) {
			ObjectNode item = obj();
			item.put("name", entry.name());
			item.put("project", entry.project());
			item.put("video", entry.video());
			ArrayNode times = arr();
			for (int time : entry.timesSeconds()) {
				times.add(time / 1000.0);
			}
			item.set("timesSeconds", times);
			ArrayNode features = arr();
			entry.features().forEach(features::add);
			item.set("features", features);
			entries.add(item);
		}
		plan.set("projects", entries);
		Path file = repo.resolve("java/parity/fixtures/capture-plan.json");
		Json.writeFile(file, plan);
		System.out.println("wrote " + repo.relativize(file));
	}

	private static Path requireMedia(String fileName) {
		Path file = media.resolve(fileName);
		if (!Files.isRegularFile(file)) {
			throw new IllegalStateException("missing fixture media: " + file);
		}
		return file;
	}

	private static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}

	private static double round(double value) {
		return Math.round(value * 1_000_000.0) / 1_000_000.0;
	}

	private static ObjectNode obj() {
		return Json.nodeFactory().objectNode();
	}

	private static ArrayNode arr() {
		return Json.nodeFactory().arrayNode();
	}

	@FunctionalInterface
	private interface Patch {
		void apply(ObjectNode editor) throws Exception;
	}
}
