package dev.gravaai.app.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.services.agent.AgentJobRegistry;
import tools.jackson.databind.JsonNode;

/**
 * The {@link AgentEditorPort} revert stack against a real headless editor session: the chip's
 * "Revert last AI change" and the {@code revert_ai} op share one core, so both are exercised here
 * (the op-path errors are part of the MCP contract, help text quotes them).
 */
class AgentEditorPortTest {

	private static final String MINIMAL_PROJECT = """
			{
			  "version": 2,
			  "projectId": "11111111-2222-3333-4444-555555555555",
			  "videoPath": "missing-recording.mp4",
			  "createdAt": 1700000000000,
			  "updatedAt": 1700000000000,
			  "projectName": "Session fixture",
			  "editor": {
			    "wallpaper": "/wallpapers/morning-tide.jpg",
			    "shadowIntensity": 0.67,
			    "backgroundBlur": 0,
			    "aspectRatio": "16:9",
			    "borderRadius": 0,
			    "padding": {"top": 20, "bottom": 20, "left": 20, "right": 20, "linked": true},
			    "clipRegions": [{"id": "clip-1", "startMs": 0, "endMs": 6000, "speed": 1}]
			  }
			}
			""";

	@TempDir
	Path directory;

	@Test
	@DisplayName("the chip's revert restores the full state from before the last AI edit")
	void chipRevertRestoresPreAiState() throws Exception {
		try (EditorSession session = openSession()) {
			AgentEditorPort port = port(session);
			applyEdit(port, 0.9);
			assertEquals(0.9, session.store().state().shadowIntensity(), 0.001);

			assertTrue(port.revertLastAiChange().join());
			assertEquals(0.67, session.store().state().shadowIntensity(), 0.001);
		}
	}

	@Test
	@DisplayName("a second chip revert reports nothing to revert")
	void secondChipRevertIsFalse() throws Exception {
		try (EditorSession session = openSession()) {
			AgentEditorPort port = port(session);
			applyEdit(port, 0.9);
			assertTrue(port.revertLastAiChange().join());
			assertFalse(port.revertLastAiChange().join());
			assertEquals(0.67, session.store().state().shadowIntensity(), 0.001);
		}
	}

	@Test
	@DisplayName("the chip's revert refuses once the user edited after the AI batch")
	void chipRevertRefusesAfterUserEdit() throws Exception {
		try (EditorSession session = openSession()) {
			AgentEditorPort port = port(session);
			applyEdit(port, 0.9);
			session.store().apply(new ZoomCommands.Add(1000, 2500));

			assertFalse(port.revertLastAiChange().join());
			assertEquals(0.9, session.store().state().shadowIntensity(), 0.001);
			assertEquals(1, session.store().state().zoomRegions().size());
		}
	}

	@Test
	@DisplayName("the revert_ai op reverts, and its no-change/stale errors survive the refactor")
	void revertAiOpContract() throws Exception {
		try (EditorSession session = openSession()) {
			AgentEditorPort port = port(session);
			CompletionException noChange = assertThrows(CompletionException.class,
					() -> applyHistoryOp(port, "{\"op\":\"revert_ai\"}"));
			assertEquals("there is no AI change to revert", noChange.getCause().getMessage());

			applyEdit(port, 0.9);
			JsonNode result = applyHistoryOp(port, "{\"op\":\"revert_ai\"}");
			assertTrue(result.path("text").asString().contains("reverted the last AI change"));
			assertEquals(0.67, session.store().state().shadowIntensity(), 0.001);
		}

		try (EditorSession session = openSession()) {
			AgentEditorPort port = port(session);
			applyEdit(port, 0.9);
			session.store().apply(new ZoomCommands.Add(1000, 2500));
			CompletionException stale = assertThrows(CompletionException.class,
					() -> applyHistoryOp(port, "{\"op\":\"revert_ai\"}"));
			assertEquals("the project changed since the last AI change; use undo instead so those"
					+ " edits are not lost", stale.getCause().getMessage());
		}
	}

	private EditorSession openSession() throws Exception {
		Path path = directory.resolve("Session fixture.gravaai");
		Files.writeString(path, MINIMAL_PROJECT);
		return EditorSession.open(path);
	}

	/** The port with no exporter/project-open seams; the edit and revert paths never touch them. */
	private AgentEditorPort port(EditorSession session) {
		return new AgentEditorPort(() -> session, () -> null, path -> { }, () -> { },
				new AgentJobRegistry(), new UserData(directory));
	}

	private static void applyEdit(AgentEditorPort port, double shadowIntensity) {
		port.requestEditor("edit", Json.MAPPER.readTree("{\"ops\":[{\"op\":\"set\",\"values\":"
				+ "{\"shadowIntensity\":" + shadowIntensity + "}}]}"), 1_000).join();
	}

	private static JsonNode applyHistoryOp(AgentEditorPort port, String op) {
		return port.requestEditor("edit", Json.MAPPER.readTree("{\"ops\":[" + op + "]}"), 1_000)
				.join();
	}
}
