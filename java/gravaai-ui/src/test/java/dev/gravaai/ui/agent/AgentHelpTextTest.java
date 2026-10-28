package dev.gravaai.ui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link AgentHelpText}: parity of the ported {@code help.ts} reference. */
class AgentHelpTextTest {

	@DisplayName("the topic list matches help.ts order")
	@Test
	void topics() {
		assertEquals(17, AgentHelpText.TOPICS.size());
		assertEquals("clips", AgentHelpText.TOPICS.get(0));
		assertEquals("conventions", AgentHelpText.TOPICS.get(16));
		assertTrue(AgentHelpText.TOPICS.contains("export"));
		assertTrue(AgentHelpText.TOPICS.contains("jobs"));
	}

	@DisplayName("a blank topic returns the index, which lists every op family")
	@Test
	void index() {
		assertEquals(AgentHelpText.INDEX, AgentHelpText.help(null));
		assertEquals(AgentHelpText.INDEX, AgentHelpText.help("   "));
		assertTrue(AgentHelpText.INDEX.startsWith("Gravaai — call inspect"));
		assertTrue(AgentHelpText.INDEX.contains("edit applies a batch of ops atomically"));
		for (String topic : AgentHelpText.TOPICS) {
			assertTrue(AgentHelpText.INDEX.contains(topic), "index mentions " + topic);
		}
		assertFalse(AgentHelpText.INDEX.endsWith("\n"));
	}

	@DisplayName("a topic is returned prefixed with its name, case-insensitively")
	@Test
	void topicsResolve() {
		String zoom = AgentHelpText.help(" Zoom ");
		assertTrue(zoom.startsWith("zoom\n"));
		assertTrue(zoom.contains("auto_zoom"));
		assertTrue(AgentHelpText.help("conventions").contains("STALE"));
		assertTrue(AgentHelpText.help("export").contains("options.captionsSidecar"));
		assertTrue(AgentHelpText.help("jobs").contains("Only one job of a kind runs at a time."));
		assertFalse(zoom.endsWith("\n"));
	}

	@DisplayName("every topic resolves to non-empty text")
	@Test
	void everyTopicHasText() {
		for (String topic : AgentHelpText.TOPICS) {
			String text = AgentHelpText.help(topic);
			assertTrue(text.startsWith(topic + "\n"), "prefix for " + topic);
			assertTrue(text.length() > topic.length() + 20, "content for " + topic);
		}
	}

	@DisplayName("an unknown topic lists the available topics")
	@Test
	void unknownTopic() {
		String text = AgentHelpText.help("nope");
		assertEquals("unknown help topic \"nope\"; topics: " + String.join(", ", AgentHelpText.TOPICS),
				text);
	}
}
