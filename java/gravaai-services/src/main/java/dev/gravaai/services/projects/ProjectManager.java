package dev.gravaai.services.projects;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;

import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.LegacyProfilePaths;
import dev.gravaai.core.result.Result;
import dev.gravaai.core.storage.RecentMetadata;
import dev.gravaai.core.storage.RecentProjectsStore;
import dev.gravaai.core.storage.StorageFiles;
import dev.gravaai.core.storage.UserData;
import tools.jackson.databind.JsonNode;

/**
 * The project library read model (port of the library side of
 * {@code electron/ipc/project/manager.ts}).
 *
 * <p>Lists the {@code .gravaai} / legacy {@code .openscreen} files in {@code <recordings>/Projects}
 * plus the recent-project history, building the card data the dashboard needs (name, thumbnail,
 * dates). Thumbnails are refreshed in the background from the first video frame and emitted through
 * {@link ProjectThumbnailReady}. Projects are only ever read here; the editor model (save, load into
 * the editor) belongs to {@code core.project} (P2).
 *
 * <p>File IO is blocking; callers on the UI thread must run these methods on a background thread
 * (plan §4.1).
 */
public final class ProjectManager {

	private static final System.Logger LOG = System.getLogger("gravaai.projects");

	/** Blocking file IO and ffmpeg previews run on virtual threads (plan §4.1). */
	private static final Executor THUMBNAIL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

	private static final Pattern PNG_DATA_URL = Pattern.compile("^data:image/png;base64,(.+)$");

	private final UserData userData;
	private final RecordingsDirectory recordingsDirectory;
	private final RecentProjectsStore recentProjects;
	private final ProjectThumbnailGenerator thumbnailGenerator;
	private final Map<Path, CompletableFuture<ProjectThumbnailReady>> thumbnailJobs = new ConcurrentHashMap<>();
	private volatile Path currentProjectPath;

	public ProjectManager(UserData userData, RecordingsDirectory recordingsDirectory,
			RecentProjectsStore recentProjects, ProjectThumbnailGenerator thumbnailGenerator) {
		this.userData = userData;
		this.recordingsDirectory = recordingsDirectory;
		this.recentProjects = recentProjects;
		this.thumbnailGenerator = thumbnailGenerator;
	}

	/** The projects folder for the current recordings directory (port of {@code getProjectsDir}). */
	public Path getProjectsDir() throws IOException {
		Path projectsDir = StorageFiles.projectsDir(recordingsDirectory.getRecordingsDir());
		Files.createDirectories(projectsDir);
		return projectsDir;
	}

	/** Port of {@code getProjectThumbnailPath}: the preview lives next to the project file. */
	public static Path getProjectThumbnailPath(Path projectPath) {
		return StorageFiles.projectThumbnailPath(projectPath);
	}

	/** The project currently open in the editor, used for {@code isCurrent} (electron/ipc/state.ts). */
	public void setCurrentProjectPath(Path projectPath) {
		this.currentProjectPath = projectPath;
	}

	public Path currentProjectPath() {
		return currentProjectPath;
	}

	/**
	 * Port of {@code buildProjectLibraryEntry}: one card. Returns {@code null} for paths that are not
	 * project files (missing, a directory, another extension).
	 */
	public ProjectLibraryEntry buildProjectLibraryEntry(Path projectPath, Path projectsDir,
			Consumer<ProjectThumbnailReady> onThumbnailReady) {
		try {
			Path normalizedPath = projectPath.toAbsolutePath().normalize();
			if (!StorageFiles.hasProjectFileExtension(normalizedPath)) {
				return null;
			}

			BasicFileAttributes attributes;
			try {
				attributes = Files.readAttributes(normalizedPath, BasicFileAttributes.class);
			} catch (IOException error) {
				return null;
			}
			if (!attributes.isRegularFile()) {
				return null;
			}

			long updatedAt = attributes.lastModifiedTime().toMillis();
			Path thumbnailPath = getProjectThumbnailPath(normalizedPath);
			boolean thumbnailExists = ThumbnailFreshness.hasFreshProjectThumbnail(thumbnailPath, updatedAt);
			if (!thumbnailExists) {
				refreshProjectThumbnail(normalizedPath, ProjectRevision.of(normalizedPath, attributes))
						.thenAccept(ready -> {
							if (ready != null && onThumbnailReady != null) {
								onThumbnailReady.accept(ready);
							}
						})
						.exceptionally(error -> null);
			}

			long createdAt = attributes.creationTime().toMillis();
			if (createdAt == 0) {
				createdAt = ProjectRevision.of(normalizedPath, attributes).changed().toMillis();
			}

			return new ProjectLibraryEntry(
					normalizedPath.toString(),
					stripProjectExtension(normalizedPath.getFileName().toString()),
					createdAt,
					updatedAt,
					thumbnailExists ? thumbnailPath.toString() : null,
					isCurrentPath(normalizedPath),
					isInProjectsDirectory(normalizedPath, projectsDir));
		} catch (RuntimeException error) {
			return null;
		}
	}

