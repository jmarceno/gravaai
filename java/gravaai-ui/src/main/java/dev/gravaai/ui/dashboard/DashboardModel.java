package dev.gravaai.ui.dashboard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.gravaai.core.project.AtomicProjectSave;
import dev.gravaai.core.result.Result;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RecoverableRecording;
import dev.gravaai.services.projects.RecordingEntry;
import dev.gravaai.services.projects.RenameLibraryProject;
import dev.gravaai.services.projects.TrashLibraryProjects;
import dev.gravaai.ui.meeting.MeetingUiService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

/**
 * Dashboard UI state (port of {@code useDashboardModel} plus the folder, raw-library, meeting-library
 * and metadata hooks in {@code src/components/video-editor/dashboard/}).
 *
 * <p>JavaFX properties so the view can bind; no scene nodes here, so the class is testable without a
 * JavaFX toolkit. Everything that touches the disk is blocking and must be called from a background
 * thread by the view (plan §4.1); the model itself never starts threads.
 */
public final class DashboardModel {

	/** Port of the message {@code useMeetingLibrary.remove} throws for skipped meetings. */
	public static final String MEETINGS_NOT_DELETED =
			"Meetings that are still being processed were not deleted.";

	private static final java.util.regex.Pattern HEX_COLOR =
			java.util.regex.Pattern.compile("^#[0-9a-f]{6}$", java.util.regex.Pattern.CASE_INSENSITIVE);

	private final DashboardDependencies dependencies;
	private DashboardLibraryData preferences = DashboardLibraryData.empty();
	private final ObjectProperty<DashboardSection> section =
			new SimpleObjectProperty<>(DashboardSection.PROJECTS);
	/**
	 * The active library ({@code library} in React): {@code "projects"}, {@code "raw"} or
	 * {@code "meetings"}. A folder is a section inside the current library, so selecting a folder
	 * keeps filtering the library the user was in, exactly like {@code setSection} in React.
	 */
	private DashboardSection library = DashboardSection.PROJECTS;
	private final StringProperty query = new SimpleStringProperty("");
	private final ObjectProperty<DashboardLibraryFilters.Period> period =
			new SimpleObjectProperty<>(DashboardLibraryFilters.Period.ALL);
	private final ObjectProperty<DashboardLibraryFilters.Sort> sort =
			new SimpleObjectProperty<>(DashboardLibraryFilters.Sort.RECENT);
	private final ObservableList<ProjectLibraryEntry> projectEntries = FXCollections.observableArrayList();
	private final ObservableList<RecordingEntry> rawRecordings = FXCollections.observableArrayList();
	private final ObservableList<MeetingLibraryEntry> meetings = FXCollections.observableArrayList();
	private final ObservableList<RecoverableRecording> recoverableRecordings =
			FXCollections.observableArrayList();
	private final ObservableList<ProjectFolder> folders = FXCollections.observableArrayList();
	private final ObservableList<ProjectLibraryEntry> visibleEntries = FXCollections.observableArrayList();
	private final ObservableList<String> selectedPaths = FXCollections.observableArrayList();
	private final BooleanProperty loading = new SimpleBooleanProperty(false);
	private final BooleanProperty busy = new SimpleBooleanProperty(false);
	private final BooleanProperty selecting = new SimpleBooleanProperty(false);
	private final StringProperty error = new SimpleStringProperty("");
	private final Map<String, RecordingEntry> rawSources = new LinkedHashMap<>();
	private final Map<String, MeetingLibraryEntry> meetingSources = new LinkedHashMap<>();
	private String meetingsError = "";
	private Path projectsDir;
	private long now = System.currentTimeMillis();

	public DashboardModel(DashboardDependencies dependencies) {
		this.dependencies = dependencies;
		registerListeners();
	}

	public DashboardDependencies dependencies() {
		return dependencies;
	}

	/* Properties, for view bindings. */

	public ObjectProperty<DashboardSection> sectionProperty() {
		return section;
	}

	public StringProperty queryProperty() {
		return query;
	}

	public ObjectProperty<DashboardLibraryFilters.Period> periodProperty() {
		return period;
	}

