package dev.gravaai.services.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.RecentProjectsStore;
import dev.gravaai.core.storage.UserData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Port of the library side of {@code electron/ipc/project/manager.test.ts}. */
@Timeout(60)
class ProjectManagerTest {

	@TempDir
	Path tempDir;

	private ProjectManager lastManager;

	@org.junit.jupiter.api.AfterEach
	void awaitPendingThumbnailJobs() throws Exception {
		if (lastManager != null) {
			lastManager.awaitPendingThumbnails(15_000);
		}
	}

	private UserData userData() {
		return new UserData(tempDir.resolve("Gravaai"));
	}

	private ProjectManager manager(ProjectThumbnailGenerator generator) {
		UserData userData = userData();
		lastManager = new ProjectManager(userData, new RecordingsDirectory(userData),
				new RecentProjectsStore(userData.recentProjectsFile()), generator);
		return lastManager;
	}

	private Path sourceFile(String name) throws IOException {
		Path source = tempDir.resolve(name);
		Files.writeString(source, "video");
		return source;
	}

	@Test
	@DisplayName("generates a missing project poster once and keeps it cached")
	void generatesMissingPosterOnce() throws Exception {
		List<Path> generated = new CopyOnWriteArrayList<>();
		byte[] poster = ProjectTestFiles.pngHeader(640, 480);
		ProjectManager manager = manager(path -> {
			generated.add(path);
			return poster;
		});
		Path source = sourceFile("source.mp4");
		Path projectPath = tempDir.resolve("test.gravaai");
		ProjectTestFiles.writeProject(projectPath, source);
		String before = Files.readString(projectPath);

		ProjectLibraryEntry entry = manager.buildProjectLibraryEntry(projectPath, tempDir, ready -> {});

		assertNotNull(entry);
		assertNull(entry.thumbnailPath());
		manager.refreshProjectThumbnail(projectPath, ProjectRevision.read(projectPath)).get(10, TimeUnit.SECONDS);
		assertEquals(ProjectManager.getProjectThumbnailPath(projectPath).toString(),
				manager.buildProjectLibraryEntry(projectPath, tempDir, ready -> {}).thumbnailPath());
		assertEquals(List.of(source), generated);
		manager.buildProjectLibraryEntry(projectPath, tempDir, ready -> {});
		assertEquals(1, generated.size());
		assertEquals(before, Files.readString(projectPath));
	}

	@Test
	@DisplayName("preserves an existing project thumbnail when no replacement is provided")
	void preservesExistingThumbnail() throws Exception {
		Path projectPath = tempDir.resolve("Projects").resolve("demo.gravaai");
		Files.createDirectories(projectPath.getParent());
		String thumbnailDataUrl = "data:image/png;base64,"
				+ Base64.getEncoder().encodeToString("png-thumbnail".getBytes(StandardCharsets.UTF_8));

		Path thumbnailPath = ProjectManager.saveProjectThumbnail(projectPath, thumbnailDataUrl);
		ProjectManager.saveProjectThumbnail(projectPath, null);

		assertEquals("png-thumbnail", Files.readString(thumbnailPath));
	}

	@Test
	@DisplayName("returns a library entry while a thumbnail decode is still pending")
	void returnsEntryWhileThumbnailDecodePending() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		byte[] poster = ProjectTestFiles.pngHeader(640, 480);
		ProjectManager manager = manager(path -> {
			entered.countDown();
			release.await(20, TimeUnit.SECONDS);
			return poster;
		});
		Path source = sourceFile("slow.mp4");
		Path projectPath = tempDir.resolve("slow.gravaai");
		ProjectTestFiles.writeProject(projectPath, source);
		List<ProjectThumbnailReady> ready = new CopyOnWriteArrayList<>();

		ProjectLibraryEntry entry = manager.buildProjectLibraryEntry(projectPath, tempDir, ready::add);

