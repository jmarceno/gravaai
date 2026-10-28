package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Port of {@code electron/agent/token.ts} semantics (plan §5.16). */
class AgentTokenTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("generates 32 random bytes as lowercase hex")
	void generatesHexToken() {
		String first = AgentToken.generate();
		String second = AgentToken.generate();
		assertEquals(AgentToken.AGENT_TOKEN_BYTES * 2, first.length());
		assertTrue(first.matches("[0-9a-f]+"));
		assertNotEquals(first, second);
	}

	@Test
	@DisplayName("creates the token file with mode 0600 on first use")
	void createsPrivateTokenFile() throws Exception {
		Path tokenFile = tempDir.resolve("agent-token");
		String token = AgentToken.readOrCreate(tokenFile);
		assertEquals(token, Files.readString(tokenFile, StandardCharsets.UTF_8));
		Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tokenFile);
		assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
				permissions);
	}

	@Test
	@DisplayName("reads the existing token instead of creating a new one")
	void readsExistingToken() throws Exception {
		Path tokenFile = tempDir.resolve("agent-token");
		AgentToken.write(tokenFile, "a".repeat(64));
		assertEquals("a".repeat(64), AgentToken.readOrCreate(tokenFile));
	}

	@Test
	@DisplayName("comparison is length-checked and constant-time")
	void comparesTokens() {
		String token = "b".repeat(64);
		assertTrue(AgentToken.tokensMatch(token, token));
		assertFalse(AgentToken.tokensMatch(token, "b".repeat(63)));
		assertFalse(AgentToken.tokensMatch(token, "c".repeat(64)));
		assertFalse(AgentToken.tokensMatch(token, null));
		assertFalse(AgentToken.tokensMatch(token, 42));
	}

	@Test
	@DisplayName("a missing token file reads as empty")
	void missingTokenReadsEmpty() {
		assertTrue(AgentToken.read(tempDir.resolve("missing-token")).isEmpty());
		assertTrue(AgentToken.read(tempDir.resolve("blank-token")).isEmpty());
	}
}
