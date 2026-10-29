package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AnnotationPosition;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationSize;
import dev.gravaai.core.project.AnnotationType;
import dev.gravaai.core.project.ArrowDirection;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionCueWord;
import dev.gravaai.core.project.ClipCameraLayout;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Padding;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomFocus;
import dev.gravaai.core.project.ZoomMode;
import dev.gravaai.core.project.ZoomRegion;
import dev.gravaai.services.agent.AgentPlanner.Context;
import dev.gravaai.services.agent.AgentPlanner.Failure;
import dev.gravaai.services.agent.AgentPlanner.Highlight;
import dev.gravaai.services.agent.AgentPlanner.Ok;
import dev.gravaai.services.agent.AgentPlanner.Result;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Port of {@code src/components/video-editor/agent/applyAgentOps.test.ts}. */
class AgentPlannerTest {

	private static final long SOURCE_MS = 60_000;

	private static final class IdFactory implements Function<AgentPlanner.IdKind, String> {
		private final Map<AgentPlanner.IdKind, Integer> counters = new LinkedHashMap<>();

		@Override
		public String apply(AgentPlanner.IdKind kind) {
			int next = counters.merge(kind, 1, Integer::sum);
			return kind.wire() + "-new-" + next;
		}
	}

	private static JsonNode json(String text) {
		return Json.MAPPER.readTree(text);
	}

	private static ProjectEditorState defaults() {
		return ProjectPersistence.normalizeProjectEditor(json("{}"));
	}

	private static ClipRegion clip(String id, long startMs, long endMs) {
		return new ClipRegion(id, startMs, endMs, null, null, null, 1, null, null, null, null);
	}

	private static ProjectEditorState baseState() {
		return defaults().toBuilder().clipRegions(List.of(clip("clip-1", 0, SOURCE_MS))).build();
	}

	private static ZoomRegion zoom(String id, long startMs, long endMs) {
		return new ZoomRegion(id, startMs, endMs, ZoomDepth.fromWire(3),
				new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL);
	}

	private static CaptionCue cue(String id, long startMs, long endMs, String text,
			List<CaptionCueWord> words) {
		return new CaptionCue(id, startMs, endMs, text, words);
	}

	private static List<CaptionCue> transcriptCues() {
		return List.of(
				cue("cue-1", 0, 3000, "hello um there",
						List.of(new CaptionCueWord("hello", 0, 400, null),
								new CaptionCueWord("um", 500, 700, true),
								new CaptionCueWord("there", 1000, 1400, true))),
				cue("cue-2", 4000, 5200, "bye now",
						List.of(new CaptionCueWord("bye", 4000, 4400, null),
								new CaptionCueWord("now", 5000, 5200, true))));
	}

	private static Context context() {
		return new Context(SOURCE_MS, List.of(), new IdFactory());
	}

	private static Context context(List<AgentAutoZoom.Sample> telemetry) {
		return new Context(SOURCE_MS, telemetry, new IdFactory());
	}

	private static Result apply(ProjectEditorState state, String opsJson) {
		return AgentPlanner.apply(state, json(opsJson), context());
	}

	private static Result apply(ProjectEditorState state, String opsJson, Context ctx) {
		return AgentPlanner.apply(state, json(opsJson), ctx);
	}

	private static Ok ok(Result result) {
		if (!(result instanceof Ok ok)) {
			throw new AssertionError("expected ok, got: " + ((Failure) result).error());
		}
		return ok;
	}

	private static Failure failed(Result result, String messagePart) {
		if (!(result instanceof Failure failure)) {
			throw new AssertionError("expected a failure, got ok");
		}
		assertTrue(failure.error().contains(messagePart),
				"expected \"" + messagePart + "\" in: " + failure.error());
		return failure;
	}

	private static void assertClips(Ok result, long[][] spans) {
		assertEquals(spans.length, result.state().clipRegions().size());
		for (int index = 0; index < spans.length; index++) {
			assertEquals(spans[index][0], result.state().clipRegions().get(index).startMs());
			assertEquals(spans[index][1], result.state().clipRegions().get(index).endMs());
		}
	}

