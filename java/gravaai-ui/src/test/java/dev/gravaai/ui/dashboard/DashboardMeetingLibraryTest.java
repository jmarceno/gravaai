package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.storage.RecentProjectsStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.platform.trash.Trash;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RecordingLibraryScan;
import dev.gravaai.services.projects.RecordingsDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of {@code useMeetingLibrary.ts} and the meeting branches of {@code useDashboardModel.ts}
 * (Meetings section of the dashboard): mapping to library entries, filtering/sorting, rename and
 * trash. Runs without a JavaFX toolkit.
 */
@Timeout(90)
class DashboardMeetingLibraryTest {

	private static final long NOW = DashboardMeetingFixtures.NOW;

	@TempDir
	Path tempDir;

	private UserData userData;
	private ProjectManager projects;
	private DashboardMeetingFixtures.FakeMeetingUiService meetings;
	private DashboardDependencies dependencies;
	private DashboardDataLoader loader;

	@BeforeEach
	void setUp() {
		userData = new UserData(tempDir.resolve("Gravaai"));
		RecordingsDirectory recordingsDirectory = new RecordingsDirectory(userData);
		projects = new ProjectManager(userData, recordingsDirectory,
				new RecentProjectsStore(userData.recentProjectsFile()), source -> null);
		meetings = new DashboardMeetingFixtures.FakeMeetingUiService();
		dependencies = new DashboardDependencies(
				userData,
				projects,
				new RecordingLibraryScan(recordingsDirectory),
				List::of,
				new DashboardLibraryStore(userData),
				new Trash(tempDir.resolve("data"), tempDir.resolve("home")),
				new NoopActions(),
				null,
				null,
				null,
				null,
				() -> meetings);
		loader = new DashboardDataLoader(dependencies);
	}

	@AfterEach
	void awaitThumbnailJobs() throws Exception {
		projects.awaitPendingThumbnails(20_000);
	}

	private DashboardModel modelWithMeetings() throws IOException {
		DashboardModel model = new DashboardModel(dependencies);
		model.setSection(DashboardSection.MEETINGS);
		model.applySnapshot(loader.load(false, true, null));
		model.setNow(NOW);
		return model;
	}

	@Test
	@DisplayName("maps the meetings library to dashboard entries with the media path as entry path")
	void mapsMeetings() throws Exception {
		DashboardModel model = modelWithMeetings();

		assertTrue(model.isMeetings());
		assertEquals(List.of("Design review", "Weekly sync", "Retro notes"), names(model.visibleEntries()));
		assertEquals(3, model.meetings().size());
		assertEquals(model.mappedMeetingEntries(), model.libraryEntries());

		ProjectLibraryEntry design = model.visibleEntries().get(0);
		long createdAt = NOW - 2 * DashboardMeetingFixtures.HOUR;
		assertEquals(DashboardMeetingFixtures.DESIGN_REVIEW.toString(), design.path());
		assertEquals("Design review", design.name());
		assertEquals(createdAt, design.createdAt());
		// useMeetingLibrary.toEntry: updatedAt = createdAt, no thumbnail, not current, no raw source.
		assertEquals(createdAt, design.updatedAt());
		assertNull(design.thumbnailPath());
		assertFalse(design.isCurrent());
		assertFalse(design.isInProjectsDirectory());
		assertNull(model.rawSource(design.path()));
		assertNotNull(model.meetingSource(design.path()));
	}

	@Test
	@DisplayName("reads meetings only when the Meetings section asks for them")
	void loadsMeetingsOnlyForTheSection() throws Exception {
		DashboardSnapshot projectsOnly = loader.load(false);
		assertTrue(projectsOnly.meetings().isEmpty());
		assertNull(projectsOnly.meetingsError());

		DashboardSnapshot withMeetings = loader.load(false, true, null);
		assertEquals(3, withMeetings.meetings().size());
	}

	@Test
	@DisplayName("applies the search, time window, folder and sort filters to meetings")
	void filtersAndSortsMeetings() throws Exception {
		DashboardModel model = modelWithMeetings();

		model.setQuery("  DESIGN ");
		assertEquals(List.of("Design review"), names(model.visibleEntries()));
		assertTrue(model.hasActiveFilters());

		model.setQuery("");
		model.setPeriod(DashboardLibraryFilters.Period.WEEK);
		// design-review is 2 h old and weekly-sync 26 h old; retro is 10 days old.
		assertEquals(List.of("Design review", "Weekly sync"), names(model.visibleEntries()));

		model.setPeriod(DashboardLibraryFilters.Period.ALL);
		model.setSort(DashboardLibraryFilters.Sort.NAME);
		assertEquals(List.of("Design review", "Retro notes", "Weekly sync"),
				names(model.visibleEntries()));

		model.setSort(DashboardLibraryFilters.Sort.CREATED);
		assertEquals(List.of("Design review", "Weekly sync", "Retro notes"),
				names(model.visibleEntries()));

		ProjectFolder folder = model.createFolder();
		model.assignFolder(DashboardMeetingFixtures.WEEKLY_SYNC.toString(), folder.id());
		model.setSection(DashboardSection.folder(folder.id()));
		assertEquals(List.of("Weekly sync"), names(model.visibleEntries()));
		assertTrue(model.hasActiveFilters());
	}

