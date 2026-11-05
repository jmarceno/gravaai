package dev.gravaai.ui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pill text the timeline overlay draws for each highlight kind. */
class AgentHighlightViewTest {

	@Test
	@DisplayName("cuts keep the fixed ✦ AI cut pill")
	void cutPill() {
		assertEquals("✦ AI cut", view(AgentHighlightUpdate.Kind.CUT, "join").displayLabel());
	}

	@Test
	@DisplayName("added/changed bands announce the touched element by name")
	void bandPills() {
		assertEquals("✦ AI added badge", view(AgentHighlightUpdate.Kind.ADDED, "badge").displayLabel());
		assertEquals("✦ AI changed badge",
				view(AgentHighlightUpdate.Kind.CHANGED, " badge ").displayLabel());
	}

	@Test
	@DisplayName("a missing span label falls back to the kind alone")
	void bandWithoutLabel() {
		assertEquals("✦ AI added", view(AgentHighlightUpdate.Kind.ADDED, null).displayLabel());
		assertEquals("✦ AI changed", view(AgentHighlightUpdate.Kind.CHANGED, "  ").displayLabel());
	}

	@Test
	@DisplayName("an over-long label is capped to keep the pill readable")
	void longLabelIsCapped() {
		String label = "a".repeat(60);
		assertEquals("✦ AI added " + "a".repeat(37) + "...",
				view(AgentHighlightUpdate.Kind.ADDED, label).displayLabel());
	}

	private static AgentHighlightView view(AgentHighlightUpdate.Kind kind, String label) {
		return new AgentHighlightView("agent-highlight-1", kind, 100, 200, label);
	}
}
