package dev.gravaai.app.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;

/** The {@code aiControlEnabled} stored-setting semantics: on unless an explicit {@code false}. */
class AgentWiringTest {

	@Test
	@DisplayName("AI control is on when the setting was never stored (fresh install)")
	void missingSettingIsOn() {
		assertTrue(AgentWiring.aiControlEnabled(null));
	}

	@Test
	@DisplayName("AI control is on for an explicit true and for a non-boolean value")
	void nonFalseValuesAreOn() {
		assertTrue(AgentWiring.aiControlEnabled(Json.nodeFactory().booleanNode(true)));
		assertTrue(AgentWiring.aiControlEnabled(Json.nodeFactory().nullNode()));
		assertTrue(AgentWiring.aiControlEnabled(Json.nodeFactory().stringNode("false")));
	}

	@Test
	@DisplayName("AI control only turns off on an explicit stored false")
	void explicitFalseIsOff() {
		assertFalse(AgentWiring.aiControlEnabled(Json.nodeFactory().booleanNode(false)));
	}
}