	@Test
	@DisplayName("renames a meeting through the meetings service and updates the card title")
	void renamesMeeting() throws Exception {
		DashboardModel model = modelWithMeetings();
		String path = DashboardMeetingFixtures.DESIGN_REVIEW.toString();

		String returned = model.renameProject(path, "  Board review  ");

		assertEquals(path, returned);
		assertEquals(List.of(DashboardMeetingFixtures.DESIGN_REVIEW + "=Board review"),
				meetings.renames);
		assertEquals("Board review", model.meetingSource(path).title());
		assertEquals(List.of("Board review", "Weekly sync", "Retro notes"),
				names(model.visibleEntries()));
	}

	@Test
	@DisplayName("moves a meeting to the trash, drops folder references and clears the selection")
	void deletesMeeting() throws Exception {
		DashboardModel model = modelWithMeetings();
		String path = DashboardMeetingFixtures.DESIGN_REVIEW.toString();
		ProjectFolder folder = model.createFolder();
		model.assignFolder(path, folder.id());
		model.setSelecting(true);
		model.toggleSelected(path);

		List<String> deleted = model.deleteLibraryEntries(List.of(path));

		assertEquals(List.of(path), deleted);
		assertEquals(List.of(List.of(DashboardMeetingFixtures.DESIGN_REVIEW)), meetings.deleteRequests);
		assertEquals(List.of("Weekly sync", "Retro notes"), names(model.visibleEntries()));
		assertTrue(model.folders().get(0).paths().isEmpty());
		assertTrue(model.selectedPaths().isEmpty());
		assertFalse(model.selectingProperty().get());
		assertNull(model.meetingSource(path));
	}

	@Test
	@DisplayName("keeps meetings that are still processing and reports the React error")
	void keepsProcessingMeetings() throws Exception {
		DashboardModel model = modelWithMeetings();
		String path = DashboardMeetingFixtures.RETRO.toString();
		meetings.undeletable.add(path);

		IOException error = assertThrows(IOException.class,
				() -> model.deleteLibraryEntries(List.of(path)));

		assertEquals(DashboardModel.MEETINGS_NOT_DELETED, error.getMessage());
		assertEquals(3, model.meetings().size());
		assertEquals(3, model.visibleEntries().size());
	}

	@Test
	@DisplayName("surfaces the meeting list error in the Meetings section")
	void surfacesListError() throws Exception {
		meetings.listError = "Could not read the meetings folder";
		DashboardModel model = new DashboardModel(dependencies);
		model.setSection(DashboardSection.MEETINGS);
		model.applySnapshot(loader.load(false, true, null));

		assertEquals("Could not read the meetings folder", model.meetingsError());
		assertTrue(model.meetings().isEmpty());
		assertTrue(model.visibleEntries().isEmpty());
	}

	@Test
	@DisplayName("reports the missing meetings service as an error instead of an empty list")
	void missingMeetingsService() throws Exception {
		DashboardDependencies unwired = new DashboardDependencies(
				userData, projects, new RecordingLibraryScan(new RecordingsDirectory(userData)),
				List::of, new DashboardLibraryStore(userData),
				new Trash(tempDir.resolve("data2"), tempDir.resolve("home2")), new NoopActions(),
				null, null, null);
		DashboardSnapshot snapshot = new DashboardDataLoader(unwired).load(false, true, null);

		assertEquals(DashboardDataLoader.MEETINGS_UNAVAILABLE, snapshot.meetingsError());
		assertTrue(snapshot.meetings().isEmpty());
	}

	@Test
	@DisplayName("the meetings section follows the single-source dashboard entry mapping")
	void keepsProjectAndMeetingPathsApart() throws Exception {
		Path video = Files.createDirectories(userData.recordingsDir()).resolve("recording-1.mp4");
		Files.writeString(video, "video");
		Path project = projects.getProjectsDir().resolve("Alpha.gravaai");
		Files.createDirectories(project.getParent());
		dev.gravaai.core.json.Json.writeFile(project, Map.of(
				"version", 2, "videoPath", video.toString(), "editor", Map.of()));

		DashboardModel model = new DashboardModel(dependencies);
		model.applySnapshot(loader.load(false, true, null));

		assertEquals(List.of("Alpha"), names(model.visibleEntries()));
		assertNull(model.meetingSource(project.toString()));
		model.setSection(DashboardSection.MEETINGS);
		assertEquals(3, model.visibleEntries().size());
		assertTrue(model.libraryEntries().stream()
				.noneMatch(entry -> entry.path().equals(project.toString())));
		assertEquals(List.of("Design review", "Weekly sync", "Retro notes"),
				names(model.visibleEntries()));
	}

	private static List<String> names(List<ProjectLibraryEntry> entries) {
		return entries.stream().map(ProjectLibraryEntry::name).toList();
	}

	/** The app shell implements these; the model tests do not navigate anywhere. */
	private static final class NoopActions implements DashboardDependencies.Actions {
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
		public void recoverRecording(dev.gravaai.services.projects.RecoverableRecording recording) {
		}

		@Override
		public void discardRecording(dev.gravaai.services.projects.RecoverableRecording recording) {
		}
	}
}
