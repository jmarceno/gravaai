package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.platform.process.ProcessResult;
import dev.gravaai.platform.process.ProcessRunner;
import tools.jackson.databind.JsonNode;

/**
 * Plan §5.16: tool names, descriptions and JSON schemas must stay byte-identical to
 * {@code electron/agent/toolSchemas.ts}. The resource {@code agent/tool-schemas.json} is generated
 * from the TS constants and this test re-evaluates the TS module with Node's type stripping and
 * compares the bytes (same pattern as {@code PromptsMatchTsTest}, stronger because it executes the
 * TS instead of scraping it).
 */
class AgentToolSchemasMatchTsTest {

	@Test
	@DisplayName("tool-schemas.json is byte-identical to electron/agent/toolSchemas.ts")
	void matchesTypeScriptConstants() throws Exception {
		Path source = typeScriptSource();
		Assumptions.assumeTrue(source != null,
				"electron/ is gone (Phase 3); nothing left to compare against");
		String typescript = evaluateAgentSchemas(source);
		byte[] expected = typescript.getBytes(StandardCharsets.UTF_8);
		byte[] actual = AgentToolSchemas.rawResource().getBytes(StandardCharsets.UTF_8);
		assertEquals(typescript, AgentToolSchemas.rawResource(),
				"agent/tool-schemas.json differs from the TS constants");
		assertTrue(expected.length == actual.length);
		JsonNode expectedTree = Json.parseJsonWithByteOrderMark(typescript);
		JsonNode actualTree = Json.parseJsonWithByteOrderMark(AgentToolSchemas.rawResource());
		assertEquals(expectedTree, actualTree);
	}

	/** Runs the TS module through Node's type stripping and prints the canonical JSON. */
	private static String evaluateAgentSchemas(Path source) throws Exception {
		String script = "import { AGENT_INSTRUCTIONS, AGENT_TOOLS } from "
				+ Json.MAPPER.writeValueAsString(source.toUri().toString())
				+ "; process.stdout.write(JSON.stringify({ instructions: AGENT_INSTRUCTIONS, tools: AGENT_TOOLS }));";
		ProcessResult result;
		try {
			result = ProcessRunner.run(List.of("node", "--experimental-strip-types",
					"--input-type=module", "-e", script), Duration.ofSeconds(30));
		} catch (IOException error) {
			Assumptions.abort("node is not available: " + error.getMessage());
			return null;
		}
		Assumptions.assumeTrue(result.succeeded(),
				"node could not evaluate toolSchemas.ts: " + result.stderr());
		return result.stdout();
	}

	private static Path typeScriptSource() {
		Path directory = Path.of("").toAbsolutePath();
		for (int depth = 0; depth < 6 && directory != null; depth++) {
			Path candidate = directory.resolve(Path.of("electron", "agent", "toolSchemas.ts"));
			if (Files.isRegularFile(candidate)) {
				return candidate.toAbsolutePath();
			}
			directory = directory.getParent();
		}
		return null;
	}
}
