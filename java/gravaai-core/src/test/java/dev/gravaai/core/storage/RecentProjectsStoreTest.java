package dev.gravaai.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests for {@link RecentProjectsStore}, ported from the recent-project behaviour in
 * {@code electron/ipc/project/manager.ts} (plan §5.2).
 */
class RecentProjectsStoreTest {

	@TempDir
	Path directory;

	private Path file;
	private RecentProjectsStore store;

	@BeforeEach
	void setUp() {
		file = directory.resolve("recent-projects.json");
		store = new RecentProjectsStore(file);
	}

	@Test
	@DisplayName("reads a missing history as empty")
	void readsMissingHistoryAsEmpty() {
		assertTrue(store.loadRecentProjectPaths().isEmpty());
	}

	@Test
	@DisplayName("reads an invalid or malformed history as empty")
	void readsInvalidHistoryAsEmpty() throws IOException {
		for (String contents : List.of(
				"",
				"{ nope",
				"[1,2]",
				"null",
				"\"text\"",
				"{\"paths\":\"nope\"}",
				"{\"other\":[]}")) {
			Files.writeString(file, contents, StandardCharsets.UTF_8);
			assertTrue(store.loadRecentProjectPaths().isEmpty(), contents);
		}
	}

	@Test
	@DisplayName("keeps only non-blank string paths")
	void keepsOnlyNonBlankStringPaths() throws IOException {
		Files.writeString(
				file,
				"{\"paths\":[\"/a.gravaai\",\"\",\"  \",5,true,null,{\"x\":1},\"/b.gravaai\"]}",
				StandardCharsets.UTF_8);

		assertEquals(List.of("/a.gravaai", "/b.gravaai"), store.loadRecentProjectPaths());
	}

	@Test
	@DisplayName("saves normalized, deduplicated paths capped at the maximum")
	void savesNormalizedPaths() throws IOException {
		List<String> paths = new ArrayList<>();
		paths.add("/tmp/a.gravaai");
		paths.add("/tmp/../tmp/a.gravaai");
		for (int index = 0; index < 20; index++) {
			paths.add("/tmp/p" + index + ".gravaai");
		}

		store.saveRecentProjectPaths(paths);

		List<String> expected = new ArrayList<>();
		expected.add("/tmp/a.gravaai");
		for (int index = 0; index < 15; index++) {
			expected.add("/tmp/p" + index + ".gravaai");
		}
		assertEquals(expected, store.loadRecentProjectPaths());
		assertEquals(StorageFiles.MAX_RECENT_PROJECTS, store.loadRecentProjectPaths().size());

		ArrayNode array = Json.nodeFactory().arrayNode();
		expected.forEach(array::add);
		ObjectNode document = Json.nodeFactory().objectNode();
		document.set("paths", array);
		assertEquals(Json.stringify(document), Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("resolves relative paths before storing them")
	void resolvesRelativePaths() throws IOException {
		store.saveRecentProjectPaths(List.of("projects/demo.gravaai"));

		assertEquals(
				List.of(Path.of("projects/demo.gravaai").toAbsolutePath().normalize().toString()),
				store.loadRecentProjectPaths());
	}

	@Test
	@DisplayName("remembers a project path at the front and ignores non-project files")
	void remembersProjectPath() throws IOException {
		store.saveRecentProjectPaths(List.of("/tmp/old.gravaai"));
		String firstSave = Files.readString(file, StandardCharsets.UTF_8);

		store.rememberRecentProject("/tmp/new.gravaai");
		assertEquals(List.of("/tmp/new.gravaai", "/tmp/old.gravaai"), store.loadRecentProjectPaths());

		store.rememberRecentProject("/tmp/notes.txt");
		assertEquals(List.of("/tmp/new.gravaai", "/tmp/old.gravaai"), store.loadRecentProjectPaths());
		assertTrue(!firstSave.isEmpty());
	}

	@Test
	@DisplayName("round-trips the Electron history fixture byte for byte")
	void roundTripsElectronFixture() throws IOException {
		String fixture;
		try (InputStream input = getClass().getResourceAsStream("/fixtures/json/recent-projects.json")) {
			assertNotNull(input, "missing fixture");
			fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		Files.writeString(file, fixture, StandardCharsets.UTF_8);

		List<String> paths = store.loadRecentProjectPaths();
		assertEquals(2, paths.size());
		store.saveRecentProjectPaths(paths);

		assertEquals(fixture, Files.readString(file, StandardCharsets.UTF_8));
	}
}
