package dev.gravaai.services.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.agent.AgentJobRegistry.Job;
import dev.gravaai.services.agent.AgentJobRegistry.Kind;
import dev.gravaai.services.agent.AgentJobRegistry.Patch;
import dev.gravaai.services.agent.AgentJobRegistry.Status;
import dev.gravaai.services.agent.AgentTypes.AgentImage;
import dev.gravaai.services.agent.AgentTypes.AgentProgressUpdate;
import dev.gravaai.services.agent.AgentTypes.AgentToolContext;
import dev.gravaai.services.agent.AgentTypes.AgentToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Server-side half of the agent surface (port of {@code electron/agent/dispatch.ts},
 * plan §5.16): routes a tool call to the editor (anything that touches open project state) or to a
 * main-process reader ({@code inspect library|jobs}, {@code inspect help}, {@code job meeting}).
 *
 * <p>All environment access goes through the narrow injected ports below so the AppContext can
 * implement them over {@code EditorStore}, the project manager, the compositor and the meeting
 * service; this class contains no Electron/UI dependency.
 */
public final class AgentDispatcher implements AutoCloseable {

	/** Thrown for validation/routing failures; the MCP bridge turns it into an error result. */
	public static final class AgentDispatchError extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public AgentDispatchError(String message) {
			super(message);
		}
	}

	/** Routes editor-bound calls to the editor surface (renderer in TS). */
	public interface EditorPort {
		boolean isReady();

		CompletableFuture<Boolean> ensureReady();

		/** Sends one tool request to the editor; the result is a JSON object with text/images. */
		CompletableFuture<JsonNode> requestEditor(String tool, JsonNode args, long timeoutMs);
	}

	/** Library reader for {@code inspect library} and media-op path approval. */
	public interface LibraryPort {
		record ProjectEntry(String name, String path, long updatedAt, boolean isCurrent) {}

		record RecordingEntry(String name, String path, long bytes, Long createdAt) {}

		record MeetingEntry(String title, String mediaPath, long createdAt) {}

		List<ProjectEntry> listProjects() throws Exception;

		List<RecordingEntry> listRecordings() throws Exception;

		List<MeetingEntry> listMeetings() throws Exception;

		void rememberApprovedLocalReadPath(Path path);
	}

	/** Help text provider (the op reference lives in the UI layer in TS). */
	@FunctionalInterface
	public interface HelpPort {
		String help(String topic);
	}

	/** A validated frame-capture request (the UI-independent half of the frames tool). */
	public record FrameRequest(List<Double> at, Double from, Double to, int count, int width) {
		public FrameRequest {
			at = at == null ? List.of() : List.copyOf(at);
		}
	}

	/** Renders preview frames as images; the AppContext wires this to the compositor. */
	public interface FramesPort {
		/** @return a JSON object with {@code text} and optional {@code images:[{mimeType,data}]} */
		CompletableFuture<JsonNode> capture(FrameRequest request);
	}

	/** Main-side meeting pipeline (port of the meeting job/library/pipeline modules). */
	public interface MeetingPort {
		enum Mode {
			FULL("full"),
			TRANSCRIBE("transcribe"),
			SUMMARIZE("summarize");

			private final String wire;

			Mode(String wire) {
				this.wire = wire;
			}

			public String wire() {
				return wire;
			}

			public static Mode fromWire(String value) {
				for (Mode mode : values()) {
					if (mode.wire.equals(value)) {
						return mode;
					}
				}
				return null;
			}
		}

		record MeetingJob(String id, String status, Double progress, String stage, String message,
				String error) {}

		record MeetingFiles(String transcript, String notes, String meta) {}

		/** Synchronous enqueue, like the real one (it starts the work in the background). */
		MeetingJob enqueue(Path mediaPath, Mode mode);

		List<MeetingJob> listJobs();

		boolean cancel(String jobId);

		MeetingFiles filesFor(Path mediaPath);
	}

	/** Tuning knobs (tests use a short poll interval). */
	public record Config(long pollMillis) {
		public static final Config DEFAULT = new Config(250);
	}

	private static final System.Logger LOG = System.getLogger("gravaai.agent.dispatch");

	private static final long RENDERER_TIMEOUT_MS = 30_000;
	private static final long INSPECT_TIMEOUT_MS = 15_000;
	private static final long FRAMES_TIMEOUT_MS = 60_000;
	private static final long JOB_START_TIMEOUT_MS = 15_000;
	private static final long JOB_WAIT_DEFAULT_MS = 60_000;
	private static final long JOB_WAIT_MAX_MS = 300_000;
	private static final int FRAME_WIDTH_MIN = 320;
	private static final int FRAME_WIDTH_MAX = 1280;
	private static final int FRAME_WIDTH_DEFAULT = 640;
	private static final int MAX_FRAMES = 9;
	private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;

	private final EditorPort editor;
	private final LibraryPort library;
	private final HelpPort help;
	private final FramesPort framesPort;
	private final MeetingPort meetings;
	private final AgentJobRegistry jobs;
	private final Config config;
	private final ExecutorService executor;
	private final ScheduledExecutorService meetingWatcher;
	private final Map<String, ScheduledFuture<?>> meetingWatches = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * @param editor the editor surface; @param library library reader; @param help help text;
	 *     @param meetings main-side meeting pipeline; @param jobs the shared job registry
	 */
	public AgentDispatcher(EditorPort editor, LibraryPort library, HelpPort help,
			MeetingPort meetings, AgentJobRegistry jobs, Config config) {
		this(editor, library, help, meetings, jobs, config, null);
	}

	/** Full constructor: frames port is optional (falls back to EditorPort.requestEditor). */
	public AgentDispatcher(EditorPort editor, LibraryPort library, HelpPort help,
			MeetingPort meetings, AgentJobRegistry jobs, Config config, FramesPort framesPort) {
		this.editor = editor;
		this.library = library;
		this.help = help;
		this.framesPort = framesPort;
		this.meetings = meetings;
		this.jobs = jobs;
		this.config = config == null ? Config.DEFAULT : config;
		this.executor = Executors.newVirtualThreadPerTaskExecutor();
		this.meetingWatcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "agent-meeting-watch");
			thread.setDaemon(true);
			return thread;
		});
	}

	/** {@code dispatchAgentTool}: runs on a worker thread; the future carries the result. */
	public CompletableFuture<AgentToolResult> dispatch(String toolName, JsonNode args,
			AgentToolContext context) {
		JsonNode resolvedArgs = args == null ? Json.nodeFactory().objectNode() : args;
		AgentToolContext resolvedContext = context == null ? update -> {} : context;
		return CompletableFuture.supplyAsync(
				() -> dispatchBlocking(toolName, resolvedArgs, resolvedContext), executor);
	}

	private AgentToolResult dispatchBlocking(String toolName, JsonNode args,
			AgentToolContext context) {
		return switch (toolName) {
			case "inspect" -> inspect(args);
			case "edit" -> edit(args);
			case "project" -> awaitEditor("project", args, RENDERER_TIMEOUT_MS);
			case "frames" -> frames(args);
			case "job" -> job(args, context);
			default -> failure("unknown tool \"" + toolName + "\"");
		};
	}

	private AgentToolResult inspect(JsonNode args) {
		String view = args.path("view").isString() ? args.path("view").asText() : "overview";
		switch (view) {
			case "help":
				return AgentToolResult.text(help.help(
						args.path("topic").isString() ? args.path("topic").asText() : null));
			case "library":
				return AgentToolResult.text(formatLibrary());
			case "jobs":
				return AgentToolResult.text(formatJobs());
			case "overview":
			case "timeline":
			case "transcript":
			case "settings":
				return awaitEditor("inspect", args, INSPECT_TIMEOUT_MS);
			default:
				return failure("unknown view \"" + view
						+ "\"; use overview, timeline, transcript, settings, library, jobs or help");
		}
	}

	private AgentToolResult edit(JsonNode args) {
		JsonNode ops = args.path("ops");
		if (!ops.isArray() || ops.isEmpty()) {
			return failure("edit needs \"ops\": a non-empty array");
		}
		ArrayNode prepared = prepareMediaOps(ops);
		ObjectNode forwarded = (ObjectNode) args.deepCopy();
		forwarded.set("ops", prepared);
		return awaitEditor("edit", forwarded, RENDERER_TIMEOUT_MS);
	}

	private AgentToolResult frames(JsonNode args) {
		Optional<Job> exporting = jobs.list().stream()
				.filter(job -> job.kind() == Kind.EXPORT
						&& !AgentJobRegistry.isTerminalJobStatus(job.status()))
				.findFirst();
		if (exporting.isPresent()) {
			return failure(exporting.get().id()
					+ " export is using the preview; wait for it to finish, then capture frames");
		}
		JsonNode normalized = normalizeFramesArgs(args);
		FramesPort port = framesPort;
		if (port == null) {
			return awaitEditor("frames", normalized, FRAMES_TIMEOUT_MS);
		}
		List<Double> at = new ArrayList<>();
		if (normalized.path("at").isArray()) {
			for (JsonNode value : normalized.path("at")) {
				at.add(value.asDouble());
			}
		}
		FrameRequest request = new FrameRequest(at,
				normalized.path("from").isNumber() ? normalized.path("from").asDouble() : null,
				normalized.path("to").isNumber() ? normalized.path("to").asDouble() : null,
				normalized.path("count").asInt(4), normalized.path("width").asInt(640));
		JsonNode result;
		try {
			result = port.capture(request).join();
		} catch (java.util.concurrent.CompletionException error) {
			Throwable cause = error.getCause() == null ? error : error.getCause();
			throw new AgentDispatchError(messageOf(cause));
		}
		return normalizeToolResult(result);
	}

	private AgentToolResult job(JsonNode args, AgentToolContext context) {
		String action = args.path("action").isString() ? args.path("action").asText() : "";
		switch (action) {
			case "start":
				return startJob(args);
			case "status":
				return AgentToolResult.text(describeJob(resolveJob(args)));
			case "wait":
				return waitJob(args, context);
			case "cancel": {
				Job job = resolveJob(args);
				if (AgentJobRegistry.isTerminalJobStatus(job.status())) {
					return AgentToolResult.text(job.id() + " already " + job.status().wire());
				}
				jobs.cancel(job.id());
				return AgentToolResult.text(job.id() + " " + job.kind().wire() + " cancelled");
			}
			default:
				return failure("unknown action; use start, status, wait or cancel");
		}
	}

	private AgentToolResult startJob(JsonNode args) {
		Kind kind = readJobKind(args.path("kind"), true);
		JsonNode options = args.path("options").isObject() ? args.path("options")
				: Json.nodeFactory().objectNode();
		if (kind == Kind.MEETING) {
			return startMeetingJob(options);
		}
		Optional<Job> duplicate = jobs.list().stream()
				.filter(job -> job.kind() == kind
						&& !AgentJobRegistry.isTerminalJobStatus(job.status()))
				.findFirst();
		if (duplicate.isPresent()) {
			return AgentToolResult.text(duplicate.get().id() + " " + kind.wire()
					+ " is still running; wait for it (job {action:\"wait\", id:\""
					+ duplicate.get().id() + "\"}) or cancel it first");
		}
		return startRendererJob(kind, options);
	}

	private AgentToolResult startRendererJob(Kind kind, JsonNode options) {
		String label = kind == Kind.TRANSCRIBE
				? "transcribe" + (options.path("language").isString()
						? " (" + options.path("language").asText() + ")"
						: "")
				: "export " + (options.path("format").isString()
						? options.path("format").asText()
						: "mp4");
		Job[] holder = new Job[1];
		holder[0] = jobs.create(kind, label,
				() -> requestEditorAsync("job",
						object(Json.nodeFactory().objectNode(), "action", "cancel", "jobId",
								holder[0].id()), JOB_START_TIMEOUT_MS));
		ObjectNode startArgs = Json.nodeFactory().objectNode();
		startArgs.put("action", "start");
		startArgs.put("kind", kind.wire());
		startArgs.put("jobId", holder[0].id());
		startArgs.set("options", options);
		AgentToolResult started;
		try {
			started = awaitEditor("job", startArgs, JOB_START_TIMEOUT_MS);
		} catch (RuntimeException error) {
			jobs.update(holder[0].id(), new Patch(Status.FAILED, null, null, messageOf(error),
					null));
			throw error;
		}
		Job current = jobs.get(holder[0].id());
		if (current != null && current.status() == Status.QUEUED) {
			jobs.update(holder[0].id(),
					new Patch(Status.RUNNING, null, "Running…", null, null));
		}
		String text = started.text().isEmpty() ? holder[0].id() + " started" : started.text();
		return AgentToolResult.text(text + "\n" + holder[0].id() + " " + kind.wire()
				+ " running — job {action:\"wait\", id:\"" + holder[0].id()
				+ "\"} reports progress");
	}

	private AgentToolResult startMeetingJob(JsonNode options) {
		String modeWire = options.path("mode").isString() ? options.path("mode").asText() : "full";
		MeetingPort.Mode mode = MeetingPort.Mode.fromWire(modeWire);
		if (mode == null) {
			return failure("unknown meeting mode \"" + modeWire
					+ "\"; use full, transcribe or summarize");
		}
		String mediaPath = options.path("path").isString() ? options.path("path").asText() : null;
		if (mediaPath == null || mediaPath.isEmpty()) {
			try {
				List<LibraryPort.MeetingEntry> all = new ArrayList<>(library.listMeetings());
				all.sort(Comparator.comparingLong(LibraryPort.MeetingEntry::createdAt).reversed());
				mediaPath = all.isEmpty() ? null : all.get(0).mediaPath();
			} catch (Exception error) {
				return failure("no meetings found; import one first, or pass \"path\"");
			}
			if (mediaPath == null) {
				return failure("no meetings found; import one first, or pass \"path\"");
			}
		}
		Path path = Path.of(mediaPath);
		if (!path.isAbsolute()) {
			return failure("\"path\" must be an absolute path");
		}
		if (!Files.isRegularFile(path)) {
			return failure("\"" + mediaPath + "\" is not a file on this machine");
		}
		library.rememberApprovedLocalReadPath(path);
		MeetingPort.MeetingJob meetingJob = meetings.enqueue(path, mode);
		Job job = jobs.create(Kind.MEETING,
				"meeting " + mode.wire() + ": " + path.getFileName(),
				() -> meetings.cancel(meetingJob.id()));
		jobs.update(job.id(), new Patch(Status.RUNNING, null, "Waiting…", null, null));
		watchMeetingJob(job.id(), meetingJob.id(), path, mode);
		return AgentToolResult.text(job.id() + " meeting " + mode.wire() + " running on "
				+ path.getFileName() + " — job {action:\"wait\", id:\"" + job.id()
				+ "\"} reports progress");
	}

	/** Mirrors the main-side meeting job into the agent registry until it settles. */
	private void watchMeetingJob(String jobId, String meetingJobId, Path mediaPath,
			MeetingPort.Mode mode) {
		ScheduledFuture<?> future = meetingWatcher.scheduleWithFixedDelay(() -> {
			try {
				MeetingPort.MeetingJob current = meetings.listJobs().stream()
						.filter(entry -> entry.id().equals(meetingJobId)).findFirst().orElse(null);
				if (current == null) {
					return;
				}
				boolean finished = "done".equals(current.status())
						|| "failed".equals(current.status())
						|| "cancelled".equals(current.status());
				if (!finished) {
					jobs.update(jobId, new Patch(
							"queued".equals(current.status()) ? Status.QUEUED : Status.RUNNING,
							current.progress() == null ? null : current.progress() / 100.0,
							current.message() != null && !current.message().isEmpty()
									? current.message()
									: current.stage(),
							null, null));
					return;
				}
				cancelMeetingWatch(jobId);
				MeetingPort.MeetingFiles files = meetings.filesFor(mediaPath);
				ObjectNode result = Json.nodeFactory().objectNode();
				result.put("mediaPath", mediaPath.toString());
				result.put("mode", mode.wire());
				if (mode != MeetingPort.Mode.TRANSCRIBE) {
					result.put("notesPath", files.notes());
				}
				result.put("transcriptPath", files.transcript());
				jobs.update(jobId, new Patch(
						"done".equals(current.status()) ? Status.DONE
								: "cancelled".equals(current.status()) ? Status.CANCELLED
										: Status.FAILED,
						"done".equals(current.status()) ? 1.0
								: current.progress() == null ? null : current.progress() / 100.0,
						current.message(), current.error(), result));
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.DEBUG,
						"meeting job mirror failed: " + error);
			}
		}, 0, config.pollMillis(), TimeUnit.MILLISECONDS);
		meetingWatches.put(jobId, future);
	}

	private void cancelMeetingWatch(String jobId) {
		ScheduledFuture<?> future = meetingWatches.remove(jobId);
		if (future != null) {
			future.cancel(false);
		}
	}

	private AgentToolResult waitJob(JsonNode args, AgentToolContext context) {
		Job job = resolveJob(args);
		Double timeoutSeconds = clamp(args.path("timeout"), 1, JOB_WAIT_MAX_MS / 1000.0);
		long timeoutMs = timeoutSeconds == null ? JOB_WAIT_DEFAULT_MS
				: (long) (timeoutSeconds * 1000);
		Job settled = waitForJob(job.id(), timeoutMs, context);
		if (settled == null) {
			return failure(job.id() + " disappeared while waiting");
		}
		if (!AgentJobRegistry.isTerminalJobStatus(settled.status())) {
			return AgentToolResult.text(describeJob(settled)
					+ "\nstill running; wait again or keep working");
		}
		return AgentToolResult.text(describeJob(settled));
	}

	private Job waitForJob(String jobId, long timeoutMs, AgentToolContext context) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		String lastSignature = "";
		while (true) {
			Job job = jobs.get(jobId);
			if (job == null) {
				return null;
			}
			if (AgentJobRegistry.isTerminalJobStatus(job.status())) {
				return job;
			}
			String signature = job.status().wire() + ":" + job.progress() + ":" + job.message();
			if (!signature.equals(lastSignature)) {
				lastSignature = signature;
				context.progress(new AgentProgressUpdate(job.progress() == null ? 0
						: job.progress(), null,
						job.message() == null ? job.kind().wire() + " " + job.status().wire()
								: job.message()));
			}
			if (System.nanoTime() >= deadline) {
				return job;
			}
			sleep(config.pollMillis());
		}
	}

	private Job resolveJob(JsonNode args) {
		String id = args.path("id").isString() && !args.path("id").asText().trim().isEmpty()
				? args.path("id").asText().trim()
				: null;
		Kind kind = readJobKind(args.path("kind"), false);
		if (id != null) {
			Job job = jobs.get(id);
			if (job == null) {
				List<Job> known = jobs.list();
				return failure(id + " not found; jobs: "
						+ (known.isEmpty() ? "none yet"
								: String.join(", ",
										known.stream().map(Job::id).toList())));
			}
			return job;
		}
		Optional<Job> latest = jobs.latest(kind);
		if (latest.isEmpty()) {
			return failure(kind != null
					? "no " + kind.wire() + " job yet; start one with job {action:\"start\", kind:\""
							+ kind.wire() + "\"}"
					: "no jobs yet; start one with job {action:\"start\", kind:\"transcribe|export|meeting\"}");
		}
		return latest.get();
	}

	private static Kind readJobKind(JsonNode value, boolean required) {
		if (value == null || value.isMissingNode() || value.isNull()) {
			if (required) {
				return failure("job needs \"kind\": transcribe, export or meeting");
			}
			return null;
		}
		String wire = value.isString() ? value.asText() : value.toString();
		Kind kind = Kind.fromWire(wire);
		if (kind == null) {
			return failure("unknown kind \"" + wire + "\"; use transcribe, export or meeting");
		}
		return kind;
	}

	private String describeJob(Job job) {
		double seconds = ((job.finishedAt() == null ? System.currentTimeMillis()
				: job.finishedAt()) - job.createdAt()) / 1000.0;
		StringBuilder builder = new StringBuilder();
		builder.append(job.id()).append(' ').append(job.kind().wire()).append(' ')
				.append(job.status().wire());
		if (job.progress() != null && !AgentJobRegistry.isTerminalJobStatus(job.status())) {
			builder.append(' ').append(Math.round(job.progress() * 100)).append('%');
		}
		if (job.error() != null && !job.error().isEmpty()) {
			builder.append(" — ").append(job.error());
		} else if (job.message() != null && !job.message().isEmpty()) {
			builder.append(" — ").append(job.message());
		}
		builder.append(" (").append(String.format(java.util.Locale.ROOT, "%.1f", seconds))
				.append("s)");
		if (job.result() != null) {
			builder.append('\n').append(Json.MAPPER.writeValueAsString(job.result()));
		}
		return builder.toString();
	}

	private String formatLibrary() {
		List<String> lines = new ArrayList<>();
		try {
			List<LibraryPort.ProjectEntry> projects = new ArrayList<>(library.listProjects());
			projects.sort(Comparator.comparingLong(LibraryPort.ProjectEntry::updatedAt).reversed());
			lines.add("projects (" + projects.size() + "):");
			for (int index = 0; index < Math.min(30, projects.size()); index++) {
				LibraryPort.ProjectEntry project = projects.get(index);
				lines.add("  " + project.name() + " — " + project.path() + " — modified "
						+ isoMinute(project.updatedAt())
						+ (project.isCurrent() ? " (open)" : ""));
			}
			if (projects.size() > 30) {
				lines.add("  …" + (projects.size() - 30) + " more");
			}
		} catch (Exception error) {
			lines.add("projects: could not read the library ("
					+ messageOf(error) + ")");
		}
		try {
			List<LibraryPort.RecordingEntry> recordings = new ArrayList<>(library.listRecordings());
			recordings.sort(Comparator.comparingLong(
					(LibraryPort.RecordingEntry entry) -> entry.createdAt() == null ? 0
							: entry.createdAt()).reversed());
			lines.add("recordings (" + recordings.size() + "):");
			for (int index = 0; index < Math.min(30, recordings.size()); index++) {
				LibraryPort.RecordingEntry recording = recordings.get(index);
				lines.add("  " + recording.name() + " — " + recording.path() + " — "
						+ String.format(java.util.Locale.ROOT, "%.1f",
								recording.bytes() / (1024.0 * 1024.0))
						+ " MB — created " + isoMinute(recording.createdAt()));
			}
			if (recordings.size() > 30) {
				lines.add("  …" + (recordings.size() - 30) + " more");
			}
		} catch (Exception error) {
			lines.add("recordings: could not read the library (" + messageOf(error) + ")");
		}
		try {
			List<LibraryPort.MeetingEntry> meetingsList = new ArrayList<>(library.listMeetings());
			meetingsList.sort(Comparator.comparingLong(
					LibraryPort.MeetingEntry::createdAt).reversed());
			lines.add("meetings (" + meetingsList.size() + "):");
			for (int index = 0; index < Math.min(30, meetingsList.size()); index++) {
				LibraryPort.MeetingEntry meeting = meetingsList.get(index);
				lines.add("  " + meeting.title() + " — " + meeting.mediaPath());
			}
			if (meetingsList.size() > 30) {
				lines.add("  …" + (meetingsList.size() - 30) + " more");
			}
		} catch (Exception error) {
			lines.add("meetings: could not read the library (" + messageOf(error) + ")");
		}
		return String.join("\n", lines);
	}

	private String formatJobs() {
		List<Job> entries = jobs.list();
		if (entries.isEmpty()) {
			return "no jobs running or finished in this session";
		}
		List<String> lines = new ArrayList<>();
		for (Job job : entries) {
			lines.add(AgentJobRegistry.formatJobLine(job));
		}
		return String.join("\n", lines);
	}

	private ArrayNode prepareMediaOps(JsonNode ops) {
		ArrayNode prepared = Json.nodeFactory().arrayNode();
		for (int index = 0; index < ops.size(); index++) {
			JsonNode op = ops.get(index);
			if (!op.isObject() || !"image".equals(op.path("op").asText())
					&& !"audio".equals(op.path("op").asText())) {
				prepared.add(op);
				continue;
			}
			if (!op.path("path").isString()) {
				prepared.add(op);
				continue;
			}
			String rawPath = op.path("path").asText();
			Path path = Path.of(rawPath);
			if (!path.isAbsolute()) {
				return failure("op " + index + ": \"path\" must be an absolute path");
			}
			if (!Files.isRegularFile(path)) {
				return failure("op " + index + ": \"" + rawPath
						+ "\" is not a file on this machine");
			}
			try {
				if ("image".equals(op.path("op").asText())
						&& Files.size(path) > MAX_IMAGE_BYTES) {
					return failure("op " + index + ": images must be under "
							+ MAX_IMAGE_BYTES / (1024 * 1024) + " MB");
				}
			} catch (java.io.IOException error) {
				return failure("op " + index + ": could not read \"" + rawPath + "\"");
			}
			library.rememberApprovedLocalReadPath(path);
			ObjectNode copy = (ObjectNode) op.deepCopy();
			copy.remove("imageContent");
			prepared.add(copy);
		}
		return prepared;
	}

	private ObjectNode normalizeFramesArgs(JsonNode args) {
		List<Double> at = new ArrayList<>();
		JsonNode atNode = args.path("at");
		if (atNode.isArray()) {
			for (JsonNode value : atNode) {
				if (value.isNumber() && Double.isFinite(value.asDouble())
						&& value.asDouble() >= 0) {
					at.add(value.asDouble());
				}
			}
		}
		if (at.size() > MAX_FRAMES) {
			return failure("at accepts at most " + MAX_FRAMES + " times");
		}
		Double from = args.path("from").isNumber() && Double.isFinite(args.path("from").asDouble())
				? args.path("from").asDouble()
				: null;
		Double to = args.path("to").isNumber() && Double.isFinite(args.path("to").asDouble())
				? args.path("to").asDouble()
				: null;
		Integer count = clampInt(args.path("count"), 1, MAX_FRAMES);
		if (at.isEmpty() && (from == null || to == null)) {
			return failure("frames needs \"at\" times, or \"from\" and \"to\" for a contact sheet");
		}
		if (from != null && to != null && to <= from) {
			return failure("\"to\" must be after \"from\"");
		}
		int width = Math.round((float) (clamp(args.path("width"), FRAME_WIDTH_MIN,
				FRAME_WIDTH_MAX) == null ? FRAME_WIDTH_DEFAULT
						: clamp(args.path("width"), FRAME_WIDTH_MIN, FRAME_WIDTH_MAX)));
		ObjectNode normalized = Json.nodeFactory().objectNode();
		if (!at.isEmpty()) {
			ArrayNode atArray = normalized.putArray("at");
			at.forEach(atArray::add);
		}
		if (from != null) {
			normalized.put("from", from);
		}
		if (to != null) {
			normalized.put("to", to);
		}
		normalized.put("count", count != null ? count : (at.isEmpty() ? 4 : at.size()));
		normalized.put("width", width);
		return normalized;
	}

	private AgentToolResult awaitEditor(String tool, JsonNode args, long timeoutMs) {
		if (!editor.isReady()) {
			Boolean ready;
			try {
				ready = editor.ensureReady().join();
			} catch (RuntimeException error) {
				ready = false;
			}
			if (!Boolean.TRUE.equals(ready)) {
				return failure("the Gravaai editor did not open; start Gravaai, then retry"
						+ " (Settings → AI control must be on)");
			}
		}
		JsonNode result;
		try {
			result = editor.requestEditor(tool, args, timeoutMs).join();
		} catch (java.util.concurrent.CompletionException error) {
			Throwable cause = error.getCause() == null ? error : error.getCause();
			throw new AgentDispatchError(messageOf(cause));
		}
		return normalizeToolResult(result);
	}

	/** Runs one editor request without waiting (cancel handles, best effort). */
	private void requestEditorAsync(String tool, JsonNode args, long timeoutMs) {
		try {
			editor.requestEditor(tool, args, timeoutMs);
		} catch (RuntimeException ignored) {
			// The job is gone already; cancelling is still the right answer.
		}
	}

	/** {@code normalizeToolResult}: bare strings or {text, images}. */
	private static AgentToolResult normalizeToolResult(JsonNode value) {
		if (value == null || value.isNull() || value.isMissingNode()) {
			return AgentToolResult.text("");
		}
		if (value.isString()) {
			return AgentToolResult.text(value.asText());
		}
		String text = value.path("text").isString() ? value.path("text").asText()
				: Json.MAPPER.writeValueAsString(value);
		List<AgentImage> images = new ArrayList<>();
		JsonNode imageNodes = value.path("images");
		if (imageNodes.isArray()) {
			for (JsonNode image : imageNodes) {
				if (image.path("mimeType").isString() && image.path("data").isString()
						&& !image.path("data").asText().isEmpty()) {
					images.add(new AgentImage(image.path("mimeType").asText(),
							image.path("data").asText()));
				}
			}
		}
		return new AgentToolResult(text, images);
	}

	private static ObjectNode object(ObjectNode node, String... keysAndValues) {
		for (int index = 0; index + 1 < keysAndValues.length; index += 2) {
			node.put(keysAndValues[index], keysAndValues[index + 1]);
		}
		return node;
	}

	private static Double clamp(JsonNode value, double min, double max) {
		if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
			return null;
		}
		return Math.min(max, Math.max(min, value.asDouble()));
	}

	private static Integer clampInt(JsonNode value, int min, int max) {
		Double clamped = clamp(value, min, max);
		return clamped == null ? null : (int) Math.round(clamped);
	}

	private static String isoMinute(long epochMillis) {
		String iso = Instant.ofEpochMilli(epochMillis).toString();
		return iso.substring(0, 16).replace('T', ' ');
	}

	private static String messageOf(Throwable error) {
		return error == null || error.getMessage() == null
				? (error == null ? "" : error.getClass().getSimpleName())
				: error.getMessage();
	}

	private static <T> T failure(String message) {
		throw new AgentDispatchError(message);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(Math.max(1, millis));
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new AgentDispatchError("the export was interrupted");
		}
	}

	@Override
	public void close() {
		for (ScheduledFuture<?> future : meetingWatches.values()) {
			future.cancel(false);
		}
		meetingWatches.clear();
		meetingWatcher.shutdownNow();
		executor.shutdownNow();
	}
}