	@Test
	@DisplayName("cuts a timeline range and ripples anchored regions")
	void cutsTimelineRange() {
		ProjectEditorState state = baseState().toBuilder().zoomRegions(List.of(zoom("zoom-a", 25_000, 27_000), zoom("zoom-b", 12_000, 14_000))).build();
		Ok result = ok(apply(state, "[{\"op\":\"cut\",\"from\":10,\"to\":20}]"));
		assertClips(result, new long[][] {{0, 10_000}, {10_000, 50_000}});
		assertEquals(List.of("zoom-a"),
				result.state().zoomRegions().stream().map(ZoomRegion::id).toList());
		assertEquals(15_000, result.state().zoomRegions().get(0).startMs());
		assertEquals(17_000, result.state().zoomRegions().get(0).endMs());
		assertTrue(result.summary().contains("cut 10.00s"), result.summary());
		assertEquals(Highlight.Kind.CUT, result.highlights().get(0).kind());
		assertEquals(10_000, result.highlights().get(0).startMs());
	}

	@Test
	@DisplayName("fails when the cut removes everything")
	void cutEverythingFails() {
		failed(apply(baseState(), "[{\"op\":\"cut\",\"from\":0,\"to\":60}]"),
				"nothing to cut");
	}

	@Test
	@DisplayName("rolls the whole batch back when one op fails")
	void atomicBatch() {
		ProjectEditorState state = baseState();
		Failure failure = failed(apply(state, "[{\"op\":\"cut\",\"from\":10,\"to\":12},"
				+ "{\"op\":\"zoom\",\"from\":1,\"to\":2,\"depth\":9}]"), "depth");
		assertEquals(1, failure.opIndex());
		assertEquals(1, state.clipRegions().size());
	}

	@Test
	@DisplayName("keeps only the given ranges")
	void keepRanges() {
		Ok result = ok(apply(baseState(),
				"[{\"op\":\"keep\",\"ranges\":[[10,20]]}]"));
		assertClips(result, new long[][] {{0, 10_000}});
	}

	@Test
	@DisplayName("splits without losing regions on either side")
	void splitClip() {
		ProjectEditorState state = baseState().toBuilder().zoomRegions(List.of(zoom("zoom-left", 1_000, 2_000), zoom("zoom-right", 8_000, 9_000))).build();
		Ok result = ok(apply(state, "[{\"op\":\"split\",\"at\":5}]"));
		assertEquals(List.of("clip-1", "clip-new-2"),
				result.state().clipRegions().stream().map(ClipRegion::id).toList());
		assertEquals(List.of(0L, 5_000L),
				List.of(result.state().clipRegions().get(0).startMs(),
						result.state().clipRegions().get(0).endMs()));
		assertEquals(List.of(5_000L, 60_000L),
				List.of(result.state().clipRegions().get(1).startMs(),
						result.state().clipRegions().get(1).endMs()));
		assertEquals(List.of("zoom-left", "zoom-right"),
				result.state().zoomRegions().stream().map(ZoomRegion::id).toList());
	}

	@Test
	@DisplayName("changes clip speed and rescales zooms")
	void clipSpeed() {
		ProjectEditorState state = baseState().toBuilder().zoomRegions(List.of(zoom("zoom-1", 2_000, 4_000))).build();
		Ok result = ok(apply(state, "[{\"op\":\"clip\",\"id\":\"c1\",\"speed\":2}]"));
		assertEquals(2, result.state().clipRegions().get(0).speed());
		assertEquals(30_000, result.state().clipRegions().get(0).endMs());
		assertEquals(1_000, result.state().zoomRegions().get(0).startMs());
		assertEquals(2_000, result.state().zoomRegions().get(0).endMs());
	}