	public ObjectProperty<DashboardLibraryFilters.Sort> sortProperty() {
		return sort;
	}

	public ObservableList<ProjectLibraryEntry> visibleEntries() {
		return visibleEntries;
	}

	public ObservableList<ProjectLibraryEntry> projectEntries() {
		return projectEntries;
	}

	public ObservableList<RecordingEntry> rawRecordings() {
		return rawRecordings;
	}

	/** The meetings of the Meetings section (port of {@code useMeetingLibrary.entries} sources). */
	public ObservableList<MeetingLibraryEntry> meetings() {
		return meetings;
	}

	public ObservableList<RecoverableRecording> recoverableRecordings() {
		return recoverableRecordings;
	}

	public ObservableList<ProjectFolder> folders() {
		return folders;
	}

	public ObservableList<String> selectedPaths() {
		return selectedPaths;
	}

	public BooleanProperty loadingProperty() {
		return loading;
	}

	public BooleanProperty busyProperty() {
		return busy;
	}

	public BooleanProperty selectingProperty() {
		return selecting;
	}

	public StringProperty errorProperty() {
		return error;
	}

	public DashboardLibraryData preferences() {
		return preferences;
	}

	/** The Projects folder path from the last snapshot (Settings -> Projects folder). */
	public Path projectsDir() {
		return projectsDir;
	}

	/** The recording behind a raw-library card ({@code entry.rawSource} in React). */
	public RecordingEntry rawSource(String path) {
		return rawSources.get(path);
	}

	/** The meeting behind a Meetings-section card ({@code entry.meeting} in React). */
	public MeetingLibraryEntry meetingSource(String path) {
		return meetingSources.get(path);
	}

	/** Port of {@code useMeetingLibrary.error}; blank when the last read succeeded. */
	public String meetingsError() {
		return meetingsError;
	}

	/** Fixes "now" for deterministic tests. */
	public void setNow(long now) {
		this.now = now;
		refreshVisible();
	}

	/** Applies one loaded snapshot on the FX thread. */
	public void applySnapshot(DashboardSnapshot snapshot) {
		preferences = snapshot.preferences();
		projectsDir = snapshot.projectsDir();
		folders.setAll(preferences.folders());
		projectEntries.setAll(snapshot.projects());
		rawSources.clear();
		for (RecordingEntry entry : snapshot.rawRecordings()) {
			rawSources.put(entry.path(), entry);
		}
		rawRecordings.setAll(snapshot.rawRecordings());
		meetingsError = snapshot.meetingsError() == null ? "" : snapshot.meetingsError();
		meetingSources.clear();
		for (MeetingLibraryEntry meeting : snapshot.meetings()) {
			meetingSources.put(meeting.mediaPath().toString(), meeting);
		}
		meetings.setAll(snapshot.meetings());
		recoverableRecordings.setAll(snapshot.recoverableRecordings());
		if (section.get() instanceof DashboardSection.Folder folder
				&& folders.stream().noneMatch(candidate -> candidate.id().equals(folder.id()))) {
			section.set(DashboardSection.PROJECTS);
		}
		now = System.currentTimeMillis();
		refreshVisible();
	}

	public DashboardSection section() {
		return section.get();
	}

	public void setSection(DashboardSection next) {
		section.set(next == null ? DashboardSection.PROJECTS : next);
	}

	public boolean isRaw() {
		return library instanceof DashboardSection.Raw;
	}

	public boolean isMeetings() {
		return library instanceof DashboardSection.Meetings;
	}

	public boolean isSettings() {
		return section.get() instanceof DashboardSection.Settings;
	}

	/** The active library, even when a folder is selected ({@code library} in React). */
	public DashboardSection library() {
		return library;
	}

	public String query() {
		return query.get();
	}

	public void setQuery(String next) {
		query.set(next == null ? "" : next);
	}

	public DashboardLibraryFilters.Period period() {
		return period.get();
	}

	public void setPeriod(DashboardLibraryFilters.Period next) {
		period.set(next == null ? DashboardLibraryFilters.Period.ALL : next);
	}

