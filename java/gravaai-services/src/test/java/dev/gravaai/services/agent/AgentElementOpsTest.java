package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.SceneElement;
import dev.gravaai.services.agent.AgentPlanner.Failure;
import dev.gravaai.services.agent.AgentPlanner.Ok;
import dev.gravaai.services.agent.AgentPlanner.Result;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The element ops of the agent {@code edit} tool, through the real planner. */
class AgentElementOpsTest {

	private static final String BADGE = """
			export const fps = 30;
			export const durationInFrames = 60;
			export const defaultProps = { text: 'NEW' };
			export const propsSchema = { text: 'string', size: { type: 'number', min: 10, max: 200 } };
			export function render(ctx, { frame, fps, width, height, props }) {
			  const s = spring({ frame, fps });
			  ctx.fillStyle = '#f00';
			  ctx.fillRect(0, 0, width * s, height);
			}
			""";

	private static ProjectEditorState base() {
		return ProjectPersistence.normalizeProjectEditor(Json.nodeFactory().objectNode())
				.toBuilder().clipRegions(List.of(new ClipRegion("clip-1", 0, 30_000, null, null,
						null, 1, null, null, null, null)))
				.build();
	}

	private static ObjectNode op(String name) {
		return Json.nodeFactory().objectNode().put("op", name);
	}

	private static Result apply(ProjectEditorState state, ObjectNode... ops) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (ObjectNode op : ops) {
			array.add(op);
		}
		return AgentPlanner.apply(state, array, AgentPlanner.Context.of(30_000));
	}

	private static Ok ok(Result result) {
		if (result instanceof Failure failure) {
			throw new AssertionError(failure.error());
		}
		return (Ok) result;
	}

	private static String failure(Result result) {
		if (result instanceof Failure failure) {
			return String.valueOf(failure.error());
		}
		throw new AssertionError("expected a failure");
	}

	@Test
	void writesComponentPlacesElementAndPersists() {
		ObjectNode element = op("element").put("component", "badge").put("from", 2)
				.put("x", 0.8).put("y", 0.2).put("w", 0.2).put("h", 0.3);
		element.putObject("props").put("text", "HOT");
		Ok result = ok(apply(base(), op("component").put("name", "badge").put("source", BADGE),
				element));
		ProjectEditorState state = result.state();
		assertEquals(1, state.elementComponents().size());
		SceneElement placed = state.elements().getFirst();
		assertEquals(2000, placed.startMs());
		assertEquals(4000, placed.endMs(), "durationInFrames 60 at 30 fps");
		assertEquals(70, placed.box().x(), 1e-9);
		assertEquals(5, placed.box().y(), 1e-9);
		assertEquals("HOT", placed.props().get("text").asString());
		assertTrue(result.summary().contains("added element e1 badge"), result.summary());

		// Round trip through the project JSON the editor saves.
		ProjectEditorState reloaded = ProjectPersistence.normalizeProjectEditor(
				ProjectDocument.writeEditor(state, Json.nodeFactory().objectNode()));
		assertEquals(state.elements(), reloaded.elements());
		assertEquals(state.elementComponents(), reloaded.elementComponents());
	}

	@Test
	void brokenComponentRollsBackTheBatch() {
		String message = failure(apply(base(),
				op("component").put("name", "ok").put("source", BADGE),
				op("component").put("name", "bad").put("source",
						"export function render(ctx) {\n  ctx.nope();\n}\n")));
		assertTrue(message.contains("failed validation"), message);
		assertTrue(message.contains("line 2"), message);
	}

	@Test
	void builtinNamespaceIsReserved() {
		String message = failure(apply(base(),
				op("component").put("name", "gravaai/title-card").put("source", BADGE)));
		assertTrue(message.contains("reserved"), message);
	}

	@Test
	void propsAreValidatedAgainstTheSchema() {
		ObjectNode element = op("element").put("component", "gravaai/title-card").put("from", 0);
		element.putObject("props").put("fontSize", 5000);
		String message = failure(apply(base(), element));
		assertTrue(message.contains("fontSize") && message.contains("at most"), message);

		ObjectNode unknown = op("element").put("component", "gravaai/title-card").put("from", 0);
		unknown.putObject("props").put("colour", "#fff");
		assertTrue(failure(apply(base(), unknown)).contains("unknown prop"));
	}

	@Test
	void updateMergesPropsAndDeleteRemoves() {
		ObjectNode place = op("element").put("component", "gravaai/title-card").put("from", 1)
				.put("to", 3);
		place.putObject("props").put("title", "One").put("subtitle", "Sub");
		ProjectEditorState placed = ok(apply(base(), place)).state();

		ObjectNode update = op("update_element").put("id", "e1").put("to", 5);
		update.putObject("props").put("title", "Two").putNull("subtitle");
		ProjectEditorState updated = ok(apply(placed, update)).state();
		SceneElement element = updated.elements().getFirst();
		assertEquals("Two", element.props().get("title").asString());
		assertTrue(!element.props().has("subtitle"));
		assertEquals(5000, element.endMs());

		assertTrue(failure(apply(updated, op("delete_component").put("name", "x")))
				.contains("no project component"));
		assertEquals(0, ok(apply(updated, op("delete_element").put("id", "e1"))).state()
				.elements().size());
	}

	@Test
	void componentInUseCannotBeDeleted() {
		ProjectEditorState state = ok(apply(base(),
				op("component").put("name", "badge").put("source", BADGE),
				op("element").put("component", "badge").put("from", 0))).state();
		assertTrue(failure(apply(state, op("delete_component").put("name", "badge")))
				.contains("used by e1"));
	}

	@Test
	void cutsRippleElements() {
		ProjectEditorState state = ok(apply(base(),
				op("element").put("component", "gravaai/counter").put("from", 10).put("to", 12)))
				.state();
		ProjectEditorState cut = ok(apply(state, op("cut").put("from", 2).put("to", 5))).state();
		assertEquals(7000, cut.elements().getFirst().startMs());
		assertEquals(9000, cut.elements().getFirst().endMs());
	}
}