	@Test
	@DisplayName("sets muted, camera layout and transitions")
	void clipFlags() {
		Ok result = ok(apply(baseState(),
				"[{\"op\":\"clip\",\"id\":\"c1\",\"muted\":true,"
						+ "\"cameraLayout\":\"hide-camera\",\"transition\":{\"ms\":600}}]"));
		ClipRegion clip = result.state().clipRegions().get(0);
		assertEquals(true, clip.muted());
		assertEquals(ClipCameraLayout.fromWire("hide-camera"), clip.cameraLayout());
		assertEquals("fade-black", clip.transitionOut().kind().wire());
		assertEquals(600, clip.transitionOut().durationMs());
		Ok cleared = ok(apply(result.state(),
				"[{\"op\":\"clip\",\"id\":\"c1\",\"transition\":null}]"));
		assertEquals(null, cleared.state().clipRegions().get(0).transitionOut());
	}

	@Test
	@DisplayName("trims a clip and re-packs the sequence")
	void trimClip() {
		ProjectEditorState state = baseState().toBuilder().clipRegions(List.of(
				clip("clip-1", 0, 10_000),
				new ClipRegion("clip-2", 10_000, 20_000, 10_000L, null, null, 1, null, null,
						null, null))).build();
		Ok result = ok(apply(state, "[{\"op\":\"trim_clip\",\"id\":\"c2\",\"start\":12}]"));
		assertEquals(List.of(10_000L, 18_000L),
				List.of(result.state().clipRegions().get(1).startMs(),
						result.state().clipRegions().get(1).endMs()));
		assertEquals(12_000, result.state().clipRegions().get(1).sourceStartMs());
	}

	@Test
	@DisplayName("deletes and reorders clips")
	void deleteReorderClips() {
		ProjectEditorState state = baseState().toBuilder().clipRegions(List.of(
				clip("clip-1", 0, 10_000),
				new ClipRegion("clip-2", 10_000, 20_000, 10_000L, null, null, 1, null, null,
						null, null))).build();
		Ok moved = ok(apply(state, "[{\"op\":\"move_clip\",\"id\":\"c2\",\"toIndex\":0}]"));
		assertEquals(List.of("clip-2", "clip-1"),
				moved.state().clipRegions().stream().map(ClipRegion::id).toList());
		Ok deleted = ok(apply(state, "[{\"op\":\"delete_clip\",\"id\":\"c2\"}]"));
		assertEquals(1, deleted.state().clipRegions().size());
	}

	@Test
	@DisplayName("reports unknown clip ids with the available range")
	void unknownClipId() {
		failed(apply(baseState(), "[{\"op\":\"delete_clip\",\"id\":\"c9\"}]"),
				"c9 not found; clips: c1");
	}

	private static ProjectEditorState stateWithTranscript() {
		return baseState().toBuilder().autoCaptions(transcriptCues()).build();
	}

	@Test
	@DisplayName("cuts words by exact phrase and restores them")
	void cutWordsAndRestore() {
		Ok cut = ok(apply(stateWithTranscript(),
				"[{\"op\":\"cut_words\",\"text\":\"um\"}]"));
		assertEquals(2, cut.state().clipRegions().size());
		assertEquals(SOURCE_MS - 450, cut.state().clipRegions().get(1).endMs());
		assertTrue(cut.summary().contains("cut 1 word"), cut.summary());
		Ok restored = ok(apply(cut.state(),
				"[{\"op\":\"restore_words\",\"ids\":[\"w2\"]}]"));
		assertEquals(1, restored.state().clipRegions().size());
		assertEquals(0, restored.state().clipRegions().get(0).startMs());
		assertEquals(SOURCE_MS, restored.state().clipRegions().get(0).endMs());
	}

	@Test
	@DisplayName("cuts filler words using the caption language")
	void cutFillers() {
		Ok result = ok(apply(stateWithTranscript(), "[{\"op\":\"cut_fillers\"}]"));
		assertTrue(result.summary().contains("filler word"), result.summary());
	}

	@Test
	@DisplayName("cuts pauses longer than the threshold")
	void cutPauses() {
		Ok result = ok(apply(stateWithTranscript(),
				"[{\"op\":\"cut_pauses\",\"min\":0.4,\"keep\":0.1}]"));
		assertTrue(result.summary().contains("2 pauses"), result.summary());
	}

