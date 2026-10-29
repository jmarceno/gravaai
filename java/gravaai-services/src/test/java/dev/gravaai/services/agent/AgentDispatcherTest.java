package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.agent.AgentDispatcher.AgentDispatchError;
import dev.gravaai.services.agent.AgentDispatcher.EditorPort;
import dev.gravaai.services.agent.AgentDispatcher.HelpPort;
import dev.gravaai.services.agent.AgentDispatcher.LibraryPort;
import dev.gravaai.services.agent.AgentDispatcher.MeetingPort;
import dev.gravaai.services.agent.AgentJobRegistry.Job;
import dev.gravaai.services.agent.AgentJobRegistry.Kind;
import dev.gravaai.services.agent.AgentJobRegistry.Patch;
import dev.gravaai.services.agent.AgentJobRegistry.Status;
import dev.gravaai.services.agent.AgentTypes.AgentImage;
import dev.gravaai.services.agent.AgentTypes.AgentProgressUpdate;
import dev.gravaai.services.agent.AgentTypes.AgentToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Port of {@code electron/agent/dispatch.test.ts} (plan §5.16). */
class AgentDispatcherTest {

	@TempDir
	Path tempDir;

	/** Fake editor surface capturing requests. */
	private static final class FakeEditor implements EditorPort {
		final List<JsonNode> requests = new ArrayList<>();
		final List<String> tools = new ArrayList<>();
		JsonNode result = Json.nodeFactory().objectNode().put("text", "ok");
		boolean ready = true;
		boolean ensureResult = true;
		final AtomicInteger ensureCalls = new AtomicInteger();
		RuntimeException failure;

		@Override
		public boolean isReady() {
			return ready;
		}

		@Override
		public CompletableFuture<Boolean> ensureReady() {
			ensureCalls.incrementAndGet();
			return CompletableFuture.completedFuture(ensureResult);
		}

		@Override
		public CompletableFuture<JsonNode> requestEditor(String tool, JsonNode args,
				long timeoutMs) {
			tools.add(tool);
			requests.add(args);
			if (failure != null) {
				return CompletableFuture.failedFuture(failure);
			}
			return CompletableFuture.completedFuture(result);
		}

		JsonNode lastArgs() {
			return requests.get(requests.size() - 1);
		}
	}

	private static final class FakeLibrary implements LibraryPort {
		final List<Path> approved = new ArrayList<>();

		@Override
		public List<ProjectEntry> listProjects() {
			return List.of(new ProjectEntry("Demo", "/tmp/projects/demo.gravaai",
					java.time.Instant.parse("2026-10-25T10:00:00Z").toEpochMilli(), true));
		}

		@Override
		public List<RecordingEntry> listRecordings() {
			return List.of(new RecordingEntry("rec-1.mp4", "/tmp/recordings/rec-1.mp4",
					1024 * 1024, java.time.Instant.parse("2026-10-24T10:00:00Z").toEpochMilli()));
		}

		@Override
		public List<MeetingEntry> listMeetings() {
			return List.of(new MeetingEntry("Standup", "/tmp/recordings/standup.webm",
					java.time.Instant.parse("2026-10-23T10:00:00Z").toEpochMilli()));
		}

		@Override
		public void rememberApprovedLocalReadPath(Path path) {
			approved.add(path);
		}
	}

	/** Fake main-side meeting pipeline settling on the second poll. */
	private static final class FakeMeetings implements MeetingPort {
		final List<String> enqueued = new ArrayList<>();
		final List<String> cancelled = new ArrayList<>();
		final AtomicInteger polls = new AtomicInteger();
		String status = "running";
		Double progress = 40.0;

		@Override
		public MeetingJob enqueue(Path mediaPath, Mode mode) {
			enqueued.add(mediaPath + "|" + mode.wire());
			return new MeetingJob("meeting-1", "queued", null, null, null, null);
		}

		@Override
		public List<MeetingJob> listJobs() {
			polls.incrementAndGet();
			if (polls.get() >= 2) {
				status = "done";
				progress = 100.0;
			}
			return List.of(new MeetingJob("meeting-1", status, progress, "transcribe",
					"done".equals(status) ? "Done" : "Transcribing", null));
		}

