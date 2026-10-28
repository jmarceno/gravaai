package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.RecentProjectsStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.platform.trash.Trash;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RecoverableRecording;
import dev.gravaai.services.projects.RecordingLibraryScan;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.services.projects.TrashLibraryProjects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of {@code useDashboardModel} and its folder/raw/metadata hooks against a temporary profile.
 * Runs without a JavaFX toolkit: the model only exposes properties and lists.
 */
@Timeout(90)
class DashboardModelTest {

	private static final long NOW = 1_800_000_000_000L;
	private static final long DAY = 86_400_000L;

	@TempDir
	Path tempDir;

	private UserData userData;
	private ProjectManager projects;
	private DashboardDependencies dependencies;
	private DashboardDataLoader loader;

	@BeforeEach
	void setUp() {
		userData = new UserData(tempDir.resolve("Gravaai"));
		RecordingsDirectory recordingsDirectory = new RecordingsDirectory(userData);
		projects = new ProjectManager(userData, recordingsDirectory,
				new RecentProjectsStore(userData.recentProjectsFile()),
				source -> pngHeader(640, 480));
		dependencies = new DashboardDependencies(
				userData,
				projects,
				new RecordingLibraryScan(recordingsDirectory),
				List::of,
				new DashboardLibraryStore(userData),
				new Trash(tempDir.resolve("data"), tempDir.resolve("home")),
				new FakeActions(),
				null,
				null,
				null);
		loader = new DashboardDataLoader(dependencies);
	}

	@AfterEach
	void awaitThumbnailJobs() throws Exception {
		projects.awaitPendingThumbnails(20_000);
	}

	@Test
	@DisplayName("maps raw recordings with hidden files removed and stored names preferred")
	void mapsRawLibrary() throws Exception {
		Path recordingsDir = Files.createDirectories(userData.recordingsDir());
		Path first = write(recordingsDir.resolve("recording-1.mp4"));
		Path second = write(recordingsDir.resolve("music.wav"));
		long secondMtime = Files.getLastModifiedTime(second).toMillis();
		new DashboardLibraryStore(userData).save(new DashboardLibraryData(List.of(),
				List.of(first.toString()), Map.of(second.toString(), "My Music"), List.of("#a68ccc")));

		DashboardModel model = new DashboardModel(dependencies);
		model.setSection(DashboardSection.RAW);
		model.applySnapshot(loader.load(true));

		assertEquals(List.of(new ProjectLibraryEntry(second.toString(), "My Music", secondMtime,
				secondMtime, null, false, false)), model.visibleEntries());
		assertNotNull(model.rawSource(second.toString()));
	}

	@Test
	@DisplayName("lists projects, applies the time window and keeps the chosen sort")
	void listsProjectsAndFilters() throws Exception {
		Path video = write(Files.createDirectories(userData.recordingsDir()).resolve("recording-1.mp4"));
		Path alpha = createProject("Alpha", NOW - 2 * DAY, video);
		Path beta = createProject("Beta", NOW - 20 * DAY, video);

		DashboardModel model = new DashboardModel(dependencies);
		model.applySnapshot(loader.load(false));
		model.setNow(NOW);

		assertEquals(List.of("Alpha", "Beta"), names(model.visibleEntries()));
		model.setPeriod(DashboardLibraryFilters.Period.WEEK);
		assertEquals(List.of("Alpha"), names(model.visibleEntries()));
		model.setPeriod(DashboardLibraryFilters.Period.ALL);
		model.setSort(DashboardLibraryFilters.Sort.NAME);
		assertEquals(List.of("Alpha", "Beta"), names(model.visibleEntries()));
		model.setQuery("bet");
		assertEquals(List.of("Beta"), names(model.visibleEntries()));
		assertTrue(model.hasActiveFilters());
	}

