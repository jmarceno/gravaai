package dev.gravaai.app.agent;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.app.export.ProjectExporter;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.services.agent.AgentDispatcher;
import dev.gravaai.services.agent.AgentJobRegistry;
import dev.gravaai.services.agent.AgentPlanner;
import dev.gravaai.ui.agent.AgentHighlightUpdate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The app-side half of the agent editor bridge (port of
 * {@code src/components/video-editor/agent/useAgentBridge.ts} + {@code views.ts} + {@code jobs.ts},
 * plan §5.16/P3.2b): it executes the {@code inspect}, {@code edit}, {@code frames}, {@code project}
 * and {@code job} tools against the editor window the user has open.
 *
 * <p>Semantics intentionally mirror the React source:
 * <ul>
 *   <li>every read ends with a {@code rev}; a counter seeded from the clock that increments on
 *       every {@link dev.gravaai.core.editor.EditorStore} change (agent or user), so a stale
 *       {@code edit} fails with {@code STALE} instead of moving the user's edit;</li>
 *   <li>one {@code edit} batch is planned by {@link AgentPlanner} and committed as a single
 *       undoable {@link dev.gravaai.core.editor.EditorStore} change labeled by the caller;</li>
 *   <li>{@code revert_ai} restores the full state (timeline <em>and</em> settings) from before the
 *       last AI batch;</li>
 *   <li>{@code frames} renders through the production compositor offscreen (no UI capture);</li>
 *   <li>{@code job start kind:"export"} runs headlessly and reports into the dispatcher's
 *       {@link AgentJobRegistry} so {@code job wait}/{@code status} see the real progress.</li>
 * </ul>
 *
 * <p>This class runs on the dispatcher's worker threads, never on the FX thread.
 */
final class AgentEditorPort implements AgentDispatcher.EditorPort {

	private static final int MAX_FRAMES = 9;
	private static final int DEFAULT_FRAME_COUNT = 4;
	private static final int DEFAULT_FRAME_WIDTH = 640;
	private static final int MIN_FRAME_WIDTH = 320;
	private static final int MAX_FRAME_WIDTH = 1280;
	private static final int MAX_REVERT_ENTRIES = 20;
	private static final long REV_SEED_MASK = 1_000_000_000L;

	private final Supplier<@Nullable EditorSession> activeEditor;
	private final Supplier<@Nullable ProjectExporter> exporter;
	private final Consumer<Path> openProject;
	private final Runnable closeProject;
	private final AgentJobRegistry jobs;
	private final UserData userData;

	/** Rev tracking: one seeded counter per editor session, bumped by every store change. */
	private final Object revLock = new Object();
	private @Nullable EditorSession trackedSession;
	private long revBase;
	private long revCount;
	private @Nullable AutoCloseable revSubscription;
	private @Nullable EditorSession sourceSession;
	private long sourceDurationMs = -1;

	/** AI checkpoints for {@code revert_ai} (full project state, unlike Ctrl+Z's timeline arrays). */
	private final Deque<RevertEntry> revertStack = new ArrayDeque<>();

	/** Running headless export jobs, keyed by the dispatcher's job id. */
	private final Map<String, ExportJob> exportJobs = new ConcurrentHashMap<>();

	private volatile Consumer<List<AgentHighlightUpdate>> highlightSink = updates -> {};

	private record RevertEntry(ProjectEditorState state, long revAfter) {}

	private record ExportJob(AtomicBoolean cancelled) {}

	AgentEditorPort(Supplier<@Nullable EditorSession> activeEditor,
			Supplier<@Nullable ProjectExporter> exporter,
			Consumer<Path> openProject, Runnable closeProject, AgentJobRegistry jobs,
			UserData userData) {
		this.activeEditor = activeEditor;
		this.exporter = exporter;
		this.openProject = openProject;
		this.closeProject = closeProject;
		this.jobs = jobs;
		this.userData = userData;
	}

	/** P3.2b seam: the export controller for the {@code job} export tool. */
	@Nullable ProjectExporter exporter() {
		return exporter.get();
	}

	/** P3.2b seam: opens a project in the app's single window. */
	void openProject(Path path) {
		if (openProject != null && path != null) {
			openProject.accept(path);
		}
	}

	/** Wired by {@link AgentWiring} once the agent UI service exists. */
	void onHighlights(Consumer<List<AgentHighlightUpdate>> sink) {
		if (sink != null) {
			this.highlightSink = sink;
		}
	}