	public DashboardLibraryFilters.Sort sort() {
		return sort.get();
	}

	public void setSort(DashboardLibraryFilters.Sort next) {
		sort.set(next == null ? DashboardLibraryFilters.Sort.RECENT : next);
	}

	/** The folder selected in the sidebar, or {@code null}. */
	public ProjectFolder selectedFolder() {
		return DashboardLibraryFilters.selectedFolder(folders, section.get());
	}

	/** Port of the {@code visible} memo (filters + sort). */
	public void refreshVisible() {
		visibleEntries.setAll(DashboardLibraryFilters.visible(libraryEntries(), query.get(), period.get(),
				sort.get(), selectedFolder(), now));
	}

	/** The entries of the active library (projects, mapped raw files, or mapped meetings). */
	public List<ProjectLibraryEntry> libraryEntries() {
		return switch (library) {
			case DashboardSection.Raw ignored -> mappedRawEntries();
			case DashboardSection.Meetings ignored -> mappedMeetingEntries();
			default -> List.copyOf(projectEntries);
		};
	}

	/** Port of the React raw mapping: hidden files are dropped and stored names win. */
	public List<ProjectLibraryEntry> mappedRawEntries() {
		Set<String> hidden = new LinkedHashSet<>(preferences.hiddenRawPaths());
		List<ProjectLibraryEntry> mapped = new ArrayList<>();
		for (RecordingEntry recording : rawRecordings) {
			if (hidden.contains(recording.path())) {
				continue;
			}
			String name = preferences.rawNames().getOrDefault(recording.path(), recording.name());
			mapped.add(new ProjectLibraryEntry(recording.path(), name, recording.createdAt(),
					recording.createdAt(), null, false, false));
		}
		return mapped;
	}

	/**
	 * Port of {@code useMeetingLibrary.toEntry}: the media path is the entry path, the title is the
	 * name, the creation date feeds both dates, there is no thumbnail ({@link MeetingThumbnail} draws
	 * the placeholder) and the meeting is not a project.
	 */
	public List<ProjectLibraryEntry> mappedMeetingEntries() {
		List<ProjectLibraryEntry> mapped = new ArrayList<>();
		for (MeetingLibraryEntry meeting : meetings) {
			String title = meeting.title() == null || meeting.title().isBlank()
					? "Meeting" : meeting.title();
			mapped.add(new ProjectLibraryEntry(meeting.mediaPath().toString(), title,
					meeting.createdAt(), meeting.createdAt(), null, false, false));
		}
		return mapped;
	}

	/** Port of {@code hasActiveFilters}. */
	public boolean hasActiveFilters() {
		return DashboardLibraryFilters.hasActiveFilters(query.get(), period.get(), folders, section.get());
	}

	/* Selection (the trash mode). */

	public boolean isSelected(String path) {
		return selectedPaths.contains(path);
	}

	public void toggleSelected(String path) {
		if (path == null) {
			return;
		}
		if (selectedPaths.contains(path)) {
			selectedPaths.remove(path);
		} else {
			selectedPaths.add(path);
		}
	}

	public void selectAllVisible() {
		for (ProjectLibraryEntry entry : visibleEntries) {
			if (!selectedPaths.contains(entry.path())) {
				selectedPaths.add(entry.path());
			}
		}
	}

	public void clearSelection() {
		selectedPaths.clear();
	}

	public void setSelecting(boolean value) {
		selecting.set(value);
		if (!value) {
			selectedPaths.clear();
		}
	}

	/* Folders (useProjectFolders.ts). */

	/** Adds "Untitled folder", "Untitled folder 1", ... with the first palette colour. */
	public ProjectFolder createFolder() {
		String name = "Untitled folder";
		int index = 1;
		while (hasFolderName(name)) {
			name = "Untitled folder " + index++;
		}
		ProjectFolder folder = new ProjectFolder(UUID.randomUUID().toString(), name,
				ProjectFolder.COLORS.get(0), List.of());
		List<ProjectFolder> next = new ArrayList<>(folders);
		next.add(folder);
		saveFolders(next);
		return folder;
	}