		assertNotNull(entry);
		assertEquals(projectPath.toAbsolutePath().normalize().toString(), entry.path());
		assertNull(entry.thumbnailPath());
		CompletableFuture<ProjectThumbnailReady> task = manager.refreshProjectThumbnail(
				projectPath, ProjectRevision.read(projectPath));
		assertTrue(entered.await(5, TimeUnit.SECONDS));
		assertTrue(ready.isEmpty());
		release.countDown();
		ProjectThumbnailReady thumbnail = task.get(10, TimeUnit.SECONDS);
		assertNotNull(thumbnail);
		assertEquals(projectPath.toAbsolutePath().normalize().toString(), thumbnail.path());
		assertEquals(1, ready.size());
	}

	@Test
	@DisplayName("replaces a thumbnail symlink without overwriting its target")
	void replacesThumbnailSymlink() throws Exception {
		ProjectManager manager = manager(path -> "new poster".getBytes(StandardCharsets.UTF_8));
		Path source = sourceFile("source.mp4");
		Path projectPath = tempDir.resolve("linked.gravaai");
		ProjectTestFiles.writeProject(projectPath, source);
		Path target = tempDir.resolve("private.txt");
		Files.writeString(target, "must remain intact");
		Path thumbnail = ProjectManager.getProjectThumbnailPath(projectPath);
		Files.createSymbolicLink(thumbnail, target.getFileName());

		manager.refreshProjectThumbnail(projectPath, ProjectRevision.read(projectPath)).get(10, TimeUnit.SECONDS);

		assertEquals("must remain intact", Files.readString(target));
		assertFalse(Files.isSymbolicLink(thumbnail));
		assertEquals("new poster", Files.readString(thumbnail));
	}

	@Test
	@DisplayName("discards a thumbnail when the project changes during decoding")
	void discardsThumbnailWhenProjectChanges() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ProjectManager manager = manager(path -> {
			entered.countDown();
			release.await(20, TimeUnit.SECONDS);
			return ProjectTestFiles.pngHeader(640, 480);
		});
		Path source = sourceFile("source.mp4");
		Path projectPath = tempDir.resolve("edited.gravaai");
		ProjectTestFiles.writeProject(projectPath, source);

		CompletableFuture<ProjectThumbnailReady> task = manager.refreshProjectThumbnail(
				projectPath, ProjectRevision.read(projectPath));
		assertTrue(entered.await(5, TimeUnit.SECONDS));
		Files.writeString(projectPath, " ", StandardOpenOption.APPEND);
		release.countDown();

		assertNull(task.get(10, TimeUnit.SECONDS));
		assertFalse(Files.exists(ProjectManager.getProjectThumbnailPath(projectPath)));
	}

	@Test
	@DisplayName("reads library previews without switching the active project and rejects unknown projects")
	void readsLibraryPreviewWithoutSwitchingProject() throws Exception {
		ProjectManager manager = manager(path -> ProjectTestFiles.pngHeader(640, 480));
		Path source = sourceFile("video.mp4");
		Path projectsDir = manager.getProjectsDir();
		Path projectPath = projectsDir.resolve("preview.gravaai");
		ProjectTestFiles.writeProjectWithByteOrderMark(projectPath, source);
		manager.setCurrentProjectPath(Path.of("active.gravaai"));

		ProjectPreview preview = manager.readProjectPreview(projectPath);

		assertEquals(source.toString(), preview.videoPath());
		assertNull(preview.webcamPath());
		assertEquals(1, preview.project().get("version").asInt());
		assertEquals(Path.of("active.gravaai"), manager.currentProjectPath());
		IllegalStateException unknown = assertThrows(IllegalStateException.class,
				() -> manager.readProjectPreview(tempDir.resolve("unknown.gravaai")));
		assertTrue(unknown.getMessage().contains("not in the library"), unknown.getMessage());
	}

	@Test
	@DisplayName("opens projects saved before the profile folder was renamed")
	void remapsLegacyProfilePaths() throws Exception {
		Path currentRecordings = tempDir.resolve("Gravaai").resolve("recordings");
		Files.createDirectories(currentRecordings);
		Files.writeString(currentRecordings.resolve("recording-1.mp4"), "video");
		Path legacyRecordings = tempDir.resolve("Grava AI").resolve("recordings");
		Path projectPath = currentRecordings.resolve("Projects").resolve("old.gravaai");
		Files.createDirectories(projectPath.getParent());

		Map<String, Object> project = new LinkedHashMap<>();
		project.put("version", 1);
		project.put("videoPath", legacyRecordings.resolve("recording-1.mp4").toString());
		project.put("editor", new LinkedHashMap<>());
		Json.writeFile(projectPath, project);

		ProjectPreview preview = manager(path -> ProjectTestFiles.pngHeader(640, 480)).readProjectPreview(projectPath);

		assertEquals(currentRecordings.resolve("recording-1.mp4").toString(), preview.videoPath());
	}

	@Test
	@DisplayName("lists the Projects folder and the recent history sorted by last edit")
	void listsProjectsAndRecentHistory() throws Exception {
		ProjectManager manager = manager(path -> ProjectTestFiles.pngHeader(640, 480));
		Path projectsDir = manager.getProjectsDir();
		Path source = sourceFile("listing.mp4");
		long base = System.currentTimeMillis() - 100_000;
		Path alpha = projectsDir.resolve("Alpha.gravaai");
		Path beta = projectsDir.resolve("Beta.gravaai");
		Path legacy = projectsDir.resolve("Legacy.openscreen");
		Path external = tempDir.resolve("External.gravaai");
		for (Path project : List.of(alpha, beta, legacy, external)) {
			ProjectTestFiles.writeProject(project, source);
		}
		Files.setLastModifiedTime(alpha, FileTime.fromMillis(base + 4_000));
		Files.setLastModifiedTime(beta, FileTime.fromMillis(base + 3_000));
		Files.setLastModifiedTime(legacy, FileTime.fromMillis(base + 2_000));
		Files.setLastModifiedTime(external, FileTime.fromMillis(base + 1_000));
		UserData userData = userData();
		new RecentProjectsStore(userData.recentProjectsFile())
				.saveRecentProjectPaths(List.of(external.toString(), tempDir.resolve("missing.gravaai").toString()));

		ProjectManager.ProjectLibrary library = manager.listProjectLibraryEntries(ready -> {});

		List<String> paths = library.entries().stream().map(ProjectLibraryEntry::path).toList();
		assertEquals(List.of(alpha, beta, legacy, external).stream()
				.map(path -> path.toAbsolutePath().normalize().toString()).toList(), paths);
		assertEquals(List.of("Alpha", "Beta", "Legacy", "External"),
				library.entries().stream().map(ProjectLibraryEntry::name).toList());
		assertTrue(library.entries().get(0).isInProjectsDirectory());
		assertFalse(library.entries().get(3).isInProjectsDirectory());
		assertEquals(paths, new RecentProjectsStore(userData.recentProjectsFile()).loadRecentProjectPaths());
		assertEquals(projectsDir, library.projectsDir());
	}

	@Test
	@DisplayName("caps the recent-project history at 16 entries")
	void capsRecentHistoryAt16() throws Exception {
		ProjectManager manager = manager(path -> ProjectTestFiles.pngHeader(640, 480));
		Path projectsDir = manager.getProjectsDir();
		Path source = sourceFile("many.mp4");
		long base = System.currentTimeMillis() - 500_000;
		for (int index = 0; index < 20; index++) {
			Path project = projectsDir.resolve("Project-" + index + ".gravaai");
			ProjectTestFiles.writeProject(project, source);
			Files.setLastModifiedTime(project, FileTime.fromMillis(base + index * 1_000L));
		}

		ProjectManager.ProjectLibrary library = manager.listProjectLibraryEntries(ready -> {});
		List<String> recent = new RecentProjectsStore(userData().recentProjectsFile()).loadRecentProjectPaths();

		assertEquals(20, library.entries().size());
		assertEquals(16, recent.size());
		assertEquals(library.entries().stream().limit(16).map(ProjectLibraryEntry::path).toList(), recent);
	}

	@Test
	@DisplayName("hides stale previews and reports a fresh current-size PNG")
	void reportsFreshThumbnailOnly() throws Exception {
		ProjectThumbnailGenerator unreachable = path -> {
			throw new AssertionError("a fresh thumbnail must not be regenerated");
		};
		ProjectManager manager = manager(unreachable);
		Path source = sourceFile("fresh.mp4");
		Path projectPath = tempDir.resolve("fresh.gravaai");
		ProjectTestFiles.writeProject(projectPath, source);
		Path thumbnailPath = ProjectManager.getProjectThumbnailPath(projectPath);
		Files.write(thumbnailPath, ProjectTestFiles.pngHeader(640, 480));
		Files.setLastModifiedTime(thumbnailPath, Files.getLastModifiedTime(projectPath));

		ProjectLibraryEntry entry = manager.buildProjectLibraryEntry(projectPath, tempDir, ready -> {});

		assertEquals(thumbnailPath.toString(), entry.thumbnailPath());
	}

	@Test
	@DisplayName("resolves a local media-server video URL to its file path")
	void resolvesMediaServerUrl() throws Exception {
		Path source = sourceFile("url-video.mp4");
		String encoded = URLEncoder.encode(source.toString(), StandardCharsets.UTF_8);
		Map<String, Object> project = new LinkedHashMap<>();
		project.put("version", 2);
		project.put("videoPath", "http://127.0.0.1:4321/video?path=" + encoded);
		project.put("editor", new LinkedHashMap<>());

		var result = ProjectManager.resolveProjectMediaSources(
				Json.parseJsonWithByteOrderMark(Json.stringify(project)));

		assertTrue(result.isOk());
		assertEquals(source.toString(), result.orElse(null).videoPath());
	}

	@Test
	@DisplayName("rejects unloadable project JSON before reading media")
	void rejectsUnloadableProjectJson() {
		Map<String, Object> project = new LinkedHashMap<>();
		project.put("videoPath", tempDir.resolve("video.mp4").toString());
		project.put("editor", new LinkedHashMap<>());

		assertFalse(ProjectManager.isLoadableProjectData(Json.parseJsonWithByteOrderMark(Json.stringify(project))));
		assertTrue(ProjectManager.resolveProjectMediaSources(
				Json.parseJsonWithByteOrderMark(Json.stringify(project))).errorMessage()
				.startsWith("Project video file not found:"));

		Map<String, Object> emptyEditor = new LinkedHashMap<>();
		emptyEditor.put("version", 2);
		emptyEditor.put("editor", new LinkedHashMap<>());
		assertFalse(ProjectManager.isLoadableProjectData(Json.parseJsonWithByteOrderMark(Json.stringify(emptyEditor))));
	}
}