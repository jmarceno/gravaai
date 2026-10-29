package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** Port of {@code electron/agent/toolSchemas.test.ts} (plan §5.16). */
class AgentToolSchemasTest {

	@Test
	@DisplayName("exposes exactly five tools")
	void exposesFiveTools() {
		assertEquals(List.of("inspect", "edit", "frames", "project", "job"),
				AgentToolSchemas.tools().stream().map(AgentToolSchemas.ToolDefinition::name).toList());
	}

	@Test
	@DisplayName("keeps the tool list under the 1,500-token budget")
	void toolListBudget() {
		assertTrue(AgentToolSchemas.toolListTokenEstimate() <= 1500,
				"tool list token estimate " + AgentToolSchemas.toolListTokenEstimate());
	}

	@Test
	@DisplayName("keeps the server instructions short enough to stay enabled")
	void instructionsBudget() {
		assertTrue(Math.ceil(AgentToolSchemas.instructions().length() / 4.0) <= 200);
	}

	@Test
	@DisplayName("gives every tool a description and an object schema")
	void descriptionsAndSchemas() {
		for (AgentToolSchemas.ToolDefinition tool : AgentToolSchemas.tools()) {
			assertTrue(tool.description().length() > 20, tool.name() + " description too short");
			assertTrue(tool.description().length() < 400, tool.name() + " description too long");
			JsonNode schema = AgentToolSchemas.schemaOf(tool.name());
			assertEquals("object", schema.path("type").asString(), tool.name() + " schema type");
		}
	}

	@Test
	@DisplayName("points at help topics that exist")
	void inspectOffersHelp() {
		JsonNode inspect = AgentToolSchemas.schemaOf("inspect");
		JsonNode view = inspect.path("properties").path("view");
		assertNotNull(view);
		boolean hasHelp = false;
		for (JsonNode value : view.path("enum")) {
			hasHelp |= "help".equals(value.asString());
		}
		assertTrue(hasHelp, "inspect view enum must contain help");
	}
}