	/**
	 * Port of {@code listProjectLibraryEntries}: the Projects folder plus the recent history, sorted
	 * by last edit, newest first. The recent history is rewritten to the listed entries.
	 */
	public ProjectLibrary listProjectLibraryEntries(
			Consumer<ProjectThumbnailReady> onThumbnailReady) throws IOException {
		Path projectsDir = getProjectsDir();
		List<Path> projectPaths = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(projectsDir)) {
			for (Path entry : entries) {
				if (Files.isRegularFile(entry) && StorageFiles.hasProjectFileExtension(entry)) {
					projectPaths.add(entry);
				}
			}
		} catch (IOException error) {
			// Ignore directory read failures and fall back to recent files, like the TS code.
		}

		List<String> recentProjectPaths = recentProjects.loadRecentProjectPaths();
		LinkedHashSet<Path> candidatePaths = new LinkedHashSet<>();
		for (Path projectPath : projectPaths) {
			candidatePaths.add(projectPath.toAbsolutePath().normalize());
		}
		for (String recentPath : recentProjectPaths) {
			try {
				candidatePaths.add(Path.of(recentPath));
			} catch (InvalidPathException ignored) {
				// The TS path.resolve never throws; a value Java cannot represent is skipped.
			}
		}

		List<ProjectLibraryEntry> library = new ArrayList<>();
		for (Path candidatePath : candidatePaths) {
			ProjectLibraryEntry entry = buildProjectLibraryEntry(candidatePath, projectsDir, onThumbnailReady);
			if (entry != null) {
				library.add(entry);
			}
		}
		library.sort(Comparator.comparingLong(ProjectLibraryEntry::updatedAt).reversed());
		recentProjects.saveRecentProjectPaths(library.stream().map(ProjectLibraryEntry::path).toList());
		return new ProjectLibrary(projectsDir, List.copyOf(library));
	}

	/**
	 * Port of {@code readProjectPreview}: reads a listed project's media for the hover preview
	 * without changing the active project. Media-server URLs become file paths (plan §2.2).
	 */
	public ProjectPreview readProjectPreview(Path projectPath) throws IOException {
		if (projectPath == null) {
			throw new IllegalArgumentException("Invalid project path");
		}
		Path normalizedPath = projectPath.toAbsolutePath().normalize();
		boolean listed = listProjectLibraryEntries(null).entries().stream()
				.anyMatch(entry -> entry.path().equals(normalizedPath.toString()));
		if (!listed) {
			throw new IllegalStateException("Project is not in the library");
		}

		JsonNode project = readProject(normalizedPath);
		if (!isLoadableProjectData(project)) {
			throw new IllegalStateException("Invalid project file format");
		}
		Result<ProjectMediaSources> media = resolveProjectMediaSources(project);
		if (media.isErr()) {
			throw new IllegalStateException(media.errorMessage());
		}
		ProjectMediaSources sources = media.orElse(null);
		return new ProjectPreview(project, sources.videoPath(), sources.webcamPath());
	}

	/** Reads and parses a project file, remapping media saved under a renamed profile folder. */
	public JsonNode readProject(Path projectPath) throws IOException {
		JsonNode project = Json.parseJsonWithByteOrderMark(
				Files.readString(projectPath, StandardCharsets.UTF_8));
		return adoptLegacyProfilePaths(project);
	}

	/** Port of {@code adoptLegacyProfilePaths}: points media at the current profile folder. */
	public JsonNode adoptLegacyProfilePaths(JsonNode project) {
		Path appDataPath = userData.dir().toAbsolutePath().normalize().getParent();
		return LegacyProfilePaths.remapLegacyProfilePaths(project,
				LegacyProfilePaths.profileDirRenames(appDataPath));
	}

	/** Port of {@code isLoadableProjectData}: the minimum a project card/preview can read. */
	public static boolean isLoadableProjectData(JsonNode projectData) {
		if (projectData == null || !projectData.isObject()) {
			return false;
		}
		JsonNode version = projectData.get("version");
		JsonNode projectId = projectData.get("projectId");
		JsonNode videoPath = projectData.get("videoPath");
		JsonNode editor = projectData.get("editor");
		return version != null
				&& version.isNumber()
				&& (projectId == null || projectId.isTextual())
				&& videoPath != null
				&& videoPath.isTextual()
				&& !videoPath.asText().isBlank()
				&& editor != null
				&& editor.isObject();
	}

	/**
	 * Port of {@code resolveProjectMediaSources}: the recording files a project needs. Only the
	 * fields the dashboard reads are touched (the full editor model belongs to P2).
	 */
	public static Result<ProjectMediaSources> resolveProjectMediaSources(JsonNode project) {
		if (project == null || !project.isObject()) {
			return Result.err("Invalid project file format");
		}

		JsonNode rawVideoPath = project.get("videoPath");
		if (rawVideoPath == null || !rawVideoPath.isTextual()) {
			return Result.err("Project file is missing a video path");
		}
		String videoPath = ProjectPaths.normalizeVideoSourcePath(rawVideoPath.asText());
		if (videoPath == null) {
			return Result.err("Project file is missing a valid video path");
		}
		if (!ProjectPaths.exists(videoPath)) {
			return Result.err("Project video file not found: " + videoPath);
		}

		String rawWebcamPath = null;
		JsonNode editor = project.get("editor");
		if (editor != null && editor.isObject()) {
			JsonNode webcam = editor.get("webcam");
			if (webcam != null && webcam.isObject()) {
				JsonNode sourcePath = webcam.get("sourcePath");
				if (sourcePath != null && sourcePath.isTextual()) {
					rawWebcamPath = sourcePath.asText();
				}
			}
		}
		String webcamPath = ProjectPaths.normalizeVideoSourcePath(rawWebcamPath);
		if (webcamPath == null || !ProjectPaths.exists(webcamPath)) {
			return Result.ok(new ProjectMediaSources(videoPath, null));
		}
		return Result.ok(new ProjectMediaSources(videoPath, webcamPath));
	}

	/** The listed projects, without a thumbnail-ready callback (dashboard refresh). */
	public List<ProjectLibraryEntry> listProjectEntries() throws IOException {
		return listProjectLibraryEntries(null).entries();
	}

	/** Port of the rename handler's recent-metadata step; a failure becomes a warning string. */
	public Optional<String> rememberRecentProject(String projectPath) {
		return RecentMetadata.persist(() -> {
			try {
				recentProjects.rememberRecentProject(projectPath);
			} catch (IOException error) {
				throw new UncheckedIOException(error);
			}
		});
	}

	/** Port of the trash handler's recent-metadata step: drops trashed projects from the history. */
	public Optional<String> forgetRecentProjects(List<String> deletedPaths) {
		return RecentMetadata.persist(() -> {
			List<String> remaining = new ArrayList<>();
			for (String path : recentProjects.loadRecentProjectPaths()) {
				String normalized;
				try {
					normalized = Path.of(path).toAbsolutePath().normalize().toString();
				} catch (InvalidPathException error) {
					normalized = path;
				}
				if (!deletedPaths.contains(normalized)) {
					remaining.add(path);
				}
			}
			try {
				recentProjects.saveRecentProjectPaths(remaining);
			} catch (IOException error) {
				throw new UncheckedIOException(error);
			}
		});
	}

	/** Port of the trash handler's {@code resolveRecordingPaths}: the media a project references. */
	public List<Path> resolveProjectRecordingPaths(Path projectPath) throws IOException {
		Result<ProjectMediaSources> media = resolveProjectMediaSources(readProject(projectPath));
		if (media.isErr()) {
			return List.of();
		}
		ProjectMediaSources sources = media.orElse(null);
		List<Path> paths = new ArrayList<>();
		paths.add(Path.of(sources.videoPath()));
		if (sources.webcamPath() != null) {
			paths.add(Path.of(sources.webcamPath()));
		}
		return paths;
	}

	/**
	 * Port of {@code refreshProjectThumbnail}: decode the first frame and cache it, discarding the
	 * result when the project changed meanwhile. Concurrent calls for the same project share one job.
	 */
	public CompletableFuture<ProjectThumbnailReady> refreshProjectThumbnail(
			Path projectPath, ProjectRevision revision) {
		Path key = projectPath.toAbsolutePath().normalize();
		CompletableFuture<ProjectThumbnailReady> task = thumbnailJobs.computeIfAbsent(key,
				path -> CompletableFuture.supplyAsync(() -> refreshProjectThumbnailNow(path, revision),
						THUMBNAIL_EXECUTOR));
		task.whenComplete((ready, error) -> thumbnailJobs.remove(key, task));
		return task;
	}

	/**
	 * Waits for in-flight preview jobs, so shutdown and tests that own a temporary profile do not
	 * race a background thumbnail write.
	 */
	public void awaitPendingThumbnails(long timeoutMillis) throws InterruptedException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (!thumbnailJobs.isEmpty()) {
			CompletableFuture<?>[] pending = thumbnailJobs.values().toArray(CompletableFuture[]::new);
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				return;
			}
			try {
				CompletableFuture.allOf(pending)
						.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
			} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
				return;
			}
		}
	}

	private ProjectThumbnailReady refreshProjectThumbnailNow(Path projectPath, ProjectRevision revision) {
		Path thumbnailPath = getProjectThumbnailPath(projectPath);
		Path temporary = null;
		try {
			if (!revision.equals(ProjectRevision.read(projectPath))) {
				return null;
			}
			JsonNode project = readProject(projectPath);
			if (!isLoadableProjectData(project)) {
				return null;
			}
			Result<ProjectMediaSources> media = resolveProjectMediaSources(project);
			if (media.isErr()) {
				return null;
			}
			byte[] thumbnail = thumbnailGenerator
					.createProjectFirstFrameThumbnail(Path.of(media.orElse(null).videoPath()));
			if (thumbnail == null || thumbnail.length == 0) {
				return null;
			}
			if (!revision.equals(ProjectRevision.read(projectPath))) {
				return null;
			}

			if (!ThumbnailFreshness.hasFreshProjectThumbnail(thumbnailPath, revision.modified().toMillis())) {
				temporary = thumbnailPath.resolveSibling(
						thumbnailPath.getFileName() + "." + UUID.randomUUID() + ".tmp");
				Files.write(temporary, thumbnail, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				// Stamp the source revision, rather than generation time, so a later edit invalidates it.
				Files.setLastModifiedTime(temporary, FileTime.fromMillis(revision.modified().toMillis()));
				if (!revision.equals(ProjectRevision.read(projectPath))) {
					return null;
				}
				// Rename replaces a sidecar symlink itself; it never writes through its target.
				Files.move(temporary, thumbnailPath, StandardCopyOption.REPLACE_EXISTING);
				temporary = null;
			}
			return new ProjectThumbnailReady(projectPath.toString(), thumbnailPath.toString(),
					revision.modified().toMillis());
		} catch (Exception error) {
			// Offline, corrupt or deleted media must not hold up the library.
			LOG.log(System.Logger.Level.DEBUG,
					"Could not refresh the preview of " + projectPath + ": " + error);
			return null;
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
					// The TS finally block swallows cleanup failures too.
				}
			}
		}
	}

	/**
	 * Port of {@code saveProjectThumbnail}.
	 *
	 * @param thumbnailDataUrl a PNG data URL; {@code null} means "no replacement" (keep the file),
	 *     an empty string deletes it, like {@code undefined} / {@code null} in the TS renderer API
	 * @return the thumbnail path, or {@code null} after a delete / when no file exists
	 */
	public static Path saveProjectThumbnail(Path projectPath, String thumbnailDataUrl) throws IOException {
		Path thumbnailPath = getProjectThumbnailPath(projectPath);
		if (thumbnailDataUrl == null) {
			return Files.exists(thumbnailPath) ? thumbnailPath : null;
		}
		if (thumbnailDataUrl.isEmpty()) {
			Files.deleteIfExists(thumbnailPath);
			return null;
		}

		Matcher match = PNG_DATA_URL.matcher(thumbnailDataUrl);
		if (!match.matches()) {
			throw new IllegalArgumentException("Project thumbnail must be a PNG data URL.");
		}
		Files.write(thumbnailPath, Base64.getDecoder().decode(match.group(1)));
		return thumbnailPath;
	}

	private boolean isCurrentPath(Path normalizedPath) {
		Path current = currentProjectPath;
		return current != null && current.toAbsolutePath().normalize().equals(normalizedPath);
	}

	private static boolean isInProjectsDirectory(Path normalizedPath, Path projectsDir) {
		Path parent = normalizedPath.getParent();
		return parent != null && parent.equals(projectsDir.toAbsolutePath().normalize());
	}

	/** {@code basename(path).replace(/\.(gravaai|openscreen)$/i, "")}. */
	private static String stripProjectExtension(String fileName) {
		int lastDot = fileName.lastIndexOf('.');
		if (lastDot <= 0) {
			return fileName;
		}
		String extension = fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
		return StorageFiles.hasProjectFileExtension("x." + extension)
				? fileName.substring(0, lastDot)
				: fileName;
	}

	/** Port of the TS return object {@code { projectsDir, entries }}. */
	public record ProjectLibrary(Path projectsDir, List<ProjectLibraryEntry> entries) {
	}
}