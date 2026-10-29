package dev.gravaai.app.agent;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.AutoCaptionSettings;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.ZoomRegion;
import dev.gravaai.services.agent.AgentIds;
import dev.gravaai.services.agent.AgentTranscript;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Text formatting for the agent's {@code inspect} views (port of
 * {@code src/components/video-editor/agent/views.ts}, plan §5.16/P3.2b).
 *
 * <p>Reads are what make the skill loop possible: the overview gives durations/counts/{@code rev};
 * the timeline view names the short ids ({@code c1}, {@code z1}, …) the edit batch has to use; the
 * transcript view exposes words and pauses; the settings view is the authoritative key list for
 * {@code set}. Every read ends with {@code rev} so the next {@code edit} can detect a concurrent
 * user change.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
final class AgentEditorViews {

	private static final long DEFAULT_PAGE_MS = 180_000;
	private static final long PAUSE_MARKER_MS = 700;

	private static final Set<String> TIMELINE_KEYS = Set.of("zoomRegions", "trimRegions",
			"clipRegions", "speedRegions", "annotationRegions", "audioRegions", "autoCaptions",
			"autoFullTrackClipId", "autoFullTrackClipEndMs");

	/** Setting-key hints copied from {@code SETTING_HINTS} in the React source. */
	private static final Map<String, String> SETTING_HINTS = Map.ofEntries(
			Map.entry("padding", "0-100 each side (linked by default)"),
			Map.entry("borderRadius", "0-50 percent"),
			Map.entry("shadowIntensity", "0-1"),
			Map.entry("backgroundBlur", "0-8"),
			Map.entry("zoomMotionBlur", "0-2"),
			Map.entry("cropRegion", "0-1 fractions; width/height clamp to 1 - x / 1 - y"),
			Map.entry("aspectRatio", "native, 16:9, 9:16, 1:1, 4:3, 4:5, 16:10, 10:16, or W:H"),
			Map.entry("cursorSize", "0.5-10 (snapped to the nearest motion preset)"),
			Map.entry("autoCaptionSettings.fontSize", "16-72"),
			Map.entry("autoCaptionSettings.maxWidth", "40-95"),
			Map.entry("autoCaptionSettings.maxRows", "1-4"),
			Map.entry("autoCaptionSettings.bottomOffset", "0-30"),
			Map.entry("autoCaptionSettings.backgroundOpacity", "0-1"),
			Map.entry("webcam.size", "10-100 (percent of the frame)"),
			Map.entry("webcam.positionPreset", "top-left, top-center, top-right, center-left,"
					+ " center, center-right, bottom-left, bottom-center, bottom-right, custom"),
			Map.entry("keystrokeOverlay.position", "top-left, top-center, top-right, bottom-left,"
					+ " bottom-center, bottom-right"));

	/** Formatting inputs the editor session provides (kept explicit so the views stay testable). */
	record Meta(String projectName, @Nullable String projectPath, String sourcePath, long sourceDurationMs,
			boolean dirty, long rev) {}

	private AgentEditorViews() {}

	/** {@code formatOverview}. */
	static String overview(ProjectEditorState state, Meta meta) {
		long editedMs = editedDurationMs(state, meta.sourceDurationMs());
		boolean hasTranscript = state.autoCaptions().stream()
				.anyMatch(cue -> cue.words() != null && !cue.words().isEmpty());
		List<String> lines = new ArrayList<>();
		lines.add("project: " + meta.projectName() + (meta.dirty() ? " (unsaved changes)" : ""));
		lines.add("path: " + (meta.projectPath() == null ? "not saved yet" : meta.projectPath()));
		lines.add("source: " + (meta.sourcePath() == null ? "none" : meta.sourcePath())
				+ " (" + seconds(meta.sourceDurationMs()) + "s)");
		lines.add("edited: " + seconds(editedMs) + "s \u2022 aspect " + state.aspectRatio());
		lines.add("clips " + count(state.clipRegions()) + " \u2022 zooms "
				+ count(state.zoomRegions()) + " \u2022 speed "
				+ count(state.speedRegions()) + " \u2022 annotations "
				+ count(state.annotationRegions()) + " \u2022 audio "
				+ count(state.audioRegions()) + " \u2022 caption cues "
				+ count(state.autoCaptions()) + (hasTranscript ? " (transcript)" : ""));
		lines.add("webcam " + (state.webcam().enabled()
				? "on (" + state.webcam().positionPreset().wire() + ", "
						+ Math.round(state.webcam().size()) + "%)"
				: "off") + " \u2022 cursor " + (state.showCursor() ? "on" : "off")
				+ " \u2022 wallpaper " + state.wallpaper());
		lines.add("rev " + meta.rev());
		return String.join("\n", lines);
	}

