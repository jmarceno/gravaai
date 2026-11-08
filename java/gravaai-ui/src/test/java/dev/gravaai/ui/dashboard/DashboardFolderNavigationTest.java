package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import dev.gravaai.core.storage.RecentProjectsStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.platform.trash.Trash;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.services.projects.RecoverableRecording;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A folder always lists the Projects library, whichever section the user came from. React kept the
 * previous library and a folder opened from Raw or Meetings filtered those entries by project paths,
 * so the grid came up empty.
 */
class DashboardFolderNavigationTest {

	private static final long NOW = 1_800_000_000_000L;

	private static final String ALPHA = "/projects/Alpha.gravaai";
	private static final String RAW = "/recordings/recording-1.mp4";
	private static final String MEETING = "/meetings/design-review.m4a";

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("shows the folder's projects after leaving the Raw section")
	void folderAfterRawShowsProjects() {
		DashboardModel model = model();
		ProjectFolder folder = fileProjectInFolder(model);

		model.setSection(DashboardSection.RAW);
		assertTrue(model.isRaw());

		model.setSection(DashboardSection.folder(folder.id()));

		assertFalse(model.isRaw());
		assertEquals(DashboardSection.PROJECTS, model.library());
		assertEquals(List.of("Alpha"), names(model.visibleEntries()));
	}

	@Test
	@DisplayName("shows the folder's projects after leaving the Meetings section")
	void folderAfterMeetingsShowsProjects() {
		DashboardModel model = model();
		ProjectFolder folder = fileProjectInFolder(model);

		model.setSection(DashboardSection.MEETINGS);
		assertTrue(model.isMeetings());

		model.setSection(DashboardSection.folder(folder.id()));

		assertFalse(model.isMeetings());
		assertEquals(DashboardSection.PROJECTS, model.library());
		assertEquals(List.of("Alpha"), names(model.visibleEntries()));
	}

	@Test
	@DisplayName("Home and the folders keep listing projects, and Raw keeps its own entries")
	void folderThenHomeAndRawStayConsistent() {
		DashboardModel model = model();
		ProjectFolder folder = fileProjectInFolder(model);

		model.setSection(DashboardSection.RAW);
		model.setSection(DashboardSection.folder(folder.id()));
		model.setSection(DashboardSection.PROJECTS);

		assertEquals(List.of("Alpha"), names(model.visibleEntries()));

		model.setSection(DashboardSection.RAW);

		assertTrue(model.isRaw());
		assertEquals(List.of("recording-1.mp4"), names(model.visibleEntries()));
	}

	private DashboardModel model() {
		UserData userData = new UserData(tempDir.resolve("Gravaai"));
		ProjectManager projects = new ProjectManager(userData, new RecordingsDirectory(userData),
				new RecentProjectsStore(userData.recentProjectsFile()), source -> new byte[0]);
		DashboardDependencies dependencies = new DashboardDependencies(userData, projects,
				includeSources -> List.of(), List::of, new DashboardLibraryStore(userData),
				new Trash(tempDir.resolve("data"), tempDir.resolve("home")), new FakeActions(), null, null,
				null, null, () -> null);
		DashboardModel model = new DashboardModel(dependencies);
		model.applySnapshot(new DashboardSnapshot(tempDir.resolve("projects"),
				List.of(project("Alpha")), List.of(rawRecording()),
				List.<RecoverableRecording>of(), DashboardLibraryData.empty(),
				List.of(meeting()), null));
		model.setNow(NOW);
		return model;
	}

	private static ProjectFolder fileProjectInFolder(DashboardModel model) {
		ProjectFolder folder = model.createFolder();
		model.assignFolder(ALPHA, folder.id());
		return folder;
	}

	private static ProjectLibraryEntry project(String name) {
		return new ProjectLibraryEntry("/projects/" + name + ".gravaai", name, NOW, NOW, null, false, true);
	}

	private static dev.gravaai.services.projects.RecordingEntry rawRecording() {
		return new dev.gravaai.services.projects.RecordingEntry(RAW, "recording-1.mp4", NOW, 1_024);
	}

	private static MeetingLibraryEntry meeting() {
		return new MeetingLibraryEntry(Path.of(MEETING), "Design review", NOW, 750_000L, false, true, false,
				MeetingLibraryEntry.Status.IDLE, null);
	}

	private static List<String> names(List<ProjectLibraryEntry> entries) {
		return entries.stream().map(ProjectLibraryEntry::name).toList();
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