	@Test
	@DisplayName("reports instead of failing when a sweep finds nothing")
	void cleanSweep() {
		Ok clean = ok(apply(stateWithTranscript(),
				"[{\"op\":\"cut_fillers\",\"lang\":\"pt\"},"
						+ "{\"op\":\"cut_pauses\",\"min\":60}]"));
		assertTrue(clean.summary().contains("no filler words found"), clean.summary());
		assertTrue(clean.summary().contains("no pauses longer than 60s"), clean.summary());
		assertEquals(stateWithTranscript().clipRegions(), clean.state().clipRegions());
	}

	@Test
	@DisplayName("cuts a source range directly")
	void cutSourceRange() {
		Ok result = ok(apply(baseState(),
				"[{\"op\":\"cut_range_src\",\"from\":30,\"to\":40}]"));
		assertClips(result, new long[][] {{0, 30_000}, {30_000, 50_000}});
	}

	@Test
	@DisplayName("explains when there is no transcript")
	void noTranscript() {
		failed(apply(baseState(), "[{\"op\":\"cut_words\",\"all\":true}]"),
				"no transcript");
	}

	@Test
	@DisplayName("adds, updates and deletes zooms")
	void zoomCrud() {
		Ok added = ok(apply(baseState(),
				"[{\"op\":\"zoom\",\"from\":3,\"to\":6,\"depth\":5,"
						+ "\"focus\":{\"x\":0.2,\"y\":0.8}}]"));
		assertEquals(1, added.state().zoomRegions().size());
		assertEquals(3_000, added.state().zoomRegions().get(0).startMs());
		assertEquals(6_000, added.state().zoomRegions().get(0).endMs());
		assertEquals(ZoomDepth.fromWire(5), added.state().zoomRegions().get(0).depth());
		assertEquals(0.2, added.state().zoomRegions().get(0).focus().cx(), 1e-9);
		Ok updated = ok(apply(added.state(),
				"[{\"op\":\"update_zoom\",\"id\":\"z1\",\"to\":8,\"depth\":2}]"));
		assertEquals(8_000, updated.state().zoomRegions().get(0).endMs());
		assertEquals(ZoomDepth.fromWire(2), updated.state().zoomRegions().get(0).depth());
		Ok deleted = ok(apply(updated.state(),
				"[{\"op\":\"delete_zoom\",\"id\":\"z1\"}]"));
		assertEquals(0, deleted.state().zoomRegions().size());
	}

	@Test
	@DisplayName("rejects overlapping zooms")
	void overlappingZooms() {
		ProjectEditorState state = baseState().toBuilder().zoomRegions(List.of(zoom("zoom-1", 2_000, 6_000))).build();
		failed(apply(state, "[{\"op\":\"zoom\",\"from\":5,\"to\":7}]"), "overlaps z1");
	}

	@Test
	@DisplayName("suggests zooms from click telemetry")
	void autoZoom() {
		List<AgentAutoZoom.Sample> telemetry = List.of(
				new AgentAutoZoom.Sample(1_000, 0.2, 0.3, null),
				new AgentAutoZoom.Sample(1_100, 0.2, 0.3, "click"),
				new AgentAutoZoom.Sample(1_200, 0.21, 0.3, null));
		Ok result = ok(apply(baseState(), "[{\"op\":\"auto_zoom\"}]",
				context(telemetry)));
		assertEquals(1, result.state().zoomRegions().size());
		assertEquals(ZoomMode.AUTO, result.state().zoomRegions().get(0).mode());
		assertEquals(600, result.state().zoomRegions().get(0).startMs());
	}

	@Test
	@DisplayName("adds speed regions and deletes them")
	void speedCrud() {
		Ok added = ok(apply(baseState(),
				"[{\"op\":\"speed\",\"from\":5,\"to\":10,\"rate\":2}]"));
		assertEquals(1, added.state().speedRegions().size());
		Ok cleared = ok(apply(added.state(),
				"[{\"op\":\"delete_speed\",\"all\":true}]"));
		assertEquals(0, cleared.state().speedRegions().size());
	}