	/** {@code formatTimeline}: one line per clip/zoom/speed/annotation/audio in the window. */
	static String timeline(ProjectEditorState state, long sourceDurationMs, Double from, Double to,
			long rev) {
		AgentIds ids = AgentIds.of(state);
		long editedMs = editedDurationMs(state, sourceDurationMs);
		long fromMs = Math.max(0, Math.round((from == null ? 0 : from) * 1000));
		long toMs = to != null ? Math.min(editedMs, Math.round(to * 1000))
				: Math.min(editedMs, fromMs + DEFAULT_PAGE_MS);
		toMs = Math.max(fromMs + 1, toMs);
		final long windowFrom = fromMs;
		final long windowTo = toMs;

		List<String> lines = new ArrayList<>();
		lines.add("window " + seconds(fromMs) + "\u2013" + seconds(toMs) + "s of "
				+ seconds(editedMs) + "s");

		lines.add("clips:");
		List<ClipRegion> clips = new ArrayList<>(state.clipRegions());
		clips.sort(Comparator.comparingLong(ClipRegion::startMs));
		boolean anyClip = false;
		for (ClipRegion clip : clips) {
			if (!overlaps(clip.startMs(), clip.endMs(), fromMs, toMs)) {
				continue;
			}
			anyClip = true;
			StringBuilder flags = new StringBuilder();
			flags.append(String.format(Locale.ROOT, "%.2fx", clip.speed() <= 0 ? 1 : clip.speed()));
			if (Boolean.TRUE.equals(clip.muted())) {
				flags.append(" muted");
			}
			if (clip.cameraLayout() != null) {
				flags.append(' ').append(clip.cameraLayout().wire());
			}
			if (clip.transitionOut() != null) {
				flags.append(" fade-out ").append(Math.round(clip.transitionOut().durationMs()))
						.append("ms");
			}
			lines.add("  " + ids.clipShortById().getOrDefault(clip.id(), "?") + " "
					+ seconds(clip.startMs()) + "\u2013" + seconds(clip.endMs()) + " src "
					+ seconds(Clips.getClipSourceStartMs(clip)) + "\u2013"
					+ seconds(Clips.getClipSourceEndMs(clip)) + " " + flags);
		}
		if (!anyClip) {
			lines.add("  (none in window)");
		}

		lines.add("zooms:");
		List<ZoomRegion> zooms = new ArrayList<>(state.zoomRegions());
		zooms.sort(Comparator.comparingLong(ZoomRegion::startMs));
		boolean anyZoom = false;
		for (ZoomRegion zoom : zooms) {
			if (!overlaps(zoom.startMs(), zoom.endMs(), fromMs, toMs)) {
				continue;
			}
			anyZoom = true;
			lines.add("  " + ids.zoomShortById().getOrDefault(zoom.id(), "?") + " "
					+ seconds(zoom.startMs()) + "\u2013" + seconds(zoom.endMs()) + " depth "
					+ zoom.depth().wire() + " focus "
					+ String.format(Locale.ROOT, "%.2f,%.2f", zoom.focus().cx(), zoom.focus().cy())
					+ (zoom.mode() == null ? "" : " " + zoom.mode().wire()));
		}
		if (!anyZoom) {
			lines.add("  (none in window)");
		}

		List<SpeedRegion> speeds = new ArrayList<>(state.speedRegions());
		speeds.sort(Comparator.comparingLong(SpeedRegion::startMs));
		List<SpeedRegion> visibleSpeeds = speeds.stream()
				.filter(region -> overlaps(region.startMs(), region.endMs(), windowFrom, windowTo))
				.toList();
		if (!visibleSpeeds.isEmpty()) {
			lines.add("speed:");
			for (SpeedRegion region : visibleSpeeds) {
				lines.add("  " + ids.speedShortById().getOrDefault(region.id(), "?") + " "
						+ seconds(region.startMs()) + "\u2013" + seconds(region.endMs()) + " "
						+ String.format(Locale.ROOT, "%.2fx", region.speed()));
			}
		}

		lines.add("annotations:");
		List<AnnotationRegion> annotations = new ArrayList<>(state.annotationRegions());
		annotations.sort(Comparator.comparingLong(AnnotationRegion::startMs)
				.thenComparingInt(AnnotationRegion::zIndex));
		boolean anyAnnotation = false;
		for (AnnotationRegion annotation : annotations) {
			if (!overlaps(annotation.startMs(), annotation.endMs(), fromMs, toMs)) {
				continue;
			}
			anyAnnotation = true;
			String text = annotationText(annotation);
			lines.add("  " + ids.annotationShortById().getOrDefault(annotation.id(), "?") + " "
					+ seconds(annotation.startMs()) + "\u2013" + seconds(annotation.endMs()) + " "
					+ annotation.type().wire() + " \"" + text + "\" x "
					+ frac((annotation.position().x() + annotation.size().width() / 2) / 100)
					+ " y " + frac((annotation.position().y() + annotation.size().height() / 2) / 100)
					+ " w " + frac(annotation.size().width() / 100)
					+ " h " + frac(annotation.size().height() / 100));
		}
		if (!anyAnnotation) {
			lines.add("  (none in window)");
		}

		List<AudioRegion> audio = new ArrayList<>(state.audioRegions());
		audio.sort(Comparator.comparingLong(AudioRegion::startMs));
		List<AudioRegion> visibleAudio = audio.stream()
				.filter(region -> overlaps(region.startMs(), region.endMs(), windowFrom, windowTo))
				.toList();
		if (!visibleAudio.isEmpty()) {
			lines.add("audio:");
			for (AudioRegion region : visibleAudio) {
				lines.add("  " + ids.audioShortById().getOrDefault(region.id(), "?") + " "
						+ seconds(region.startMs()) + "\u2013" + seconds(region.endMs()) + " "
						+ region.audioPath() + " vol "
						+ String.format(Locale.ROOT, "%.2f", region.volume()));
			}
		}

		int cueCount = state.autoCaptions().size();
		lines.add(cueCount == 0 ? "captions: none"
				: "captions: " + cueCount + " cues"
						+ (state.autoCaptionSettings().enabled() ? "" : " (disabled)")
						+ " \u2014 inspect view:\"transcript\" for words");
		lines.add("rev " + rev);
		return String.join("\n", lines);
	}

