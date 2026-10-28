package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of the localStorage behaviour in {@code useProjectFolders}, {@code useRawLibrary} and
 * {@code useDashboardMetadata}, stored in {@code dashboard-library.json} (Lead decision, task-9).
 */
class DashboardLibraryStoreTest {

	@TempDir
	Path tempDir;

	private Path file() {
		return tempDir.resolve("dashboard-library.json");
	}

	@Test
	@DisplayName("reads missing and malformed files as empty preferences")
	void readsMissingAndMalformedFilesAsEmpty() throws Exception {
		assertEquals(DashboardLibraryData.empty(), new DashboardLibraryStore(file()).load());

		Files.writeString(file(), "not json", StandardCharsets.UTF_8);
		assertEquals(DashboardLibraryData.empty(), new DashboardLibraryStore(file()).load());

		Files.writeString(file(), "[1, 2, 3]", StandardCharsets.UTF_8);
		assertEquals(DashboardLibraryData.empty(), new DashboardLibraryStore(file()).load());
	}

	@Test
	@DisplayName("reads valid folders, hidden raw files, raw names and custom colours")
	void readsValidPreferences() throws Exception {
		Files.writeString(file(), """
				{
				  "gravaai.project-folders.v1": [
				    {"id":"f1","name":"Work","color":"#DE85AC","paths":["/p/a.gravaai"]},
				    {"id":"bad-color","name":"Bad","color":"red","paths":[]},
				    {"id":"bad-paths","name":"Bad","color":"#70ad8a","paths":["/a", 3]}
				  ],
				  "gravaai.raw-hidden.v1": ["/raw/a.mkv", 4],
				  "gravaai.raw-names.v1": {"/raw/a.mkv": "Test", "/raw/b.mkv": 7},
				  "gravaai.dashboard-metadata.v1": {"colors": ["#a68ccc", "not-a-color", "#123456", "#a68ccc"]}
				}""", StandardCharsets.UTF_8);

		DashboardLibraryData data = new DashboardLibraryStore(file()).load();

		assertEquals(List.of(new ProjectFolder("f1", "Work", "#DE85AC", List.of("/p/a.gravaai"))),
				data.folders());
		assertEquals(List.of("/raw/a.mkv"), data.hiddenRawPaths());
		assertEquals(Map.of("/raw/a.mkv", "Test"), data.rawNames());
		assertEquals(List.of("#a68ccc", "#123456"), data.colors());
	}

	@Test
	@DisplayName("writes two-space JSON atomically, keeps unknown keys and tolerates a byte order mark")
	void writesAtomicallyAndKeepsUnknownKeys() throws Exception {
		DashboardLibraryStore store = new DashboardLibraryStore(file());
		store.load();
		DashboardLibraryData data = new DashboardLibraryData(
				List.of(new ProjectFolder("f1", "Work", "#de85ac", List.of("/p/a.gravaai"))),
				List.of("/raw/a.mkv"),
				Map.of("/raw/a.mkv", "Test"),
				List.of("#a68ccc"));

		store.save(data);

		String written = Files.readString(file(), StandardCharsets.UTF_8);
		assertTrue(written.startsWith("{\n  \"gravaai.project-folders.v1\": ["), written);
		assertFalse(written.endsWith("\n"));
		assertEquals(data, new DashboardLibraryStore(file()).load());

		// Unknown top-level keys must survive a round trip (plan §5.2).
		Files.writeString(file(),
				"\uFEFF" + written.replaceFirst("\\{", "{\n  \"gravaai.future-key.v1\": {\"x\": 1},"),
				StandardCharsets.UTF_8);
		DashboardLibraryStore reread = new DashboardLibraryStore(file());
		DashboardLibraryData loaded = reread.load();
		reread.save(loaded);

		String rewritten = Files.readString(file(), StandardCharsets.UTF_8);
		assertTrue(rewritten.contains("\"gravaai.future-key.v1\""), rewritten);
		assertEquals(data, new DashboardLibraryStore(file()).load());
	}

	@Test
	@DisplayName("moves project folder references when a project is renamed")
	void movesFolderReferences() {
		DashboardLibraryData data = new DashboardLibraryData(
				List.of(new ProjectFolder("f1", "Work", "#de85ac",
						List.of("/p/a.gravaai", "/p/b.gravaai"))),
				List.of("/raw/a.mkv"),
				Map.of("/raw/a.mkv", "Test"),
				List.of("#a68ccc"));

		DashboardLibraryData moved = DashboardLibraryStore.moveProjectFolderReferences(
				data, "/p/a.gravaai", "/p/Renamed.gravaai");

		assertEquals(List.of("/p/Renamed.gravaai", "/p/b.gravaai"), moved.folders().get(0).paths());
		assertEquals(data.hiddenRawPaths(), moved.hiddenRawPaths());
		assertEquals(data.rawNames(), moved.rawNames());
		assertEquals(data.colors(), moved.colors());
	}
}