	// ── EditorPort ──────────────────────────────────────────────────────────────────────────

	@Override
	public boolean isReady() {
		return activeEditor.get() != null;
	}

	@Override
	public CompletableFuture<Boolean> ensureReady() {
		// The app is running (the bridge reached its socket). With no project open the agent can
		// still open one ("project open/new"), like the Electron bridge opening the editor window.
		return CompletableFuture.completedFuture(true);
	}

	@Override
	public CompletableFuture<JsonNode> requestEditor(String tool, JsonNode args, long timeoutMs) {
		try {
			EditorSession session = activeEditor.get();
			if (session == null) {
				String action = args != null && args.path("action").isString()
						? args.path("action").asString() : "";
				if ("project".equals(tool) && ("open".equals(action) || "new".equals(action))) {
					return CompletableFuture.completedFuture(text(project(null, args)));
				}
				throw new AgentDispatcher.AgentDispatchError(
						"No project is open in Gravaai. Open one with project {action:\"open\",path:\"…\"}"
								+ " (a .gravaai project, or a recording to start a new project).");
			}
			JsonNode result = switch (tool) {
				case "inspect" -> text(inspect(session, args));
				case "edit" -> text(edit(session, args));
				case "frames" -> frames(session, args);
				case "project" -> text(project(session, args));
				case "job" -> text(job(session, args));
				default -> throw new AgentDispatcher.AgentDispatchError(
						"The " + tool + " tool is not known to the Java editor.");
			};
			return CompletableFuture.completedFuture(result);
		} catch (RuntimeException error) {
			return CompletableFuture.failedFuture(error);
		}
	}

	// ── inspect ─────────────────────────────────────────────────────────────────────────────

	private String inspect(EditorSession session, JsonNode args) {
		String view = args != null && args.path("view").isString() ? args.path("view").asString()
				: "overview";
		long rev = rev(session);
		long sourceMs = sourceDurationMs(session);
		ProjectEditorState state = session.store().state();
		AgentEditorViews.Meta meta = new AgentEditorViews.Meta(session.displayName(),
				session.projectPath() == null ? null : session.projectPath().toString(),
				session.videoPath(), sourceMs, session.dirty(), rev);
		return switch (view) {
			case "timeline" -> AgentEditorViews.timeline(state, sourceMs, number(args, "from"),
					number(args, "to"), rev);
			case "transcript" -> AgentEditorViews.transcript(state, sourceMs, number(args, "from"),
					number(args, "to"), rev);
			case "settings" -> AgentEditorViews.settings(state,
					args != null && args.path("topic").isString() ? args.path("topic").asString()
							: null,
					rev);
			default -> AgentEditorViews.overview(state, meta);
		};
	}

	// ── edit ────────────────────────────────────────────────────────────────────────────────

	private String edit(EditorSession session, JsonNode args) {
		JsonNode ops = args == null ? null : args.path("ops");
		if (ops == null || !ops.isArray() || ops.isEmpty()) {
			throw new AgentDispatcher.AgentDispatchError("edit needs \"ops\": a non-empty array");
		}
		long rev = rev(session);
		JsonNode requestedRev = args.path("rev");
		if (requestedRev.isNumber() && requestedRev.asLong() != rev) {
			throw new AgentDispatcher.AgentDispatchError("STALE: the project changed since rev "
					+ requestedRev.asLong() + " (now rev " + rev + "); nothing was applied."
					+ " Re-read and retry.\n\n" + AgentEditorViews.overview(
							session.store().state(), meta(session, rev)));
		}
		if (ops.size() == 1 && isHistoryOp(ops.get(0))) {
			return applyHistoryOp(session, ops.get(0), rev);
		}

		ProjectEditorState state = session.store().state();
		AgentPlanner.Result result = AgentPlanner.apply(state, ops, plannerContext(session, state));
		if (result instanceof AgentPlanner.Failure failure) {
			String reason = failure.error();
			if (reason == null || reason.isBlank()) {
				reason = "the edit could not be planned";
			}
			throw new AgentDispatcher.AgentDispatchError(failure.opIndex() >= 0
					? "op " + failure.opIndex() + ": " + reason
					: reason);
		}
		AgentPlanner.Ok ok = (AgentPlanner.Ok) result;
		String summary = ok.summary() == null || ok.summary().isBlank()
				? ops.size() + " op" + (ops.size() == 1 ? "" : "s")
				: ok.summary();
		String label = args.path("label").isString() && !args.path("label").asString().isBlank()
				? args.path("label").asString().trim()
				: summary;

		if (args.path("dryRun").isBoolean() && args.path("dryRun").asBoolean()) {
			return "dry run \u2014 would apply: " + summary + "\nrev " + rev + " (unchanged)";
		}

		if (editorJson(ok.state()).equals(editorJson(state))) {
			return summary + " (no change)\nrev " + rev;
		}

		ProjectEditorState before = state;
		commit(session, ok.state());
		long newRev = rev(session);
		pushRevert(new RevertEntry(before, newRev));
		highlightSink.accept(highlights(ok.highlights(), label));
		long editedMs = editedDurationMs(ok.state(), sourceDurationMs(session));
		return summary + "\nrev " + newRev + " \u2022 edited "
				+ String.format(Locale.ROOT, "%.2f", editedMs / 1000.0) + "s";
	}