	/** {@code formatTranscript}: words grouped by cue with {@code ~} cut markers and pauses. */
	static String transcript(ProjectEditorState state, long sourceDurationMs, Double from,
			Double to, long rev) {
		AgentIds ids = AgentIds.of(state);
		long fromMs = Math.max(0, Math.round((from == null ? 0 : from) * 1000));
		long toMs = to != null ? Math.min(sourceDurationMs, Math.round(to * 1000))
				: Math.min(sourceDurationMs, fromMs + DEFAULT_PAGE_MS);
		toMs = Math.max(fromMs + 1, toMs);
		final long windowFrom = fromMs;
		final long windowTo = toMs;

		List<AgentTranscript.Word> inWindow = ids.words().stream()
				.filter(word -> word.endMs() > windowFrom && word.startMs() < windowTo).toList();
		List<String> lines = new ArrayList<>();
		if (state.autoCaptions().isEmpty()) {
			lines.add("no transcript yet; run job transcribe (source 0.00\u2013"
					+ seconds(sourceDurationMs) + "s)");
			lines.add("rev " + rev);
			return String.join("\n", lines);
		}
		if (inWindow.isEmpty()) {
			lines.add("no words between " + seconds(fromMs) + "\u2013" + seconds(toMs)
					+ "s; transcript ends at " + seconds(sourceDurationMs) + "s");
			lines.add("rev " + rev);
			return String.join("\n", lines);
		}

		Map<String, CaptionCue> cueById = new LinkedHashMap<>();
		for (CaptionCue cue : state.autoCaptions()) {
			cueById.put(cue.id(), cue);
		}
		lines.add("words " + seconds(fromMs) + "\u2013" + seconds(toMs) + "s of "
				+ seconds(sourceDurationMs) + "s");
		long previousEnd = -1;
		int index = 0;
		while (index < inWindow.size()) {
			String cueId = inWindow.get(index).cueId();
			List<AgentTranscript.Word> group = new ArrayList<>();
			while (index < inWindow.size() && inWindow.get(index).cueId().equals(cueId)) {
				group.add(inWindow.get(index));
				index++;
			}
			if (previousEnd >= 0 && group.get(0).startMs() - previousEnd >= PAUSE_MARKER_MS) {
				lines.add("[pause " + String.format(Locale.ROOT, "%.1f",
						(group.get(0).startMs() - previousEnd) / 1000.0)
						+ "s] at " + seconds(previousEnd));
			}
			CaptionCue cue = cueById.get(cueId);
			long cueStart = Math.max(cue == null ? group.get(0).startMs() : cue.startMs(), fromMs);
			long cueEnd = cue == null ? group.get(group.size() - 1).endMs() : cue.endMs();
			List<String> parts = new ArrayList<>();
			for (AgentTranscript.Word word : group) {
				boolean kept = AgentTranscript.isWordKept(word, state.clipRegions());
				parts.add((kept ? "" : "~")
						+ ids.wordShortByKey().getOrDefault(word.key(), "?") + " "
						+ String.format(Locale.ROOT, "%.2f", word.startMs() / 1000.0) + " "
						+ word.text());
			}
			lines.add(ids.cueShortById().getOrDefault(cueId, "?") + " " + seconds(cueStart)
					+ "\u2013" + seconds(cueEnd) + ": " + String.join(" | ", parts));
			previousEnd = group.get(group.size() - 1).endMs();
		}
		if (toMs < sourceDurationMs) {
			lines.add("next: {from: " + String.format(Locale.ROOT, "%.2f", toMs / 1000.0) + "}");
		}
		lines.add("rev " + rev);
		return String.join("\n", lines);
	}

