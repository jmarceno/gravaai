package dev.gravaai.services.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.ProjectEditorState;

/**
 * Short, stable identifiers used by the agent surface (port of
 * {@code src/components/video-editor/agent/ids.ts}, plan §5.16): the nth clip in timeline order is
 * {@code c1}, zooms are {@code z1}, speed regions {@code s1}, annotations {@code a1}, audio regions
 * {@code m1}, caption cues {@code q1} and transcript words {@code w1}.
 */
public record AgentIds(List<String> clipIds, List<String> zoomIds, List<String> speedIds,
		List<String> annotationIds, List<String> audioIds, List<String> cueIds,
		List<AgentTranscript.Word> words, Map<String, String> clipShortById,
		Map<String, String> zoomShortById, Map<String, String> speedShortById,
		Map<String, String> annotationShortById, Map<String, String> audioShortById,
		Map<String, String> cueShortById, Map<String, String> clipIdByShort,
		Map<String, String> zoomIdByShort, Map<String, String> speedIdByShort,
		Map<String, String> annotationIdByShort, Map<String, String> audioIdByShort,
		Map<String, String> cueIdByShort, Map<String, String> wordShortByKey,
		Map<String, String> wordKeyByShort) {

	private record ShortMaps(List<String> ids, Map<String, String> shortById,
			Map<String, String> idByShort) {}

	/** {@code buildAgentIdMap}. */
	public static AgentIds of(ProjectEditorState state) {
		ShortMaps clips = shortMaps("c", Clips.sortClipRegions(state.clipRegions()).stream()
				.map(clip -> clip.id()).toList());
		ShortMaps zooms = shortMaps("z", byStart(state.zoomRegions().stream()
				.map(region -> new Span(region.startMs(), region.endMs(), region.id())).toList()));
		ShortMaps speeds = shortMaps("s", byStart(state.speedRegions().stream()
				.map(region -> new Span(region.startMs(), region.endMs(), region.id())).toList()));
		List<AnnotationRegion> annotations = new ArrayList<>(state.annotationRegions());
		annotations.sort(Comparator.comparingLong(AnnotationRegion::startMs)
				.thenComparingInt(AnnotationRegion::zIndex));
		ShortMaps annotationMaps = shortMaps("a",
				annotations.stream().map(AnnotationRegion::id).toList());
		ShortMaps audio = shortMaps("m", byStart(state.audioRegions().stream()
				.map(region -> new Span(region.startMs(), region.endMs(), region.id())).toList()));
		ShortMaps cues = shortMaps("q", byStart(state.autoCaptions().stream()
				.map(cue -> new Span(cue.startMs(), cue.endMs(), cue.id())).toList()));

		List<AgentTranscript.Word> words = AgentTranscript.flattenTranscriptWords(
				state.autoCaptions());
		Map<String, String> wordShortByKey = new LinkedHashMap<>();
		Map<String, String> wordKeyByShort = new LinkedHashMap<>();
		for (int index = 0; index < words.size(); index++) {
			String shortId = "w" + (index + 1);
			wordShortByKey.put(words.get(index).key(), shortId);
			wordKeyByShort.put(shortId, words.get(index).key());
		}
		return new AgentIds(clips.ids(), zooms.ids(), speeds.ids(), annotationMaps.ids(),
				audio.ids(), cues.ids(), words, clips.shortById(), zooms.shortById(),
				speeds.shortById(), annotationMaps.shortById(), audio.shortById(),
				cues.shortById(), clips.idByShort(), zooms.idByShort(), speeds.idByShort(),
				annotationMaps.idByShort(), audio.idByShort(), cues.idByShort(), wordShortByKey,
				wordKeyByShort);
	}

	/** {@code describeShorts}: "none", "z1" or "z1–z3" for error messages. */
	public static String describeShorts(List<String> shortIds, String prefix) {
		if (shortIds.isEmpty()) {
			return "none";
		}
		if (shortIds.size() == 1) {
			return prefix + "1";
		}
		return prefix + "1–" + prefix + shortIds.size();
	}

	/** {@code formatSeconds}. */
	public static String formatSeconds(double ms) {
		return String.format(java.util.Locale.ROOT, "%.2f", Math.round(ms) / 1000.0);
	}

	private record Span(double startMs, double endMs, String id) {}

	private static List<String> byStart(List<Span> spans) {
		List<Span> sorted = new ArrayList<>(spans);
		sorted.sort(Comparator.comparingDouble(Span::startMs).thenComparingDouble(Span::endMs));
		return sorted.stream().map(Span::id).toList();
	}

	private static ShortMaps shortMaps(String prefix, List<String> internalIds) {
		Map<String, String> shortById = new LinkedHashMap<>();
		Map<String, String> idByShort = new LinkedHashMap<>();
		for (int index = 0; index < internalIds.size(); index++) {
			String shortId = prefix + (index + 1);
			shortById.put(internalIds.get(index), shortId);
			idByShort.put(shortId, internalIds.get(index));
		}
		return new ShortMaps(List.copyOf(internalIds), Map.copyOf(shortById),
				Map.copyOf(idByShort));
	}
}