	@Test
	@DisplayName("adds every annotation type")
	void annotationTypes() {
		Ok result = ok(apply(baseState(),
				"[{\"op\":\"text\",\"from\":1,\"to\":4,\"text\":\"Title\","
						+ "\"x\":0.5,\"y\":0.2,\"w\":0.6,\"h\":0.1},"
						+ "{\"op\":\"figure\",\"from\":5,\"to\":6,\"arrow\":\"up-right\","
						+ "\"x\":0.3,\"y\":0.3},"
						+ "{\"op\":\"blur\",\"from\":7,\"to\":8,\"x\":0.1,\"y\":0.1,"
						+ "\"w\":0.2,\"h\":0.2},"
						+ "{\"op\":\"image\",\"from\":9,\"to\":10,"
						+ "\"path\":\"/tmp/logo.png\","
						+ "\"imageContent\":\"data:image/png;base64,AAA\"}]"));
		assertEquals(List.of(AnnotationType.TEXT, AnnotationType.FIGURE, AnnotationType.BLUR,
				AnnotationType.IMAGE), result.state().annotationRegions().stream()
						.map(AnnotationRegion::type).toList());
		AnnotationRegion text = result.state().annotationRegions().get(0);
		assertEquals("Title", text.textContent());
		assertEquals(new AnnotationPosition(20, 15), text.position());
		assertEquals(new AnnotationSize(60, 10), text.size());
		assertEquals(ArrowDirection.UP_RIGHT,
				result.state().annotationRegions().get(1).figureData().arrowDirection());
	}

	@Test
	@DisplayName("requires image content to be loaded before applying")
	void imageNeedsContent() {
		failed(apply(baseState(),
				"[{\"op\":\"image\",\"from\":1,\"to\":2,\"path\":\"/tmp/missing.png\"}]"),
				"could not read image");
	}

	@Test
	@DisplayName("updates and deletes annotations")
	void annotationCrud() {
		Ok added = ok(apply(baseState(),
				"[{\"op\":\"text\",\"from\":1,\"to\":2,\"text\":\"hi\","
						+ "\"x\":0.1,\"y\":0.1}]"));
		Ok updated = ok(apply(added.state(),
				"[{\"op\":\"update_annotation\",\"id\":\"a1\",\"text\":\"bye\","
						+ "\"x\":0.8,\"y\":0.9}]"));
		assertEquals("bye", updated.state().annotationRegions().get(0).textContent());
		assertEquals(new AnnotationPosition(65, 80),
				updated.state().annotationRegions().get(0).position());
		Ok deleted = ok(apply(updated.state(),
				"[{\"op\":\"delete_annotation\",\"all\":true}]"));
		assertEquals(0, deleted.state().annotationRegions().size());
	}

	@Test
	@DisplayName("adds audio regions and controls the source audio")
	void audioAndSourceAudio() {
		Ok added = ok(apply(baseState(),
				"[{\"op\":\"audio\",\"path\":\"/tmp/music.mp3\",\"at\":2,\"to\":12,"
						+ "\"volume\":0.4}]"));
		assertEquals(2_000, added.state().audioRegions().get(0).startMs());
		assertEquals(12_000, added.state().audioRegions().get(0).endMs());
		assertEquals("/tmp/music.mp3", added.state().audioRegions().get(0).audioPath());
		assertEquals(0.4, added.state().audioRegions().get(0).volume(), 1e-9);
		Ok sourced = ok(apply(added.state(),
				"[{\"op\":\"source_audio\",\"clip\":\"c1\",\"muted\":true,"
						+ "\"volume\":0.5}]"));
		assertEquals(true, sourced.state().clipRegions().get(0).muted());
		JsonNode mixed = sourced.state().sourceAudioTrackSettingsByClip().path("clip-1")
				.path("mixed");
		assertEquals(0.5, mixed.path("volume").asDouble(), 1e-9);
		assertEquals(false, mixed.path("normalize").asBoolean());
	}