	/** Renames a folder; a blank name is ignored like the React blur handler. */
	public void renameFolder(String id, String name) {
		String clean = name == null ? "" : name.trim();
		if (clean.isEmpty()) {
			return;
		}
		List<ProjectFolder> next = folders.stream()
				.map(folder -> folder.id().equals(id)
						? new ProjectFolder(folder.id(), clean, folder.color(), folder.paths())
						: folder)
				.toList();
		saveFolders(next);
	}

	public void removeFolder(String id) {
		List<ProjectFolder> next = folders.stream().filter(folder -> !folder.id().equals(id)).toList();
		saveFolders(next);
		if (section.get() instanceof DashboardSection.Folder folder && folder.id().equals(id)) {
			setSection(DashboardSection.PROJECTS);
		}
	}

	/**
	 * Port of {@code assignFolder}: toggles the project in one folder, or removes it from all when
	 * {@code folderId} is {@code "none"}.
	 */
	public void assignFolder(String path, String folderId) {
		List<ProjectFolder> next = new ArrayList<>();
		for (ProjectFolder folder : folders) {
			List<String> paths;
			if (folder.id().equals(folderId)) {
				paths = new ArrayList<>(folder.paths());
				if (paths.contains(path)) {
					paths.remove(path);
				} else {
					paths.add(path);
				}
			} else if ("none".equals(folderId)) {
				paths = folder.paths().stream().filter(candidate -> !candidate.equals(path)).toList();
			} else {
				paths = folder.paths();
			}
			next.add(new ProjectFolder(folder.id(), folder.name(), folder.color(), paths));
		}
		saveFolders(next);
	}

	/** Changes one folder's colour (the FolderColors swatch picker). */
	public void setFolderColor(String id, String color) {
		if (color == null || !HEX_COLOR.matcher(color).matches()) {
			return;
		}
		String normalized = color.toLowerCase(java.util.Locale.ROOT);
		List<ProjectFolder> next = folders.stream()
				.map(folder -> folder.id().equals(id)
						? new ProjectFolder(folder.id(), folder.name(), normalized, folder.paths())
						: folder)
				.toList();
		saveFolders(next);
	}

	/** Adds a custom colour to the folder palette (useDashboardMetadata colours). */
	public void addCustomColor(String color) {
		if (color == null || !HEX_COLOR.matcher(color).matches()) {
			return;
		}
		String normalized = color.toLowerCase(java.util.Locale.ROOT);
		if (preferences.colors().contains(normalized) || ProjectFolder.COLORS.contains(normalized)) {
			return;
		}
		List<String> colors = new ArrayList<>(preferences.colors());
		colors.add(normalized);
		applyPreferences(new DashboardLibraryData(folders, preferences.hiddenRawPaths(),
				preferences.rawNames(), colors));
	}

	/** Port of {@code moveProjectFolderReferences}: used after a project rename. */
	public void moveFolderReferences(String previous, String next) {
		DashboardLibraryData moved = DashboardLibraryStore.moveProjectFolderReferences(preferences,
				previous, next);
		applyPreferences(moved);
	}

	/* Project, raw-file and meeting operations (dashboard handlers). */