	private String applyHistoryOp(EditorSession session, JsonNode op, long rev) {
		String name = op.path("op").asString();
		int count = op.path("n").isNumber() && op.path("n").asInt() > 0 ? op.path("n").asInt() : 1;
		if ("revert_ai".equals(name)) {
			RevertEntry entry = revertStack.peek();
			if (entry == null) {
				throw new AgentDispatcher.AgentDispatchError("there is no AI change to revert");
			}
			if (entry.revAfter() != rev) {
				throw new AgentDispatcher.AgentDispatchError(
						"the project changed since the last AI change; use undo instead so those"
								+ " edits are not lost");
			}
			revertStack.pop();
			commit(session, entry.state());
			highlightSink.accept(List.of());
			return "reverted the last AI change\nrev " + rev(session);
		}
		boolean undo = "undo".equals(name);
		if (!undo && !"redo".equals(name)) {
			throw new AgentDispatcher.AgentDispatchError(
					"unknown history op \"" + name + "\"; use undo, redo or revert_ai");
		}
		int done = 0;
		for (int index = 0; index < count; index++) {
			boolean changed = undo ? session.store().undo() : session.store().redo();
			if (!changed) {
				break;
			}
			done++;
		}
		return (undo ? "undid " : "redid ") + done + " change" + (done == 1 ? "" : "s")
				+ "\nrev " + rev(session);
	}

	/** One editor store change = one undo step, so the user can Ctrl+Z the whole AI batch. */
	private void commit(EditorSession session, ProjectEditorState next) {
		session.store().apply(editor -> {
			editor.replaceState(next);
			return true;
		});
	}

	private void pushRevert(RevertEntry entry) {
		revertStack.push(entry);
		while (revertStack.size() > MAX_REVERT_ENTRIES) {
			revertStack.removeLast();
		}
	}

	private AgentPlanner.Context plannerContext(EditorSession session, ProjectEditorState state) {
		// The app supplies its own id factory over the store's counters (the TS editor keeps
		// nextZoomIdRef/nextClipIdRef equivalent counters) and the source's cursor telemetry, so
		// auto_zoom can cluster clicks exactly like the UI's suggest-zooms action.
		List<dev.gravaai.services.agent.AgentAutoZoom.Sample> telemetry = new ArrayList<>();
		try {
			for (dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils.CursorTelemetryPoint point
					: session.cursorTelemetryPoints()) {
				String interactionType = point.interactionType();
				if (interactionType == null) {
					// Position-only sample: the auto-zoom clusterer only groups click samples.
					continue;
				}
				telemetry.add(new dev.gravaai.services.agent.AgentAutoZoom.Sample(
						point.timeMs(), point.cx(), point.cy(), interactionType));
			}
		} catch (RuntimeException error) {
			telemetry.clear();
		}
		return new AgentPlanner.Context(sourceDurationMs(session), telemetry,
				kind -> session.store().nextId(kind.wire(), existingIds(state, kind)));
	}

	private static List<String> existingIds(ProjectEditorState state, AgentPlanner.IdKind kind) {
		return switch (kind) {
			case ZOOM -> state.zoomRegions().stream().map(zoom -> zoom.id()).toList();
			case CLIP -> state.clipRegions().stream().map(clip -> clip.id()).toList();
			case ANNOTATION -> state.annotationRegions().stream()
					.map(annotation -> annotation.id()).toList();
			case AUDIO -> state.audioRegions().stream().map(audio -> audio.id()).toList();
			case SPEED -> state.speedRegions().stream().map(speed -> speed.id()).toList();
		};
	}