		@Override
		public boolean cancel(String jobId) {
			cancelled.add(jobId);
			return true;
		}

		@Override
		public MeetingFiles filesFor(Path mediaPath) {
			String base = mediaPath.toString().replaceFirst("\\.[^./]+$", "");
			return new MeetingFiles(base + ".transcript.md", base + ".notes.md",
					base + ".meeting.json");
		}
	}

	private FakeEditor editor;
	private FakeLibrary library;
	private FakeMeetings meetings;
	private AgentJobRegistry jobs;
	private AgentDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		editor = new FakeEditor();
		library = new FakeLibrary();
		meetings = new FakeMeetings();
		jobs = new AgentJobRegistry();
		dispatcher = new AgentDispatcher(editor, library,
				topic -> "Gravaai help: clips zooms captions ops…", meetings, jobs,
				new AgentDispatcher.Config(10));
	}

	@AfterEach
	void tearDown() {
		dispatcher.close();
	}

	private AgentToolResult dispatch(String tool, ObjectNode args) throws Exception {
		return dispatcher.dispatch(tool, args, null).get(20, TimeUnit.SECONDS);
	}

	private void assertFails(String tool, ObjectNode args, String messagePart) {
		ExecutionException error = org.junit.jupiter.api.Assertions.assertThrows(
				ExecutionException.class,
				() -> dispatcher.dispatch(tool, args, null).get(20, TimeUnit.SECONDS));
		Throwable cause = error.getCause();
		assertTrue(cause instanceof AgentDispatchError, "unexpected cause: " + cause);
		assertTrue(cause.getMessage().contains(messagePart),
				"expected \"" + messagePart + "\" in: " + cause.getMessage());
	}

	private static ObjectNode args() {
		return Json.nodeFactory().objectNode();
	}

	@Test
	@DisplayName("answers help and the library without the renderer")
	void answersHelpAndLibrary() throws Exception {
		AgentToolResult help = dispatch("inspect", args().put("view", "help"));
		assertTrue(help.text().contains("Gravaai"));
		assertTrue(help.text().contains("clips"));
		AgentToolResult libraryResult = dispatch("inspect", args().put("view", "library"));
		assertTrue(libraryResult.text().contains("Demo — /tmp/projects/demo.gravaai"));
		assertTrue(libraryResult.text().contains("rec-1.mp4"));
		assertTrue(libraryResult.text().contains("Standup — /tmp/recordings/standup.webm"));
		assertTrue(editor.requests.isEmpty());
	}

	@Test
	@DisplayName("routes editor views through the renderer")
	void routesEditorViews() throws Exception {
		editor.result = Json.nodeFactory().objectNode().put("text", "overview text");
		AgentToolResult result = dispatch("inspect", args().put("view", "overview"));
		assertEquals("overview text", result.text());
		assertEquals("inspect", editor.tools.get(0));
		assertEquals("overview", editor.lastArgs().path("view").asString());
	}

	@Test
	@DisplayName("rejects unknown views and empty edit batches before touching the editor")
	void rejectsBadInputs() {
		assertFails("inspect", args().put("view", "nope"), "unknown view \"nope\"");
		ObjectNode emptyOps = args();
		emptyOps.putArray("ops");
		assertFails("edit", emptyOps, "non-empty array");
		assertTrue(editor.requests.isEmpty());
	}

	@Test
	@DisplayName("normalizes frame requests and returns their images")
	void normalizesFrames() throws Exception {
		ObjectNode contactSheet = Json.nodeFactory().objectNode().put("text",
				"contact sheet of 2 frames");
		ArrayNode images = contactSheet.putArray("images");
		images.addObject().put("mimeType", "image/jpeg").put("data", "AAAA");
		editor.result = contactSheet;

		ObjectNode first = args();
		first.put("from", 0).put("to", 10).put("count", 6).put("width", 5000);
		AgentToolResult result = dispatch("frames", first);
		assertEquals(1280, editor.lastArgs().path("width").asInt());
		assertEquals(6, editor.lastArgs().path("count").asInt());
		assertEquals(List.of(new AgentImage("image/jpeg", "AAAA")), result.images());

		ObjectNode second = args();
		second.putArray("at").add(1.5).add(2.5);
		dispatch("frames", second);
		assertEquals(2, editor.lastArgs().path("at").size());
		assertEquals(2, editor.lastArgs().path("count").asInt());
		assertEquals(640, editor.lastArgs().path("width").asInt());
	}

	@Test
	@DisplayName("explains bad frame requests without touching the editor")
	void explainsBadFrames() {
		assertFails("frames", args(), "frames needs");
		ObjectNode reversed = args();
		reversed.put("from", 5).put("to", 1);
		assertFails("frames", reversed, "after \"from\"");
		ObjectNode tooMany = args();
		ArrayNode at = tooMany.putArray("at");
		for (int index = 0; index < 10; index++) {
			at.add(index);
		}
		assertFails("frames", tooMany, "at most 9");
		assertTrue(editor.requests.isEmpty());
	}

	@Test
	@DisplayName("starts a renderer job and reports its id")
	void startsRendererJob() throws Exception {
		editor.result = Json.nodeFactory().objectNode().put("text", "job-1 transcribe started");
		ObjectNode start = args();
		start.put("action", "start").put("kind", "transcribe");
		start.putObject("options").put("language", "pt");
		AgentToolResult result = dispatch("job", start);
		assertTrue(result.text().contains("job-1 transcribe started"));
		JsonNode forwarded = editor.lastArgs();
		assertEquals("start", forwarded.path("action").asString());
		assertEquals("transcribe", forwarded.path("kind").asString());
		assertEquals("job-1", forwarded.path("jobId").asString());
		assertEquals("pt", forwarded.path("options").path("language").asString());
		assertEquals(Status.RUNNING, jobs.get("job-1").status());
	}

	@Test
	@DisplayName("refuses a second job of the same kind while one runs")
	void refusesDuplicateJob() throws Exception {
		Job running = jobs.create(Kind.EXPORT, "export mp4");
		jobs.update(running.id(), Patch.of(Status.RUNNING));
		ObjectNode start = args();
		start.put("action", "start").put("kind", "export").putObject("options");
		AgentToolResult result = dispatch("job", start);
		assertTrue(result.text().contains("still running"));
		assertTrue(editor.requests.isEmpty());
	}

	@Test
	@DisplayName("reports, waits on and cancels jobs from the registry")
	void reportsWaitsCancels() throws Exception {
		Job job = jobs.create(Kind.EXPORT, "export mp4");
		jobs.update(job.id(), new Patch(Status.RUNNING, 0.4, "encoding", null, null));

		ObjectNode status = args();
		status.put("action", "status").put("id", job.id());
		assertTrue(dispatch("job", status).text().contains("40%"));

		CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(30);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			jobs.update(job.id(), new Patch(Status.DONE, 1.0, null, null, null));
		});
		List<AgentProgressUpdate> updates = new ArrayList<>();
		ObjectNode wait = args();
		wait.put("action", "wait").put("id", job.id()).put("timeout", 5);
		AgentToolResult waited = dispatcher.dispatch("job", wait, updates::add)
				.get(20, TimeUnit.SECONDS);
		assertTrue(waited.text().contains("done"));
		assertFalse(updates.isEmpty());

		Job other = jobs.create(Kind.TRANSCRIBE, "transcribe");
		jobs.update(other.id(), Patch.of(Status.RUNNING));
		ObjectNode cancel = args();
		cancel.put("action", "cancel").put("id", other.id());
		assertTrue(dispatch("job", cancel).text().contains("cancelled"));
		assertEquals(Status.CANCELLED, jobs.get(other.id()).status());
	}

	@Test
	@DisplayName("runs meeting jobs in main and settles them")
	void runsMeetingJobs() throws Exception {
		Path media = tempDir.resolve("standup.webm");
		Files.writeString(media, "x");
		ObjectNode start = args();
		start.put("action", "start").put("kind", "meeting");
		start.putObject("options").put("path", media.toString()).put("mode", "full");
		assertTrue(dispatch("job", start).text().contains("meeting full"));

		List<AgentProgressUpdate> updates = new ArrayList<>();
		ObjectNode wait = args();
		wait.put("action", "wait").put("id", "job-1").put("timeout", 5);
		AgentToolResult waited = dispatcher.dispatch("job", wait, updates::add)
				.get(20, TimeUnit.SECONDS);
		assertTrue(waited.text().contains("done"));
		assertTrue(Json.MAPPER.writeValueAsString(jobs.get("job-1").result())
				.contains(".notes.md"));
		assertFalse(updates.isEmpty());
		assertTrue(meetings.enqueued.get(0).contains("full"));
	}

	@Test
	@DisplayName("opens the editor when nothing is ready yet")
	void opensEditor() throws Exception {
		editor.ready = false;
		editor.result = Json.nodeFactory().objectNode().put("text", "project: saved");
		AgentToolResult result = dispatch("project", args().put("action", "save"));
		assertTrue(result.text().contains("project:"));
		assertTrue(editor.ensureCalls.get() >= 1);
	}

	@Test
	@DisplayName("explains when the editor cannot be opened")
	void explainsMissingEditor() {
		editor.ready = false;
		editor.ensureResult = false;
		ObjectNode edit = args();
		edit.putArray("ops").addObject().put("op", "cut").put("from", 1).put("to", 2);
		assertFails("edit", edit, "editor did not open");
	}

	@Test
	@DisplayName("lists jobs from the registry")
	void listsJobs() throws Exception {
		jobs.create(Kind.EXPORT, "Export 1080p");
		AgentToolResult result = dispatch("inspect", args().put("view", "jobs"));
		assertTrue(result.text().contains("export queued"));
	}

	@Test
	@DisplayName("checks media paths and drops agent-supplied image content")
	void checksMediaPaths() throws Exception {
		ObjectNode audio = args();
		audio.putArray("ops").addObject().put("op", "audio")
				.put("path", "/definitely/missing.mp3").put("at", 0).put("to", 1);
		assertFails("edit", audio, "not a file");

		ObjectNode relative = args();
		relative.putArray("ops").addObject().put("op", "image").put("path", "relative.png")
				.put("from", 0).put("to", 1);
		assertFails("edit", relative, "absolute path");

		Path existing = tempDir.resolve("still.png");
		Files.writeString(existing, "image-bytes");
		ObjectNode image = args();
		ObjectNode imageOp = image.putArray("ops").addObject();
		imageOp.put("op", "image").put("path", existing.toString()).put("from", 0).put("to", 1)
				.put("imageContent", "https://evil.example/x.png");
		dispatch("edit", image);
		JsonNode forwardedOps = editor.lastArgs().path("ops");
		assertNotNull(forwardedOps);
		assertFalse(forwardedOps.get(0).has("imageContent"));
		assertEquals(existing.toString(), forwardedOps.get(0).path("path").asString());
		assertTrue(library.approved.contains(existing));
	}
	@Test
	@DisplayName("routes frames through the FramesPort when one is provided")
	void routesFramesPort() throws Exception {
		java.util.List<dev.gravaai.services.agent.AgentDispatcher.FrameRequest> captured =
				new java.util.ArrayList<>();
		dev.gravaai.services.agent.AgentDispatcher.FramesPort framesPort = request -> {
			captured.add(request);
			tools.jackson.databind.node.ObjectNode result = Json.nodeFactory().objectNode();
			result.put("text", "contact sheet");
			result.putArray("images").addObject().put("mimeType", "image/jpeg")
					.put("data", "AAAA");
			return java.util.concurrent.CompletableFuture.completedFuture(result);
		};
		dev.gravaai.services.agent.AgentDispatcher routed = new dev.gravaai.services.agent.AgentDispatcher(
				editor, library, topic -> "help", meetings, jobs,
				new dev.gravaai.services.agent.AgentDispatcher.Config(10), framesPort);
		try {
			ObjectNode args = args();
			args.put("from", 0).put("to", 10).put("count", 6).put("width", 5000);
			AgentToolResult result = routed.dispatch("frames", args, null)
					.get(20, java.util.concurrent.TimeUnit.SECONDS);
			assertEquals(1, captured.size());
			assertEquals(0.0, captured.get(0).from());
			assertEquals(10.0, captured.get(0).to());
			assertEquals(6, captured.get(0).count());
			assertEquals(1280, captured.get(0).width());
			assertEquals(List.of(new AgentImage("image/jpeg", "AAAA")), result.images());
			assertTrue(editor.requests.isEmpty());
		} finally {
			routed.close();
		}
	}

}