	/**
	 * Renames a library project (or a raw file's display name, or a meeting title) and moves folder
	 * references. Port of the rename handlers in {@code register/project.ts} and
	 * {@code useMeetingLibrary.rename}.
	 *
	 * @return the new path (unchanged for raw files and meetings, whose media path is stable)
	 */
	public String renameProject(String path, String name) throws IOException {
		String clean = name == null ? "" : name.trim();
		if (clean.isEmpty()) {
			return path;
		}
		MeetingLibraryEntry meeting = meetingSources.get(path);
		if (meeting != null) {
			MeetingUiService service = dependencies.meetingService();
			if (service == null) {
				throw new IOException("The meetings service is not available.");
			}
			Result<String> result = service.renameMeeting(meeting.mediaPath(), clean);
			if (result.isErr()) {
				throw new IOException(result.errorMessage());
			}
			replaceMeeting(meeting, result.orElse(clean));
			return path;
		}
		if (rawSources.containsKey(path)) {
			Map<String, String> names = new LinkedHashMap<>(preferences.rawNames());
			names.put(path, clean);
			applyPreferences(new DashboardLibraryData(folders, preferences.hiddenRawPaths(), names,
					preferences.colors()));
			return path;
		}

		Path source = Path.of(path);
		List<Path> allowed = projectEntries.stream().map(entry -> Path.of(entry.path())).toList();
		Path target = RenameLibraryProject.renameLibraryProject(source, clean, allowed,
				value -> List.of(ProjectManager.getProjectThumbnailPath(value),
						AtomicProjectSave.getProjectBackupPath(value)));
		Path current = dependencies.projects().currentProjectPath();
		if (current != null && current.toAbsolutePath().normalize()
				.equals(source.toAbsolutePath().normalize())) {
			dependencies.projects().setCurrentProjectPath(target);
		}
		dependencies.projects().rememberRecentProject(target.toString());
		moveFolderReferences(path, target.toString());
		return target.toString();
	}

	/** Puts a renamed meeting back into the list (the collection listeners refresh the grid). */
	private void replaceMeeting(MeetingLibraryEntry previous, String title) {
		MeetingLibraryEntry next = new MeetingLibraryEntry(previous.mediaPath(), title,
				previous.createdAt(), previous.durationMs(), previous.hasVideo(),
				previous.hasTranscript(), previous.hasNotes(), previous.status(), previous.error());
		meetingSources.put(next.mediaPath().toString(), next);
		int index = meetings.indexOf(previous);
		if (index >= 0) {
			meetings.set(index, next);
		} else {
			meetings.add(next);
		}
		refreshVisible();
	}

	/**
	 * Trashes project files (or hides raw files / trashes meetings) and returns the result. Port of
	 * the trash handler in {@code register/project.ts}; recordings are kept unless opted in.
	 */
	public TrashLibraryProjects.TrashResult trashProjects(List<String> paths, boolean deleteRecordings)
			throws IOException {
		if (isRaw()) {
			hideRawRecordings(paths);
			return new TrashLibraryProjects.TrashResult(List.copyOf(paths), List.of());
		}
		if (isMeetings()) {
			return new TrashLibraryProjects.TrashResult(deleteLibraryEntries(paths), List.of());
		}

		TrashLibraryProjects.Dependencies trashDependencies = new TrashLibraryProjects.Dependencies(
				() -> dependencies.projects().listProjectEntries().stream()
						.map(entry -> Path.of(entry.path())).toList(),
				file -> dependencies.trash().trash(file),
				ProjectManager::getProjectThumbnailPath,
				projectPath -> dependencies.projects().resolveProjectRecordingPaths(projectPath));
		TrashLibraryProjects.TrashResult result = TrashLibraryProjects.trashLibraryProjects(paths,
				trashDependencies, deleteRecordings);
		if (result.deleted().isEmpty()) {
			return result;
		}

		Path current = dependencies.projects().currentProjectPath();
		if (current != null && result.deleted().contains(current.toAbsolutePath().normalize().toString())) {
			dependencies.projects().setCurrentProjectPath(null);
		}
		dependencies.projects().forgetRecentProjects(result.deleted());
		projectEntries.removeIf(entry -> result.deleted().contains(entry.path()));
		selectedPaths.removeAll(result.deleted());
		moveFolderReferencesAfterTrash(result.deleted());
		refreshVisible();
		if (result.errors().isEmpty() && selectedPaths.isEmpty()) {
			selecting.set(false);
		}
		return result;
	}

	/**
	 * Port of {@code deleteEntries} for the libraries without a delete dialog: raw files are hidden,
	 * meetings move to the system trash.
	 *
	 * @return the deleted entry paths
	 */
	public List<String> deleteLibraryEntries(List<String> paths) throws IOException {
		if (isRaw()) {
			hideRawRecordings(paths);
			return List.copyOf(paths);
		}
		return deleteMeetings(paths);
	}