	private static boolean isHistoryOp(JsonNode op) {
		if (op == null || !op.isObject() || !op.path("op").isString()) {
			return false;
		}
		String name = op.path("op").asString();
		return "undo".equals(name) || "redo".equals(name) || "revert_ai".equals(name);
	}

	private static List<AgentHighlightUpdate> highlights(List<AgentPlanner.Highlight> highlights,
			String label) {
		List<AgentHighlightUpdate> updates = new ArrayList<>();
		for (AgentPlanner.Highlight highlight : highlights == null ? List.<AgentPlanner.Highlight>of()
				: highlights) {
			updates.add(new AgentHighlightUpdate(
					AgentHighlightUpdate.Kind.fromWire(highlight.kind().wire()),
					highlight.startMs(), highlight.endMs(),
					highlight.label() == null || highlight.label().isBlank() ? label
							: highlight.label()));
		}
		return updates;
	}

	// ── frames ──────────────────────────────────────────────────────────────────────────────

	private JsonNode frames(EditorSession session, JsonNode args) {
		long editedMs = session.timelineDataSource().totalDurationMs();
		if (editedMs <= 0) {
			throw new AgentDispatcher.AgentDispatchError(
					"the timeline is empty; there is nothing to look at");
		}
		double durationSeconds = editedMs / 1000.0;
		FramePlan plan = framePlan(args, durationSeconds);
		List<AgentFrameRenderer.FrameImage> images;
		try {
			images = AgentFrameRenderer.render(session, plan.times(), plan.width());
		} catch (IOException | RuntimeException error) {
			throw new AgentDispatcher.AgentDispatchError(
					"could not render frames: " + messageOf(error));
		}
		List<String> times = new ArrayList<>();
		for (double time : plan.times()) {
			times.add(String.format(Locale.ROOT, "%.2f", time) + "s");
		}
		String text = "frames at " + String.join(", ", times) + " (" + plan.width() + "px wide)"
				+ " \u2022 edited " + String.format(Locale.ROOT, "%.2f", durationSeconds)
				+ "s \u2022 rev " + rev(session);
		ObjectNode result = Json.nodeFactory().objectNode();
		result.put("text", text);
		ArrayNode imageNodes = result.putArray("images");
		for (AgentFrameRenderer.FrameImage image : images) {
			ObjectNode node = imageNodes.addObject();
			node.put("mimeType", image.mimeType());
			node.put("data", image.data());
		}
		return result;
	}

	private record FramePlan(List<Double> times, int width) {}

	private static FramePlan framePlan(JsonNode args, double durationSeconds) {
		Double requestedWidth = number(args, "width");
		int width = (int) Math.round(clamp(requestedWidth == null ? DEFAULT_FRAME_WIDTH
				: requestedWidth, MIN_FRAME_WIDTH, MAX_FRAME_WIDTH));
		JsonNode at = args == null ? null : args.path("at");
		if (at != null && at.isArray() && !at.isEmpty()) {
			Set<Double> unique = new LinkedHashSet<>();
			for (JsonNode value : at) {
				if (value.isNumber() && Double.isFinite(value.asDouble())) {
					unique.add(round2(clamp(value.asDouble(), 0, durationSeconds)));
				}
			}
			List<Double> times = new ArrayList<>(unique);
			times.sort(Double::compare);
			if (times.size() > MAX_FRAMES) {
				times = times.subList(0, MAX_FRAMES);
			}
			if (!times.isEmpty()) {
				return new FramePlan(List.copyOf(times), width);
			}
		}
		Double requestedFrom = number(args, "from");
		double from = clamp(requestedFrom == null ? 0 : requestedFrom, 0, durationSeconds);
		Double requestedTo = number(args, "to");
		double to = clamp(requestedTo == null ? durationSeconds : requestedTo, from,
				durationSeconds);
		Double requestedCount = number(args, "count");
		int count = (int) Math.round(clamp(requestedCount == null ? DEFAULT_FRAME_COUNT
				: requestedCount, 1, MAX_FRAMES));
		double span = to - from;
		List<Double> times = new ArrayList<>();
		if (count == 1) {
			times.add(round2(from + span / 2));
		} else {
			for (int index = 0; index < count; index++) {
				times.add(round2(from + span * index / (count - 1)));
			}
		}
		return new FramePlan(List.copyOf(times), width);
	}