	@Test
	@DisplayName("clears the search and selection when the sidebar section changes")
	void clearsTransientStateOnSectionChange() throws Exception {
		Path video = write(Files.createDirectories(userData.recordingsDir()).resolve("recording-1.mp4"));
		createProject("Alpha", NOW, video);
		DashboardModel model = new DashboardModel(dependencies);
		model.applySnapshot(loader.load(false));
		model.setQuery("alp");
		model.setSelecting(true);
		model.toggleSelected(projects.getProjectsDir().resolve("Alpha.gravaai").toString());

		model.setSection(DashboardSection.RAW);

		assertEquals("", model.query());
		assertFalse(model.selectingProperty().get());
		assertTrue(model.selectedPaths().isEmpty());
	}

	@Test
	@DisplayName("creates, renames, removes and assigns folders, persisting them")
	void persistsFolders() throws Exception {
		Path video = write(Files.createDirectories(userData.recordingsDir()).resolve("recording-1.mp4"));
		Path project = createProject("Alpha", NOW, video);
		DashboardModel model = new DashboardModel(dependencies);
		model.applySnapshot(loader.load(false));

		ProjectFolder folder = model.createFolder();
		ProjectFolder second = model.createFolder();
		assertEquals("Untitled folder", folder.name());
		assertEquals("Untitled folder 1", second.name());
		assertEquals(2, new DashboardLibraryStore(userData).load().folders().size());

		model.renameFolder(folder.id(), "  Work  ");
		assertEquals("Work", model.folders().get(0).name());
		assertEquals("Work", new DashboardLibraryStore(userData).load().folders().get(0).name());

		model.assignFolder(project.toString(), folder.id());
		assertTrue(model.folders().get(0).paths().contains(project.toString()));
		assertTrue(new DashboardLibraryStore(userData).load().folders().get(0).paths()
				.contains(project.toString()));

		model.setSection(DashboardSection.folder(folder.id()));
		assertEquals(List.of("Alpha"), names(model.visibleEntries()));

		model.assignFolder(project.toString(), "none");
		assertFalse(model.folders().get(0).paths().contains(project.toString()));
		model.removeFolder(second.id());
		assertEquals(1, model.folders().size());
	}

	@Test
	@DisplayName("renames a project with its preview and moves folder references")
	void renamesProject() throws Exception {
		Path video = write(Files.createDirectories(userData.recordingsDir()).resolve("recording-1.mp4"));
		Path project = createProject("Alpha", NOW, video);
		Path preview = ProjectManager.getProjectThumbnailPath(project);
		Files.write(preview, pngHeader(640, 480));
		DashboardModel model = new DashboardModel(dependencies);
		model.applySnapshot(loader.load(false));
		ProjectFolder folder = model.createFolder();
		model.assignFolder(project.toString(), folder.id());

		String target = model.renameProject(project.toString(), "Renamed");

		Path renamed = projects.getProjectsDir().resolve("Renamed.gravaai");
		assertEquals(renamed.toAbsolutePath().normalize().toString(), target);
		assertFalse(Files.exists(project));
		assertTrue(Files.exists(renamed));
		assertTrue(Files.exists(Path.of(target + ".preview.png")));
		assertTrue(model.folders().get(0).paths().contains(target));
		assertTrue(new RecentProjectsStore(userData.recentProjectsFile()).loadRecentProjectPaths()
				.contains(target));
	}

	@Test
	@DisplayName("trashes a project but keeps its recording unless opted in")
	void trashesProjectAndKeepsRecording() throws Exception {
		Path video = write(Files.createDirectories(userData.recordingsDir()).resolve("recording-1.mp4"));
		Path project = createProject("Alpha", NOW, video);
		DashboardModel model = new DashboardModel(dependencies);
		model.applySnapshot(loader.load(false));
		ProjectFolder folder = model.createFolder();
		model.assignFolder(project.toString(), folder.id());

		TrashLibraryProjects.TrashResult result = model.trashProjects(List.of(project.toString()), false);

		assertEquals(List.of(project.toAbsolutePath().normalize().toString()), result.deleted());
		assertFalse(Files.exists(project));
		assertTrue(Files.exists(video));
		assertTrue(model.projectEntries().isEmpty());
		assertTrue(model.folders().get(0).paths().isEmpty());
		assertFalse(new RecentProjectsStore(userData.recentProjectsFile()).loadRecentProjectPaths()
				.contains(project.toString()));
	}

