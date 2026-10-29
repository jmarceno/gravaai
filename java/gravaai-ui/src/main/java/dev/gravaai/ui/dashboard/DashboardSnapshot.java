package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.RecoverableRecording;
import dev.gravaai.services.projects.RecordingEntry;

/**
 * One blocking read of everything the dashboard shows, loaded off the UI thread.
 *
 * @param projectsDir the Projects folder path (Settings -> Projects folder)
 * @param projects the project library entries, newest first
 * @param rawRecordings the recordings library entries (empty unless the raw section is visible)
 * @param recoverableRecordings interrupted recordings offered by the banner
 * @param preferences the dashboard preference file contents
 * @param meetings the meetings library entries, newest first (empty unless the meetings section is
 *     visible)
 * @param meetingsError the {@code meeting-list} IPC error, or {@code null}; port of the
 *     {@code error} state in {@code useMeetingLibrary.ts}
 */
public record DashboardSnapshot(
		Path projectsDir,
		List<ProjectLibraryEntry> projects,
		List<RecordingEntry> rawRecordings,
		List<RecoverableRecording> recoverableRecordings,
		DashboardLibraryData preferences,
		List<MeetingLibraryEntry> meetings,
		@Nullable String meetingsError) {

	public DashboardSnapshot {
		projects = List.copyOf(projects);
		rawRecordings = List.copyOf(rawRecordings);
		recoverableRecordings = List.copyOf(recoverableRecordings);
		meetings = List.copyOf(meetings);
	}

	/** Snapshot without a meetings read (Projects, Raw and Settings loads). */
	public DashboardSnapshot(Path projectsDir, List<ProjectLibraryEntry> projects,
			List<RecordingEntry> rawRecordings,
			List<RecoverableRecording> recoverableRecordings,
			DashboardLibraryData preferences) {
		this(projectsDir, projects, rawRecordings, recoverableRecordings, preferences, List.of(), null);
	}
}