	// ── project ─────────────────────────────────────────────────────────────────────────────

	private String project(@Nullable EditorSession session, JsonNode args) {
		String action = args != null && args.path("action").isString()
				? args.path("action").asString() : "";
		String path = args != null && args.path("path").isString() ? args.path("path").asString()
				: null;
		boolean force = args != null && args.path("force").isBoolean()
				&& args.path("force").asBoolean();
		return switch (action) {
			case "open", "new" -> openOrCreate(session, args, action, path, force);
			case "save" -> save(requireSession(session));
			case "save_as" -> saveAs(requireSession(session), args);
			case "close" -> closeProject();
			default -> throw new AgentDispatcher.AgentDispatchError(
					"unknown action; use open, new, save, save_as or close");
		};
	}

	private String closeProject() {
		Runnable close = closeProject;
		if (close == null) {
			throw new AgentDispatcher.AgentDispatchError(
					"project close is not available in this build");
		}
		// Navigation happens on the FX thread: going Home closes the editor in the main window.
		javafx.application.Platform.runLater(close);
		return "Closed the project. The app is back on the dashboard.";
	}

	/** The open session for an action that edits it; the dispatcher rejects these without one. */
	private static EditorSession requireSession(@Nullable EditorSession session) {
		if (session == null) {
			throw new AgentDispatcher.AgentDispatchError(
					"No project is open in Gravaai. Open one with project {action:\"open\",path:\"…\"}");
		}
		return session;
	}

	private String openOrCreate(@Nullable EditorSession session, JsonNode args, String action,
			@Nullable String path, boolean force) {
		if (path == null || path.isBlank()) {
			throw new AgentDispatcher.AgentDispatchError(
					"project " + action + " needs \"path\" (see inspect {view:\"library\"})");
		}
		Path target = Path.of(path).toAbsolutePath().normalize();
		if (!Files.isRegularFile(target)) {
			throw new AgentDispatcher.AgentDispatchError("\"" + path + "\" is not a file");
		}
		guardUnsaved(session, force);
		if (openProject == null) {
			throw new AgentDispatcher.AgentDispatchError(
					"opening projects from the agent is not wired in this build");
		}
		openProject(target);
		EditorSession opened = awaitSession(session, target);
		if (opened == null) {
			throw new AgentDispatcher.AgentDispatchError(
					"could not open \"" + path + "\"; check the editor for the error");
		}
		String name = args != null && args.path("name").isString()
				? args.path("name").asString().trim() : "";
		if (!name.isEmpty()) {
			try {
				opened.saveAs(saveTarget(opened, name));
			} catch (IOException error) {
				throw new AgentDispatcher.AgentDispatchError(
						"opened " + path + ", but naming it \"" + name + "\" failed: "
								+ messageOf(error));
			}
			return "opened " + path + " as project \"" + name + "\" (saved)\n"
					+ AgentEditorViews.overview(opened.store().state(), meta(opened, rev(opened)));
		}
		boolean isProject = path.toLowerCase(Locale.ROOT).endsWith(".gravaai")
				|| path.toLowerCase(Locale.ROOT).endsWith(".openscreen");
		return "opened " + path + " as " + (isProject ? "project" : "a new project")
				+ "\n" + AgentEditorViews.overview(opened.store().state(), meta(opened, rev(opened)));
	}