	@Test
	@DisplayName("trashes referenced recordings when the delete dialog opted in")
	void trashesRecordingsWhenOptedIn() throws Exception {
		Path video = write(Files.createDirectories(userData.recordingsDir()).resolve("recording-1.mp4"));
		Path project = createProject("Alpha", NOW, video);
		DashboardModel model = new DashboardModel(dependencies);
		model.applySnapshot(loader.load(false));

		TrashLibraryProjects.TrashResult result = model.trashProjects(List.of(project.toString()), true);

		assertEquals(List.of(), result.errors());
		assertFalse(Files.exists(video));
	}

	@Test
	@DisplayName("renames and hides raw-library files through the preference file")
	void renamesAndHidesRawFiles() throws Exception {
		Path raw = write(Files.createDirectories(userData.recordingsDir()).resolve("recording-2.mp4"));
		DashboardModel model = new DashboardModel(dependencies);
		model.setSection(DashboardSection.RAW);
		model.applySnapshot(loader.load(true));

		model.renameProject(raw.toString(), "Demo");

		assertEquals("Demo", model.mappedRawEntries().get(0).name());
		assertEquals("Demo", new DashboardLibraryStore(userData).load().rawNames().get(raw.toString()));

		model.hideRawRecordings(List.of(raw.toString()));

		assertTrue(model.mappedRawEntries().isEmpty());
		assertTrue(new DashboardLibraryStore(userData).load().hiddenRawPaths().contains(raw.toString()));
	}

	@Test
	@DisplayName("selects and clears visible entries for the trash mode")
	void selectsEntries() throws Exception {
		Path video = write(Files.createDirectories(userData.recordingsDir()).resolve("recording-1.mp4"));
		createProject("Alpha", NOW, video);
		createProject("Beta", NOW - DAY, video);
		DashboardModel model = new DashboardModel(dependencies);
		model.applySnapshot(loader.load(false));

		model.setSelecting(true);
		model.selectAllVisible();
		assertEquals(2, model.selectedPaths().size());
		model.toggleSelected(model.visibleEntries().get(0).path());
		assertEquals(1, model.selectedPaths().size());
		model.setSelecting(false);
		assertTrue(model.selectedPaths().isEmpty());
	}

	private DashboardModel modelWithSnapshotFilled() throws IOException {
		DashboardModel model = new DashboardModel(dependencies);
		model.applySnapshot(loader.load(false));
		return model;
	}

	private Path createProject(String name, long updatedAt, Path videoPath) throws IOException {
		Path project = projects.getProjectsDir().resolve(name + ".gravaai");
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("version", 2);
		data.put("videoPath", videoPath.toString());
		data.put("editor", new LinkedHashMap<>());
		Json.writeFile(project, data);
		Files.setLastModifiedTime(project, FileTime.fromMillis(updatedAt));
		return project;
	}

	private static Path write(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, "data");
		return file;
	}

	private static List<String> names(List<ProjectLibraryEntry> entries) {
		return entries.stream().map(ProjectLibraryEntry::name).toList();
	}

	private static byte[] pngHeader(int width, int height) {
		byte[] header = new byte[24];
		byte[] signature = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };
		System.arraycopy(signature, 0, header, 0, signature.length);
		header[16] = (byte) (width >>> 24);
		header[17] = (byte) (width >>> 16);
		header[18] = (byte) (width >>> 8);
		header[19] = (byte) width;
		header[20] = (byte) (height >>> 24);
		header[21] = (byte) (height >>> 16);
		header[22] = (byte) (height >>> 8);
		header[23] = (byte) height;
		return header;
	}

	/** The app shell implements these; the model tests do not navigate anywhere. */
	private static final class FakeActions implements DashboardDependencies.Actions {
		@Override
		public void openProject(String path) {
		}

		@Override
		public void openRecording(String path) {
		}

		@Override
		public void importFile() {
		}

		@Override
		public void revealInFolder(String path) {
		}

		@Override
		public void showRecordingHud() {
		}

		@Override
		public void openExternalUrl(String url) {
		}

		@Override
		public void recoverRecording(RecoverableRecording recording) {
		}

		@Override
		public void discardRecording(RecoverableRecording recording) {
		}
	}
}