	/** {@code formatSettings}: flat key=value list of everything {@code set} can change. */
	static String settings(ProjectEditorState state, String topic, long rev) {
		ObjectNode editor = ProjectDocument.writeEditor(state, Json.nodeFactory().objectNode());
		List<String[]> settings = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : editor.properties()) {
			if (TIMELINE_KEYS.contains(entry.getKey())) {
				continue;
			}
			flatten(entry.getKey(), entry.getValue(), settings);
		}
		String normalizedTopic = topic == null ? null : topic.trim().toLowerCase(Locale.ROOT);
		List<String[]> filtered = new ArrayList<>();
		for (String[] setting : settings) {
			if (normalizedTopic == null || setting[0].toLowerCase(Locale.ROOT).equals(normalizedTopic)
					|| setting[0].toLowerCase(Locale.ROOT).startsWith(normalizedTopic + ".")
					|| setting[0].toLowerCase(Locale.ROOT).contains(normalizedTopic)) {
				filtered.add(setting);
			}
		}
		List<String> lines = new ArrayList<>();
		if (filtered.isEmpty()) {
			lines.add("no settings match \"" + topic
					+ "\"; inspect view:\"settings\" without a topic for the list");
		} else {
			for (String[] setting : filtered) {
				String hint = SETTING_HINTS.get(setting[0]);
				lines.add(setting[0] + "=" + setting[1] + (hint == null ? "" : "  (" + hint + ")"));
			}
			if (normalizedTopic != null) {
				SETTING_HINTS.entrySet().stream()
						.filter(entry -> entry.getKey().toLowerCase(Locale.ROOT)
								.startsWith(normalizedTopic))
						.filter(entry -> filtered.stream()
								.noneMatch(setting -> setting[0].equals(entry.getKey())))
						.forEach(entry -> lines.add("# " + entry.getKey() + ": "
								+ entry.getValue()));
			}
		}
		lines.add("rev " + rev);
		return String.join("\n", lines);
	}

	/** The edited timeline length: the end of the last clip, like the TS helper. */
	static long editedDurationMs(ProjectEditorState state, long sourceDurationMs) {
		long end = Clips.getTimelineDurationMs(state.clipRegions(), sourceDurationMs);
		return end > 0 ? end : sourceDurationMs;
	}

	/** Source recording duration; probed once per session by the port and cached there. */
	static long sourceDurationMs(EditorSession session) {
		double total = session.timelineDataSource().totalDurationMs() / 1000.0;
		return Math.max(1, Math.round(total * 1000));
	}

	private static String annotationText(AnnotationRegion annotation) {
		String text;
		if (annotation.type() == dev.gravaai.core.project.AnnotationType.TEXT) {
			text = annotation.textContent() == null ? annotation.content()
					: annotation.textContent();
		} else if (annotation.type() == dev.gravaai.core.project.AnnotationType.FIGURE) {
			text = annotation.figureData() == null
					|| annotation.figureData().arrowDirection() == null ? "arrow"
							: annotation.figureData().arrowDirection().wire();
		} else {
			text = annotation.type().wire();
		}
		if (text == null) {
			text = "";
		}
		return text.length() > 40 ? text.substring(0, 40) : text;
	}

	private static void flatten(String prefix, JsonNode value, List<String[]> out) {
		if (value != null && value.isObject()) {
			for (Map.Entry<String, JsonNode> child : value.properties()) {
				flatten(prefix.isEmpty() ? child.getKey() : prefix + "." + child.getKey(),
						child.getValue(), out);
			}
			return;
		}
		if (value != null && value.isArray()) {
			out.add(new String[] {prefix, value.size() + " items"});
			return;
		}
		out.add(new String[] {prefix, scalar(value)});
	}

	private static String scalar(JsonNode value) {
		if (value == null || value.isNull() || value.isMissingNode()) {
			return "null";
		}
		return value.isString() ? value.asString() : value.toString();
	}

	private static boolean overlaps(long startMs, long endMs, long fromMs, long toMs) {
		return endMs > fromMs && startMs < toMs;
	}

	private static int count(List<?> values) {
		return values == null ? 0 : values.size();
	}

	private static String seconds(long ms) {
		return AgentIds.formatSeconds(ms);
	}

	private static String frac(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	/** The auto-caption settings block (kept for the overview's future caption line). */
	static AutoCaptionSettings captionSettings(ProjectEditorState state) {
		return state.autoCaptionSettings();
	}
}