	/** Waits (off the FX thread) until the app hosts the session for {@code target}. */
	private @Nullable EditorSession awaitSession(@Nullable EditorSession previous, Path target) {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
		while (System.nanoTime() < deadline) {
			EditorSession current = activeEditor.get();
			if (current != null && current != previous
					&& current.projectPath() != null
					&& current.projectPath().toAbsolutePath().normalize().equals(target)) {
				return current;
			}
			if (current != null && current != previous && current.videoPath() != null
					&& Path.of(current.videoPath()).toAbsolutePath().normalize().equals(target)) {
				return current;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		return null;
	}

	private void guardUnsaved(@Nullable EditorSession session, boolean force) {
		if (session == null || !session.dirty()) {
			return;
		}
		if (!force) {
			throw new AgentDispatcher.AgentDispatchError(
					"the open project has unsaved changes; save it first, or pass force:true to"
							+ " save it and continue");
		}
		try {
			if (session.isUnsaved()) {
				session.saveAs(saveTarget(session, session.displayName()));
			} else {
				session.save();
			}
		} catch (IOException error) {
			throw new AgentDispatcher.AgentDispatchError(
					"could not save before opening: " + messageOf(error));
		}
	}

	private String save(EditorSession session) {
		if (session.isUnsaved()) {
			throw new AgentDispatcher.AgentDispatchError(
					"this recording has no project file yet; use project {action:\"save_as\",name:\"…\"}"
							+ " (saved in the Projects folder)");
		}
		try {
			session.save();
		} catch (IOException error) {
			throw new AgentDispatcher.AgentDispatchError(
					"saving failed: " + messageOf(error));
		}
		return "saved " + session.projectPath();
	}

	private String saveAs(EditorSession session, JsonNode args) {
		String name = args != null && args.path("name").isString() ? args.path("name").asString()
				: "";
		if (name.isBlank()) {
			throw new AgentDispatcher.AgentDispatchError(
					"save_as needs \"name\"; the project is saved in the Projects folder");
		}
		Path target = saveTarget(session, name.trim());
		try {
			session.saveAs(target);
		} catch (IOException error) {
			throw new AgentDispatcher.AgentDispatchError(
					"saving failed: " + messageOf(error));
		}
		return "saved as \"" + name.trim() + "\" (" + target + ")";
	}

	private Path saveTarget(EditorSession session, String name) {
		String clean = name.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
		if (clean.toLowerCase(Locale.ROOT).endsWith(".gravaai")) {
			clean = clean.substring(0, clean.length() - ".gravaai".length());
		}
		Path projectFile = session.projectPath();
		Path parent = projectFile == null ? null : projectFile.getParent();
		if (parent == null) {
			parent = userData != null ? userData.projectsDir()
					: Path.of(System.getProperty("user.home"), "Gravaai Projects");
		}
		try {
			Files.createDirectories(parent);
		} catch (IOException ignored) {
			// saveAs surfaces the failure.
		}
		return parent.resolve(clean + ".gravaai");
	}

	// ── job ─────────────────────────────────────────────────────────────────────────────────

	private String job(EditorSession session, JsonNode args) {
		String action = args != null && args.path("action").isString()
				? args.path("action").asString() : "";
		JsonNode options = args != null && args.path("options").isObject() ? args.path("options")
				: Json.nodeFactory().objectNode();
		String jobId = args != null && args.path("jobId").isString() ? args.path("jobId").asString()
				: "";
		return switch (action) {
			case "start" -> startJob(session, args, jobId, options);
			case "cancel" -> cancelJob(jobId);
			default -> throw new AgentDispatcher.AgentDispatchError(
					"the editor handles job start and cancel; status and wait are main-side");
		};
	}

	private String startJob(EditorSession session, JsonNode args, String jobId, JsonNode options) {
		String kind = args != null && args.path("kind").isString() ? args.path("kind").asString()
				: "";
		if (jobId.isBlank()) {
			throw new AgentDispatcher.AgentDispatchError(
					"job start is missing the job id from main");
		}
		if ("transcribe".equals(kind)) {
			throw new AgentDispatcher.AgentDispatchError(
					"transcribe is not wired to the Java editor yet; export is available");
		}
		if (!"export".equals(kind)) {
			throw new AgentDispatcher.AgentDispatchError(
					"the editor can only start transcribe or export jobs");
		}
		Path destination;
		try {
			destination = AgentExportRunner.destination(options);
		} catch (IllegalArgumentException error) {
			throw new AgentDispatcher.AgentDispatchError(messageOf(error));
		}
		String format = AgentExportRunner.format(options);
		ExportJob handle = new ExportJob(new AtomicBoolean(false));
		exportJobs.put(jobId, handle);
		Thread.ofVirtual().name("agent-export-" + jobId).start(() -> {
			try {
				awaitJobStarted(jobId);
				if (handle.cancelled().get()) {
					jobs.update(jobId, new AgentJobRegistry.Patch(AgentJobRegistry.Status.CANCELLED,
							0.0, "cancelled", null, null));
					deleteQuietly(destination);
					return;
				}
				jobs.update(jobId, AgentJobRegistry.Patch.progress(0, "Rendering\u2026"));
				ProjectExporter exportController = exporter();
				if (exportController == null) {
					throw new AgentDispatcher.AgentDispatchError(
							"export is not available in this build");
				}
				AgentExportRunner.ExportResult result = AgentExportRunner.export(session,
						exportController, options);
				if (handle.cancelled().get()) {
					jobs.update(jobId, new AgentJobRegistry.Patch(AgentJobRegistry.Status.CANCELLED,
							0.0, "cancelled", null, null));
					deleteQuietly(destination);
					return;
				}
				jobs.update(jobId, new AgentJobRegistry.Patch(AgentJobRegistry.Status.DONE, 1.0,
						"done", null, result.toJson()));
			} catch (Exception error) {
				jobs.update(jobId, new AgentJobRegistry.Patch(AgentJobRegistry.Status.FAILED, null,
						"export failed", messageOf(error), null));
			} finally {
				exportJobs.remove(jobId);
			}
		});
		return format + " export started: " + destination;
	}

	/** The dispatcher marks the renderer job RUNNING right after this call returns. */
	private void awaitJobStarted(String jobId) {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			AgentJobRegistry.Job job = jobs.get(jobId);
			if (job == null || job.status() != AgentJobRegistry.Status.QUEUED) {
				return;
			}
			try {
				Thread.sleep(20);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private String cancelJob(String jobId) {
		ExportJob handle = jobId == null || jobId.isBlank() ? null : exportJobs.get(jobId);
		if (handle == null) {
			return (jobId == null || jobId.isBlank() ? "that job" : jobId)
					+ " is not running in the editor";
		}
		handle.cancelled().set(true);
		return "cancelling " + jobId;
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// Best effort: the job is cancelled either way.
		}
	}

	// ── rev + session helpers ───────────────────────────────────────────────────────────────

	/** Every read's rev: seeded per session, bumped by every store change (agent or user). */
	private long rev(EditorSession session) {
		synchronized (revLock) {
			if (trackedSession != session) {
				if (revSubscription != null) {
					try {
						revSubscription.close();
					} catch (Exception ignored) {
						// Best effort: the old session is gone.
					}
					revSubscription = null;
				}
				trackedSession = session;
				revBase = (System.currentTimeMillis() % REV_SEED_MASK) + revCount;
				revCount = 0;
				revertStack.clear();
				revSubscription = session.store().onChange(changed -> {
					synchronized (revLock) {
						revCount++;
					}
				});
			}
			return revBase + revCount;
		}
	}

	private AgentEditorViews.Meta meta(EditorSession session, long rev) {
		return new AgentEditorViews.Meta(session.displayName(),
				session.projectPath() == null ? null : session.projectPath().toString(),
				session.videoPath(), sourceDurationMs(session), session.dirty(), rev);
	}

	/** Source duration cached per session; a probe failure falls back to the edited duration. */
	private long sourceDurationMs(EditorSession session) {
		if (sourceSession == session && sourceDurationMs > 0) {
			return sourceDurationMs;
		}
		long duration = 0;
		try {
			MediaInfo info = MediaProbe.probe(Path.of(session.videoPath()));
			duration = Math.round(info.durationSeconds() * 1000);
		} catch (RuntimeException error) {
			duration = session.timelineDataSource().totalDurationMs();
		}
		if (duration <= 0) {
			duration = Math.max(1, session.timelineDataSource().totalDurationMs());
		}
		sourceSession = session;
		sourceDurationMs = duration;
		return duration;
	}

	private static long editedDurationMs(ProjectEditorState state, long sourceMs) {
		long end = 0;
		for (ClipRegion clip : state.clipRegions()) {
			end = Math.max(end, clip.endMs());
		}
		return end > 0 ? end : sourceMs;
	}

	private static ObjectNode editorJson(ProjectEditorState state) {
		return ProjectDocument.writeEditor(state, Json.nodeFactory().objectNode());
	}

	private static @Nullable ObjectNode text(String value) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("text", value);
		return node;
	}

	private static @Nullable Double number(JsonNode args, String key) {
		if (args == null) {
			return null;
		}
		JsonNode value = args.path(key);
		return value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
	}

	private static double clamp(double value, double min, double max) {
		return Math.min(max, Math.max(min, value));
	}

	private static double round2(double value) {
		return Math.round(value * 100) / 100.0;
	}

	private static String messageOf(Throwable error) {
		if (error == null) {
			return "unknown error";
		}
		return error.getMessage() == null || error.getMessage().isBlank()
				? error.getClass().getSimpleName() : error.getMessage();
	}
}
