package dev.gravaai.app.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.services.agent.AgentDispatcher;
import dev.gravaai.services.agent.AgentJobRegistry;
import dev.gravaai.services.agent.AgentService;
import dev.gravaai.services.agent.AgentTypes;
import dev.gravaai.services.meeting.MeetingJobMode;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.ui.agent.AgentHelpText;
import dev.gravaai.ui.agent.AgentUiService;
import dev.gravaai.ui.agent.ServiceAgentUiService;
import tools.jackson.databind.JsonNode;

/**
 * App-side assembly for AI control (plan §5.16, P3.2): the agent control server, the MCP tool
 * dispatcher over narrow permission ports, and the UI adapter (Settings page, status chip,
 * timeline highlights).
 *
 * <p>{@link AgentEditorPort} executes the real editor tools (plan P3.2b): inspect views, one atomic
 * {@link dev.gravaai.services.agent.AgentPlanner} edit batch per intent, offscreen compositor frames,
 * project open/save, and headless export jobs reported through the shared job registry.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class AgentWiring implements AutoCloseable {

	private final AgentService service;
	private final AgentUiService ui;
	private final AgentDispatcher dispatcher;

	private AgentWiring(AgentService service, AgentUiService ui, AgentDispatcher dispatcher) {
		this.service = service;
		this.ui = ui;
		this.dispatcher = dispatcher;
	}

	/**
	 * Builds and starts the agent service.
	 *
	 * @param activeEditor the editor session currently on screen, or null
	 * @param projects the project library (may be null during very early startup)
	 * @param meeting the meeting service, or null until meetings are ready
	 */
	public static AgentWiring create(UserData userData, AppSettingsStore settings,
			Supplier<EditorSession> activeEditor, Supplier<ProjectManager> projects,
			Supplier<MeetingService> meeting, Supplier<dev.gravaai.app.export.ProjectExporter> exporter,
			java.util.function.Consumer<Path> openProject, Runnable closeProject) {
		AgentJobRegistry jobs = new AgentJobRegistry();
		AgentEditorPort editorPort = new AgentEditorPort(activeEditor, exporter, openProject,
				closeProject, jobs, userData);
		AgentDispatcher dispatcher = new AgentDispatcher(
				editorPort,
				new AppLibraryPort(userData, projects, meeting),
				topic -> AgentHelpText.help(topic),
				new AppMeetingPort(meeting),
				jobs,
				AgentDispatcher.Config.DEFAULT);
		AgentTypes.AgentToolHandler handler = (request, context) ->
				dispatcher.dispatch(request.tool(), request.args(), context);
		AgentService service = new AgentService(
				userData.agentSocket(),
				userData.agentTokenFile(),
				handler,
				jobs,
				new AgentService.SettingsPort() {
					@Override
					public boolean aiControlEnabled() {
						JsonNode value = settings.readSetting("aiControlEnabled");
						return value != null && value.isBoolean() && value.booleanValue();
					}

					@Override
					public void setAiControlEnabled(boolean enabled) {
						try {
							settings.writeSetting("aiControlEnabled",
									Json.nodeFactory().booleanNode(enabled));
						} catch (IOException error) {
							throw new java.io.UncheckedIOException(error);
						}
					}
				},
				() -> CompletableFuture.completedFuture(false),
				appExecutablePath(),
				List.of("--mcp", "--ozone-platform=headless"));
		service.startIfEnabled();
		AgentUiService ui = new ServiceAgentUiService(service);
		// The timeline overlay is JavaFX: hop from the dispatcher worker thread to the FX thread.
		editorPort.onHighlights(updates -> javafx.application.Platform.runLater(() -> {
			try {
				ui.applyHighlights(updates);
			} catch (RuntimeException error) {
				// A disposed window/overlay must not fail the edit the agent already applied.
			}
		}));
		return new AgentWiring(service, ui, dispatcher);
	}

	public AgentService service() {
		return service;
	}

	public AgentUiService ui() {
		return ui;
	}

	@Override
	public void close() {
		try {
			dispatcher.close();
		} catch (RuntimeException ignored) {
			// Best effort.
		}
		service.close();
	}

	/** The packaged executable for the MCP command; falls back to {@code gravaai}. */
	private static String appExecutablePath() {
		String appImage = System.getenv("GRAVAAI_APPIMAGE");
		if (appImage != null && !appImage.isBlank()) {
			return appImage;
		}
		return ProcessHandle.current().info().command().orElse("gravaai");
	}

	// ── ports ───────────────────────────────────────────────────────────────────────────────

	/** Library reader used by {@code inspect library} and meeting tools. */
	private static final class AppLibraryPort implements AgentDispatcher.LibraryPort {
		private final UserData userData;
		private final Supplier<ProjectManager> projects;
		private final Supplier<MeetingService> meeting;

		AppLibraryPort(UserData userData, Supplier<ProjectManager> projects,
				Supplier<MeetingService> meeting) {
			this.userData = userData;
			this.projects = projects;
			this.meeting = meeting;
		}

		@Override
		public List<AgentDispatcher.LibraryPort.ProjectEntry> listProjects() throws Exception {
			ProjectManager manager = projects.get();
			if (manager == null) {
				return List.of();
			}
			List<AgentDispatcher.LibraryPort.ProjectEntry> entries = new ArrayList<>();
			for (ProjectLibraryEntry entry : manager.listProjectEntries()) {
				entries.add(new AgentDispatcher.LibraryPort.ProjectEntry(entry.name(), entry.path(),
						entry.updatedAt(), Path.of(entry.path())
								.equals(manager.currentProjectPath())));
			}
			return entries;
		}

		@Override
		public List<AgentDispatcher.LibraryPort.RecordingEntry> listRecordings() throws Exception {
			Path recordings;
			try {
				recordings = new RecordingsDirectory(userData).getRecordingsDir();
			} catch (IOException error) {
				return List.of();
			}
			if (!Files.isDirectory(recordings)) {
				return List.of();
			}
			List<AgentDispatcher.LibraryPort.RecordingEntry> entries = new ArrayList<>();
			try (var files = Files.list(recordings)) {
				for (Path file : files.toList()) {
					String name = file.getFileName().toString();
					if (!isMedia(name)) {
						continue;
					}
					long size = Files.size(file);
					long created = Files.getLastModifiedTime(file).toMillis();
					entries.add(new AgentDispatcher.LibraryPort.RecordingEntry(name,
							file.toString(), size, created));
				}
			}
			return entries;
		}

		private static boolean isMedia(String name) {
			String lower = name.toLowerCase(java.util.Locale.ROOT);
			return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
					|| lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".wav")
					|| lower.endsWith(".ogg") || lower.endsWith(".flac") || lower.endsWith(".mov");
		}

		@Override
		public List<AgentDispatcher.LibraryPort.MeetingEntry> listMeetings() throws Exception {
			MeetingService service = meeting.get();
			if (service == null) {
				return List.of();
			}
			var result = service.listMeetings();
			if (!(result instanceof dev.gravaai.core.result.Result.Ok<List<MeetingLibraryEntry>> ok)) {
				return List.of();
			}
			List<AgentDispatcher.LibraryPort.MeetingEntry> entries = new ArrayList<>();
			for (MeetingLibraryEntry entry : ok.value()) {
				entries.add(new AgentDispatcher.LibraryPort.MeetingEntry(entry.title(),
						entry.mediaPath().toString(), entry.createdAt()));
			}
			return entries;
		}

		@Override
		public void rememberApprovedLocalReadPath(Path path) {
			// The Java app has no renderer-side allow-list; local reads are already permitted.
		}
	}

	/** Meeting pipeline port over the app's MeetingService. */
	private static final class AppMeetingPort implements AgentDispatcher.MeetingPort {
		private final Supplier<MeetingService> meeting;

		AppMeetingPort(Supplier<MeetingService> meeting) {
			this.meeting = meeting;
		}

		private MeetingService require() {
			MeetingService service = meeting.get();
			if (service == null) {
				throw new AgentDispatcher.AgentDispatchError(
						"Meeting mode is not ready yet (the meetings service is starting).");
			}
			return service;
		}

		@Override
		public AgentDispatcher.MeetingPort.MeetingJob enqueue(Path mediaPath,
				AgentDispatcher.MeetingPort.Mode mode) {
			MeetingJobMode mapped = switch (mode) {
				case FULL -> MeetingJobMode.FULL;
				case TRANSCRIBE -> MeetingJobMode.TRANSCRIBE;
				case SUMMARIZE -> MeetingJobMode.SUMMARIZE;
			};
			var result = require().runJob(mediaPath, mapped);
			if (!(result instanceof dev.gravaai.core.result.Result.Ok<dev.gravaai.services.meeting.MeetingJob> ok)) {
				throw new AgentDispatcher.AgentDispatchError(
						result instanceof dev.gravaai.core.result.Result.Err<?> err ? err.error()
								: "Could not start the meeting job.");
			}
			return map(ok.value());
		}

		@Override
		public List<AgentDispatcher.MeetingPort.MeetingJob> listJobs() {
			List<AgentDispatcher.MeetingPort.MeetingJob> jobs = new ArrayList<>();
			for (var job : require().listJobs()) {
				jobs.add(map(job));
			}
			return jobs;
		}

		@Override
		public boolean cancel(String jobId) {
			return require().cancelJob(jobId);
		}

		@Override
		public AgentDispatcher.MeetingPort.MeetingFiles filesFor(Path mediaPath) {
			var result = require().getFiles(mediaPath);
			if (!(result instanceof dev.gravaai.core.result.Result.Ok<MeetingService.FilesView> ok)) {
				throw new AgentDispatcher.AgentDispatchError(
						result instanceof dev.gravaai.core.result.Result.Err<?> err ? err.error()
								: "Could not read the meeting files.");
			}
			var files = ok.value().paths();
			return new AgentDispatcher.MeetingPort.MeetingFiles(files.transcript().toString(),
					files.notes().toString(), files.meta().toString());
		}

		private static AgentDispatcher.MeetingPort.MeetingJob map(
				dev.gravaai.services.meeting.MeetingJob job) {
			return new AgentDispatcher.MeetingPort.MeetingJob(job.id(), job.status().wire(),
					job.progress() == null ? null : job.progress().doubleValue(),
					job.stage() == null ? null : job.stage().wire(), job.message(), job.error());
		}
	}
}
