package dev.gravaai.ui.dashboard;

import java.util.Objects;
import java.util.function.Supplier;

import dev.gravaai.core.storage.UserData;
import dev.gravaai.platform.trash.Trash;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RecoverableRecording;
import dev.gravaai.services.projects.RecoverableRecordingsProvider;
import dev.gravaai.services.projects.RecordingsReadModel;
import dev.gravaai.ui.meeting.MeetingUiService;

/**
 * Everything a {@link DashboardView} needs, built by {@code gravaai-app.AppContext} (plan §5.1:
 * constructor injection, no DI framework).
 *
 * <p>Navigation and OS actions are callbacks, so the dashboard never depends on the app module. The
 * read side is injected as the service read models; the recording service replaces
 * {@link RecordingsReadModel} and {@link RecoverableRecordingsProvider} with its own
 * implementations when it publishes them.
 *
 * @param userData the active profile (paths for the screenshot harness and diagnostics)
 * @param projects the project library read model / rename / trash helpers
 * @param recordings the recordings library read model (raw section, Appendix A list-recordings)
 * @param recoverableRecordings the interrupted-recording banner read port
 * @param preferences the dashboard {@code dashboard-library.json} store
 * @param trash the freedesktop trash used by project delete
 * @param actions navigation and OS callbacks implemented by the app shell
 * @param globalShortcuts the global-shortcuts controller for Settings -> Shortcuts, or
 *     {@code null} until AppContext wires {@link ServiceGlobalShortcutsController}
 * @param agent the AI-control settings controller, or {@code null} until the Phase 3 agent
 *     service exists
 * @param automations the automations controller, or {@code null} when the page is not wired
 * @param agentUiService the P3.2 AI-control page port, or {@code null} until AppContext wires
 *     {@code services.agent}; the dashboard then falls back to
 *     {@code PlaceholderAgentUiService.disabled()}
 * @param meetings lazy meetings UI service ({@code services.meeting.MeetingService} through
 *     {@link MeetingUiService}), or a supplier returning {@code null} when the meetings service is
 *     not up. It is lazy because AppContext creates the meetings service after the dashboard in
 *     the HUD startup path; the dashboard resolves it per load, like the React IPC calls.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public record DashboardDependencies(
		UserData userData,
		ProjectManager projects,
		RecordingsReadModel recordings,
		RecoverableRecordingsProvider recoverableRecordings,
		DashboardLibraryStore preferences,
		Trash trash,
		Actions actions,
		GlobalShortcutsController globalShortcuts,
		AgentSettingsController agent,
		AutomationsController automations,
		dev.gravaai.ui.agent.AgentUiService agentUiService,
		Supplier<MeetingUiService> meetings) {

	public DashboardDependencies {
		Objects.requireNonNull(userData, "userData");
		Objects.requireNonNull(projects, "projects");
		Objects.requireNonNull(recordings, "recordings");
		Objects.requireNonNull(recoverableRecordings, "recoverableRecordings");
		Objects.requireNonNull(preferences, "preferences");
		Objects.requireNonNull(trash, "trash");
		Objects.requireNonNull(actions, "actions");
		meetings = meetings == null ? () -> null : meetings;
	}

	/**
	 * Backwards-compatible constructor for callers that do not pass the P3.2 agent page port yet
	 * (AppContext keeps working; the AI-control page renders the disabled placeholder).
	 */
	public DashboardDependencies(UserData userData, ProjectManager projects,
			RecordingsReadModel recordings, RecoverableRecordingsProvider recoverableRecordings,
			DashboardLibraryStore preferences, Trash trash, Actions actions,
			GlobalShortcutsController globalShortcuts, AgentSettingsController agent,
			AutomationsController automations) {
		this(userData, projects, recordings, recoverableRecordings, preferences, trash, actions,
				globalShortcuts, agent, automations, null);
	}

	/**
	 * Backwards-compatible constructor for callers that do not wire the meetings service yet: the
	 * Meetings section renders its empty state instead of a grid.
	 */
	public DashboardDependencies(UserData userData, ProjectManager projects,
			RecordingsReadModel recordings, RecoverableRecordingsProvider recoverableRecordings,
			DashboardLibraryStore preferences, Trash trash, Actions actions,
			GlobalShortcutsController globalShortcuts, AgentSettingsController agent,
			AutomationsController automations, dev.gravaai.ui.agent.AgentUiService agentUiService) {
		this(userData, projects, recordings, recoverableRecordings, preferences, trash, actions,
				globalShortcuts, agent, automations, agentUiService, null);
	}

	/**
	 * The meetings UI service for this load, or {@code null} while AppContext has not created the
	 * meetings service (the Meetings section then shows "No meetings yet.").
	 */
	public MeetingUiService meetingService() {
		return meetings.get();
	}

	/** The AI-control settings page service: the wired service, or the disabled placeholder. */
	public dev.gravaai.ui.agent.AgentUiService agentUiServiceOrDefault() {
		return agentUiService != null ? agentUiService
				: dev.gravaai.ui.agent.PlaceholderAgentUiService.disabled();
	}

	/** Navigation and OS actions the dashboard delegates to the app shell. */
	public interface Actions {

		/** Opens a project in the editor (P1: the "Editor arrives in Phase 2" placeholder). */
		void openProject(String path);

		/** Opens a recording/recovered file in the editor. */
		void openRecording(String path);

		/**
		 * Opens a meeting in the meeting view ({@code MeetingViewHost} / {@code
		 * requestOpenMeeting} in React). The app shell opens the meeting window; the default keeps
		 * implementations without meeting navigation working by opening the media as a recording.
		 */
		default void openMeeting(String path) {
			openRecording(path);
		}

		/** The import/open-file flow (file chooser in the app shell). */
		void importFile();

		/**
		 * Imports an existing audio/video file as a meeting (dashboard toolbar in the Meetings
		 * section). The default falls back to the regular import flow.
		 */
		default void importMeeting() {
			importFile();
		}

		/** {@code shell.showItemInFolder} (portal FileManager1, plan §5.5). */
		void revealInFolder(String path);


		/** Opens the recording HUD (the "Record new" buttons). */
		void showRecordingHud();

		/** A still JPEG data URL for a raw recording card; completes with null when unavailable. */
		default java.util.concurrent.CompletableFuture<String> recordingThumbnail(String videoPath) {
			return java.util.concurrent.CompletableFuture.completedFuture(null);
		}

		/** {@code shell.openExternal} for announcement links. */
		void openExternalUrl(String url);

		/** Recovers one interrupted recording (recording service, P1.8). */
		void recoverRecording(RecoverableRecording recording);

		/** Discards one interrupted recording (recording service, P1.8). */
		void discardRecording(RecoverableRecording recording);
	}
}