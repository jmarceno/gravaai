package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.TimelineMarker;
import tools.jackson.databind.JsonNode;

/** {@link AgentPlanner} marker ops: marker / update_marker / delete_marker. */
class AgentPlannerMarkerTest {

	private static final long SOURCE_MS = 60_000;

	private static JsonNode json(String text) {
		return Json.MAPPER.readTree(text);
	}

	private static ProjectEditorState baseState() {
		return ProjectPersistence.normalizeProjectEditor(json("{}")).toBuilder()
				.clipRegions(List.of(new ClipRegion("clip-1", 0, 30_000, 0L, null, null, 1, null,
						null, null, null)))
				.build();
	}

	private static AgentPlanner.Ok apply(ProjectEditorState state, String ops) {
		AgentPlanner.Result result = AgentPlanner.apply(state, json(ops),
				new AgentPlanner.Context(SOURCE_MS, List.of(), new AgentPlanner.SequentialIds()));
		return assertInstanceOf(AgentPlanner.Ok.class, result);
	}

	@Test
	@DisplayName("marker places a source-time marker, named from the list when no name is given")
	void addMarker() {
		AgentPlanner.Ok ok = apply(baseState(), """
				[{"op":"marker","at":12.5},{"op":"marker","at":20,"name":"  Demo   part ","chapter":true}]
				""");
		assertEquals(2, ok.state().markers().size());
		TimelineMarker first = ok.state().markers().get(0);
		assertEquals(12_500, first.startMs());
		assertEquals("Marker 1", first.name());
		TimelineMarker second = ok.state().markers().get(1);
		assertEquals(20_000, second.startMs());
		assertEquals("Demo part", second.name());
		assertTrue(second.chapter());
		assertTrue(ok.summary().contains("marker k1 at 12.50s src"), ok.summary());
		assertTrue(ok.summary().contains("(chapter)"), ok.summary());
		assertEquals(2, ok.highlights().size(), "one highlight per marker");
	}

	@Test
	@DisplayName("update_marker retimes, renames and promotes; the k short id resolves")
	void updateMarker() {
		ProjectEditorState state = baseState().toBuilder()
				.markers(List.of(new TimelineMarker("marker-1", 1000, "One", false)))
				.build();
		AgentPlanner.Ok ok = apply(state, """
				[{"op":"update_marker","id":"k1","at":5,"name":"Opening","chapter":true}]
				""");
		TimelineMarker marker = ok.state().markers().get(0);
		assertEquals(5000, marker.startMs());
		assertEquals("Opening", marker.name());
		assertTrue(marker.chapter());
		assertTrue(ok.summary().contains("updated k1"));
	}

	@Test
	@DisplayName("markers are clamped to the source duration and kept in source order")
	void clampsToSource() {
		AgentPlanner.Ok ok = apply(baseState(), """
				[{"op":"marker","at":120},{"op":"marker","at":1}]
				""");
		assertEquals(List.of(1000L, 60_000L),
				ok.state().markers().stream().map(TimelineMarker::startMs).toList());
	}

	@Test
	@DisplayName("an unknown k id fails with the available markers named")
	void unknownMarker() {
		AgentPlanner.Result result = AgentPlanner.apply(baseState(),
				json("[{\"op\":\"update_marker\",\"id\":\"k9\",\"name\":\"x\"}]"),
				new AgentPlanner.Context(SOURCE_MS, List.of(), new AgentPlanner.SequentialIds()));
		AgentPlanner.Failure failure = assertInstanceOf(AgentPlanner.Failure.class, result);
		assertEquals(0, failure.opIndex());
		assertTrue(failure.error().contains("k9 not found; markers: none"), failure.error());
	}

	@Test
	@DisplayName("delete_marker removes one marker, or all of them")
	void deleteMarkers() {
		ProjectEditorState state = baseState().toBuilder().markers(List.of(
				new TimelineMarker("marker-1", 1000, "One", false),
				new TimelineMarker("marker-2", 2000, "Two", true))).build();
		AgentPlanner.Ok one = apply(state, "[{\"op\":\"delete_marker\",\"id\":\"k2\"}]");
		assertEquals(List.of("marker-1"),
				one.state().markers().stream().map(TimelineMarker::id).toList());
		AgentPlanner.Ok all = apply(state, "[{\"op\":\"delete_marker\",\"all\":true}]");
		assertEquals(List.of(), all.state().markers());
		assertTrue(all.summary().contains("deleted 2 markers"), all.summary());
	}

	@Test
	@DisplayName("delete_marker without an id or all:true is an error")
	void deleteNeedsTarget() {
		AgentPlanner.Result result = AgentPlanner.apply(baseState(),
				json("[{\"op\":\"delete_marker\"}]"),
				new AgentPlanner.Context(SOURCE_MS, List.of(), new AgentPlanner.SequentialIds()));
		AgentPlanner.Failure failure = assertInstanceOf(AgentPlanner.Failure.class, result);
		assertTrue(failure.error().contains("delete_marker needs \"id\" or \"all\": true"),
				failure.error());
	}

	@Test
	@DisplayName("markers cannot be replaced through the set op")
	void setRejectsMarkers() {
		AgentPlanner.Result result = AgentPlanner.apply(baseState(),
				json("[{\"op\":\"set\",\"markers\":[]}]"),
				new AgentPlanner.Context(SOURCE_MS, List.of(), new AgentPlanner.SequentialIds()));
		AgentPlanner.Failure failure = assertInstanceOf(AgentPlanner.Failure.class, result);
		assertTrue(failure.error().contains("its own op"), failure.error());
	}
}
