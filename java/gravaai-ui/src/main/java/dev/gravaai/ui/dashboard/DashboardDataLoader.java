package dev.gravaai.ui.dashboard;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.ProjectThumbnailReady;
import dev.gravaai.services.projects.RecoverableRecordingsProvider;
import dev.gravaai.services.projects.RecordingEntry;
import dev.gravaai.services.projects.RecordingsReadModel;
import dev.gravaai.ui.meeting.MeetingUiService;

/**
 * Loads {@link DashboardSnapshot}s off the UI thread (blocking file IO, plan §4.1).
 *
 * <p>The dashboard view runs {@link #load(boolean, boolean, Consumer)} on a virtual thread and
 * applies the snapshot on the FX thread; tests call it directly. The meetings read is the
 * {@code useMeetingLibrary} hook's {@code meetingList()} call, resolved through the lazy
 * {@link DashboardDependencies#meetings()} supplier so a service that comes up after the dashboard
 * is still found.
 */
public final class DashboardDataLoader {

	/** Port of the React failure the hook surfaces when the meeting list cannot be read. */
	static final String MEETINGS_UNAVAILABLE = "The meetings service is not available.";

	private final ProjectManager projects;
	private final RecordingsReadModel recordings;
	private final RecoverableRecordingsProvider recoverableRecordings;
	private final DashboardLibraryStore preferences;
	private final Supplier<MeetingUiService> meetings;

	public DashboardDataLoader(DashboardDependencies dependencies) {
		this(dependencies.projects(), dependencies.recordings(), dependencies.recoverableRecordings(),
				dependencies.preferences(), dependencies.meetings());
	}

	public DashboardDataLoader(ProjectManager projects, RecordingsReadModel recordings,
			RecoverableRecordingsProvider recoverableRecordings, DashboardLibraryStore preferences) {
		this(projects, recordings, recoverableRecordings, preferences, () -> null);
	}

	public DashboardDataLoader(ProjectManager projects, RecordingsReadModel recordings,
			RecoverableRecordingsProvider recoverableRecordings, DashboardLibraryStore preferences,
			Supplier<MeetingUiService> meetings) {
		this.projects = projects;
		this.recordings = recordings;
		this.recoverableRecordings = recoverableRecordings;
		this.preferences = preferences;
		this.meetings = meetings;
	}

	public DashboardSnapshot load(boolean includeRaw) throws IOException {
		return load(includeRaw, false, null);
	}

	public DashboardSnapshot load(boolean includeRaw, Consumer<ProjectThumbnailReady> onThumbnailReady)
			throws IOException {
		return load(includeRaw, false, onThumbnailReady);
	}

	/**
	 * @param includeRaw {@code true} when the raw recordings section is selected
	 * @param includeMeetings {@code true} when the meetings section is selected
	 * @param onThumbnailReady called from the background thumbnail jobs; the view hops to the FX
	 *     thread itself
	 */
	public DashboardSnapshot load(boolean includeRaw, boolean includeMeetings,
			Consumer<ProjectThumbnailReady> onThumbnailReady) throws IOException {
		DashboardLibraryData libraryPreferences = preferences.load();
		ProjectManager.ProjectLibrary library = projects.listProjectLibraryEntries(onThumbnailReady);
		List<RecordingEntry> raw = includeRaw ? recordings.list(true) : List.of();
		List<MeetingLibraryEntry> meetingEntries = List.of();
		String meetingsError = null;
		if (includeMeetings) {
			MeetingUiService service = meetings.get();
			if (service == null) {
				meetingsError = MEETINGS_UNAVAILABLE;
			} else {
				Result<List<MeetingLibraryEntry>> result = service.listMeetings();
				if (result.isErr()) {
					meetingsError = result.errorMessage();
				} else {
					meetingEntries = result.orElse(List.of());
				}
			}
		}
		return new DashboardSnapshot(library.projectsDir(), library.entries(), raw,
				recoverableRecordings.list(), libraryPreferences, meetingEntries, meetingsError);
	}
}
