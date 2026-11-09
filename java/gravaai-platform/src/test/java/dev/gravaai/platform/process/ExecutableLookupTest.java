package dev.gravaai.platform.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PATH resolution, which three call sites used to implement separately. */
class ExecutableLookupTest {

	@Test
	@DisplayName("finds an executable and skips directories that only hold data files")
	void findsExecutablesOnly(@TempDir Path tempDir) throws IOException {
		Path dataDir = Files.createDirectories(tempDir.resolve("data"));
		Files.createFile(dataDir.resolve("tool"));
		Path binDir = Files.createDirectories(tempDir.resolve("bin"));
		Path tool = Files.createFile(binDir.resolve("tool"));
		Files.setPosixFilePermissions(tool, java.nio.file.attribute.PosixFilePermissions
				.fromString("rwxr-xr-x"));

		Map<String, String> env = Map.of("PATH", dataDir + ":" + binDir);

		assertEquals(tool.toRealPath(),
				ExecutableLookup.find(env, "tool").orElseThrow().toRealPath());
	}

	@Test
	@DisplayName("returns empty for a missing command instead of guessing a path")
	void missingCommandIsEmpty(@TempDir Path tempDir) {
		Map<String, String> env = Map.of("PATH", tempDir.toString());

		assertTrue(ExecutableLookup.find(env, "definitely-not-installed").isEmpty());
	}

	@Test
	@DisplayName("an unset PATH means nothing resolves")
	void unsetPathResolvesNothing() {
		assertTrue(ExecutableLookup.find(Map.of(), "sh").isEmpty());
		assertTrue(ExecutableLookup.find(Map.of("PATH", ""), "sh").isEmpty());
	}

	@Test
	@DisplayName("empty PATH entries are skipped, not treated as the working directory")
	void emptyPathEntriesAreSkipped(@TempDir Path tempDir) throws IOException {
		Path binDir = Files.createDirectories(tempDir.resolve("bin"));
		Path tool = Files.createFile(binDir.resolve("tool"));
		Files.setPosixFilePermissions(tool, java.nio.file.attribute.PosixFilePermissions
				.fromString("rwxr-xr-x"));

		assertEquals(tool.toRealPath(),
				ExecutableLookup.find(Map.of("PATH", "::" + binDir + ":"), "tool")
						.orElseThrow().toRealPath());
	}

	@Test
	@DisplayName("require fails with the message the caller wants")
	void requireUsesTheCallersMessage() {
		IOException error = assertThrows(IOException.class,
				() -> ExecutableLookup.require(Map.of(), "pactl", "pactl is not installed"));

		assertEquals("pactl is not installed", error.getMessage());
	}

	@Test
	@DisplayName("findAny takes the first name that resolves")
	void findAnyTakesTheFirstMatch(@TempDir Path tempDir) throws IOException {
		Path binDir = Files.createDirectories(tempDir.resolve("bin"));
		Path second = Files.createFile(binDir.resolve("whisper-cpp"));
		Files.setPosixFilePermissions(second, java.nio.file.attribute.PosixFilePermissions
				.fromString("rwxr-xr-x"));
		Map<String, String> env = Map.of("PATH", binDir.toString());

		assertEquals(second.toRealPath(),
				ExecutableLookup.findAny(env, List.of("whisper-cli", "whisper-cpp"))
						.orElseThrow().toRealPath());
	}
}