	/**
	 * Port of {@code useMeetingLibrary.remove}: deletes the meetings that are not being processed and
	 * throws when some were skipped (React throws after dropping the deleted entries).
	 */
	private List<String> deleteMeetings(List<String> paths) throws IOException {
		MeetingUiService service = dependencies.meetingService();
		if (service == null) {
			throw new IOException("The meetings service is not available.");
		}
		List<Path> mediaPaths = new ArrayList<>();
		for (String path : paths) {
			mediaPaths.add(Path.of(path));
		}
		Result<List<Path>> result = service.deleteMeetings(mediaPaths);
		if (result.isErr()) {
			throw new IOException(result.errorMessage());
		}
		List<String> deleted = result.orElse(List.of()).stream().map(Path::toString).toList();
		for (String path : deleted) {
			MeetingLibraryEntry removed = meetingSources.remove(path);
			if (removed != null) {
				meetings.remove(removed);
			}
		}
		selectedPaths.removeAll(deleted);
		moveFolderReferencesAfterTrash(deleted);
		refreshVisible();
		if (selectedPaths.isEmpty()) {
			selecting.set(false);
		}
		if (deleted.size() < paths.size()) {
			throw new IOException(MEETINGS_NOT_DELETED);
		}
		return deleted;
	}

	/** Hides raw-library files (port of {@code useRawLibrary.remove}: a local preference, not trash). */
	public void hideRawRecordings(List<String> paths) {
		Set<String> hidden = new LinkedHashSet<>(preferences.hiddenRawPaths());
		hidden.addAll(paths);
		applyPreferences(new DashboardLibraryData(folders, List.copyOf(hidden),
				preferences.rawNames(), preferences.colors()));
		selectedPaths.removeAll(paths);
		refreshVisible();
	}

	private void moveFolderReferencesAfterTrash(List<String> deleted) {
		List<ProjectFolder> next = new ArrayList<>();
		for (ProjectFolder folder : folders) {
			List<String> paths = folder.paths().stream()
					.filter(path -> !deleted.contains(path))
					.toList();
			next.add(new ProjectFolder(folder.id(), folder.name(), folder.color(), paths));
		}
		saveFolders(next);
	}

	private boolean hasFolderName(String name) {
		return folders.stream().anyMatch(folder -> folder.name().equals(name));
	}

	private void saveFolders(List<ProjectFolder> next) {
		folders.setAll(next);
		applyPreferences(new DashboardLibraryData(next, preferences.hiddenRawPaths(),
				preferences.rawNames(), preferences.colors()));
	}

	private void applyPreferences(DashboardLibraryData next) {
		preferences = next;
		if (!folders.equals(next.folders())) {
			folders.setAll(next.folders());
		}
		try {
			dependencies.preferences().save(next);
			error.set("");
		} catch (IOException exception) {
			error.set("Could not save library preferences: " + exception.getMessage());
		}
		refreshVisible();
	}

	private void registerListeners() {
		section.addListener((observable, oldValue, newValue) -> {
			if (newValue instanceof DashboardSection.Projects
					|| newValue instanceof DashboardSection.Raw
					|| newValue instanceof DashboardSection.Meetings) {
				// React's setSection only changes the library for these ids; a folder id keeps it.
				library = newValue;
			}
			if (oldValue != null && !oldValue.wire().equals(newValue.wire())) {
				// React's setSection clears the search and the selection.
				query.set("");
				selecting.set(false);
				selectedPaths.clear();
			}
			refreshVisible();
		});
		query.addListener((observable, oldValue, newValue) -> refreshVisible());
		period.addListener((observable, oldValue, newValue) -> refreshVisible());
		sort.addListener((observable, oldValue, newValue) -> refreshVisible());
		folders.addListener((ListChangeListener<ProjectFolder>) change -> refreshVisible());
		projectEntries.addListener((ListChangeListener<ProjectLibraryEntry>) change -> refreshVisible());
		rawRecordings.addListener((ListChangeListener<RecordingEntry>) change -> refreshVisible());
		meetings.addListener((ListChangeListener<MeetingLibraryEntry>) change -> refreshVisible());
	}
}