	@Test
	@DisplayName("adds, updates, splits, merges and deletes caption cues")
	void captionCrud() {
		Ok added = ok(apply(baseState().toBuilder().autoCaptions(List.of()).build(),
				"[{\"op\":\"caption\",\"from\":60,\"to\":62,\"text\":\"one two\"}]"));
		assertEquals(1, added.state().autoCaptions().size());
		assertEquals(true, added.state().autoCaptionSettings().enabled());
		Ok updated = ok(apply(added.state(),
				"[{\"op\":\"update_caption\",\"id\":\"q1\",\"text\":\"one two three\"}]"));
		assertEquals("one two three", updated.state().autoCaptions().get(0).text());
		Ok split = ok(apply(updated.state(),
				"[{\"op\":\"split_caption\",\"id\":\"q1\",\"at\":61}]"));
		assertEquals(2, split.state().autoCaptions().size());
		Ok merged = ok(apply(split.state(),
				"[{\"op\":\"merge_captions\",\"a\":\"q1\",\"b\":\"q2\"}]"));
		assertEquals(1, merged.state().autoCaptions().size());
		Ok deleted = ok(apply(merged.state(),
				"[{\"op\":\"delete_caption\",\"all\":true}]"));
		assertEquals(0, deleted.state().autoCaptions().size());
	}

	@Test
	@DisplayName("styles captions through the autoCaptionSettings normalizer")
	void captionStyles() {
		Ok result = ok(apply(baseState(),
				"[{\"op\":\"captions_style\",\"settings\":{\"fontSize\":44,"
						+ "\"maxRows\":2}}]"));
		assertEquals(44, result.state().autoCaptionSettings().fontSize(), 1e-9);
		assertEquals(2, result.state().autoCaptionSettings().maxRows());
		failed(apply(baseState(),
				"[{\"op\":\"captions_style\",\"fontSize\":400}]"),
				"invalid value for \"autoCaptionSettings.fontSize\"");
	}

	@Test
	@DisplayName("applies dotted paths and linked padding")
	void dottedSettings() {
		Ok result = ok(apply(baseState(),
				"[{\"op\":\"set\",\"values\":{\"padding.top\":30,"
						+ "\"aspectRatio\":\"9:16\"}}]"));
		assertEquals(new Padding(30, 30, 30, 30, true), result.state().padding());
		assertEquals("9:16", result.state().aspectRatio());
	}

	@Test
	@DisplayName("rejects unknown keys, out-of-range values and derived values")
	void settingGuards() {
		failed(apply(baseState(), "[{\"op\":\"set\",\"nope\":1}]"),
				"unknown setting \"nope\"");
		failed(apply(baseState(), "[{\"op\":\"set\",\"backgroundBlur\":99}]"),
				"invalid value");
		failed(apply(baseState(), "[{\"op\":\"set\",\"zoomSmoothness\":0.9}]"),
				"invalid value");
		failed(apply(baseState(), "[{\"op\":\"set\",\"clipRegions\":[]}]"), "own op");
	}

	@Test
	@DisplayName("refuses history ops mixed with edits")
	void mixedHistory() {
		Failure failure = failed(apply(baseState(),
				"[{\"op\":\"cut\",\"from\":1,\"to\":2},{\"op\":\"undo\"}]"),
				"cannot be mixed");
		assertEquals(1, failure.opIndex());
	}

	@Test
	@DisplayName("refuses history ops on their own (the editor applies them)")
	void historyAlone() {
		failed(apply(baseState(), "[{\"op\":\"revert_ai\"}]"), "applied by the editor");
	}

	@Test
	@DisplayName("names the failing op index")
	void parseErrorIndex() {
		Failure failure = failed(apply(baseState(),
				"[{\"op\":\"cut\",\"from\":1,\"to\":2},{\"op\":\"wat\"}]"),
				"unknown op \"wat\"");
		assertEquals(1, failure.opIndex());
	}

	@Test
	@DisplayName("requires a non-empty ops array")
	void emptyOps() {
		failed(apply(baseState(), "[]"), "non-empty array");
	}

	private static ProjectEditorState twoZooms() {
		return baseState().toBuilder().zoomRegions(List.of(zoom("zoom-a", 1_000, 2_000),
				zoom("zoom-b", 3_000, 4_000), zoom("zoom-c", 5_000, 6_000))).build();
	}

	@Test
	@DisplayName("resolves short ids against the state the agent read, not mid-batch renumbering")
	void shortIdsResolvedUpfront() {
		Ok result = ok(apply(twoZooms(),
				"[{\"op\":\"delete_zoom\",\"id\":\"z1\"},"
						+ "{\"op\":\"delete_zoom\",\"id\":\"z2\"}]"));
		assertEquals(List.of("zoom-c"),
				result.state().zoomRegions().stream().map(ZoomRegion::id).toList());
	}

	@Test
	@DisplayName("keeps the identity of everything the batch did not touch")
	void keepsUntouchedIdentity() {
		ProjectEditorState state = twoZooms();
		Ok result = ok(apply(state, "[{\"op\":\"delete_zoom\",\"id\":\"z1\"}]"));
		assertNotEquals(state.zoomRegions(), result.state().zoomRegions());
		assertSame(state.clipRegions(), result.state().clipRegions());
		assertSame(state.autoCaptions(), result.state().autoCaptions());
		assertSame(state.webcam(), result.state().webcam());
		assertEquals(3, state.zoomRegions().size());
	}

	@Test
	@DisplayName("names regions created mid-batch by their final short id")
	void summaryShortId() {
		Ok result = ok(apply(twoZooms(), "[{\"op\":\"zoom\",\"from\":0,\"to\":0.5}]"));
		assertTrue(result.summary().startsWith("zoom z1 0.00–0.50s"), result.summary());
	}

	@Test
	@DisplayName("marks a cut as a point where the two sides meet")
	void cutHighlight() {
		Ok result = ok(apply(baseState(), "[{\"op\":\"cut\",\"from\":10,\"to\":20}]"));
		Highlight highlight = result.highlights().get(0);
		assertEquals(Highlight.Kind.CUT, highlight.kind());
		assertEquals(10_000, highlight.startMs());
		assertEquals(10_001, highlight.endMs());
	}

	@Test
	@DisplayName("marks every join a transcript cut creates")
	void transcriptJoinHighlights() {
		Ok result = ok(apply(stateWithTranscript(), "[{\"op\":\"cut_fillers\"}]"));
		assertEquals(1, result.highlights().stream()
				.filter(highlight -> highlight.kind() == Highlight.Kind.CUT).count());
	}

	@Test
	@DisplayName("refuses whole-object writes that change a protected key")
	void webcamGuards() {
		ProjectEditorState state = baseState();
		ObjectNode webcam = (ObjectNode) Json.MAPPER.valueToTree(state.webcam());
		ObjectNode allowed = webcam.deepCopy();
		allowed.put("size", 30);
		ObjectNode valuesAllowed = Json.nodeFactory().objectNode();
		valuesAllowed.set("webcam", allowed);
		ObjectNode opAllowed = Json.nodeFactory().objectNode();
		opAllowed.put("op", "set");
		opAllowed.set("values", valuesAllowed);
		ok(apply(state, "[" + Json.MAPPER.writeValueAsString(opAllowed) + "]"));
		ObjectNode blocked = webcam.deepCopy();
		blocked.put("sourcePath", "/x.mp4");
		ObjectNode valuesBlocked = Json.nodeFactory().objectNode();
		valuesBlocked.set("webcam", blocked);
		ObjectNode opBlocked = Json.nodeFactory().objectNode();
		opBlocked.put("op", "set");
		opBlocked.set("values", valuesBlocked);
		failed(apply(state, "[" + Json.MAPPER.writeValueAsString(opBlocked) + "]"),
				"webcam.sourcePath");
	}
}
