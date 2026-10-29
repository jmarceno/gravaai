package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import dev.gravaai.services.meeting.MeetingJob;
import dev.gravaai.services.meeting.Subscription;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.ProjectThumbnailReady;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.ContentClamp;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Skeleton;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.kit.Toast;
import dev.gravaai.ui.kit.ToggleGroup;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The dashboard screen (port of {@code Dashboard.tsx} + {@code DashboardGrid.tsx} and the loading
 * skeletons). Structure: sidebar rail + the content card with the recoverable banner, announcement
 * carousel, search row, filters, the project/raw grid, and the Settings section.
 *
 * <p>Wiring contract for {@code gravaai-app}:
 * <pre>{@code
 * DashboardView view = new DashboardView(new DashboardDependencies(...));
 * KitRoot root = new KitRoot(view.ui());
 * Scene scene = new Scene(root);
 * Theme.install(scene);
 * view.refresh();
 * }</pre>
 * {@link #createScene(DashboardDependencies)} does that for the screenshot harness. The window
 * itself stays owned by {@code ui.windows.EditorWindow} (outside this package).
 */
public final class DashboardView {

	private static final Executor LOAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

	/** The dashboard stylesheet; the hosting window adds it with {@link #installStyles(Scene)}. */
	public static final String STYLESHEET = "/ui/dashboard/dashboard.css";

	private static final Map<String, String> SETTINGS_CATEGORIES = settingsCategories();

	private final DashboardDependencies dependencies;
	private final DashboardModel model;
	private final DashboardDataLoader loader;

	private final HBox shell = new HBox();
	private final VBox content = new VBox();
	private final RecoverableRecordingsBannerView banner;
	private final DashboardAnnouncementsView announcements;
	private final DashboardToolbar toolbar;
	private final DashboardFiltersBar filters;
	private final javafx.scene.control.Label errorLabel = new javafx.scene.control.Label();
	private final FlowPane grid = new FlowPane();
	private final VBox skeletonHost = new VBox();
	private final VBox emptyState = new VBox();
	private final StackPane gridHost = new StackPane();
	private final ScrollPane gridScroll;
	private final ScrollPane settingsScroll;
	private final VBox settingsContent = new VBox();
	private final ToggleGroup settingsTabs = new ToggleGroup(ToggleGroup.Mode.SINGLE);
	private final VBox settingsSectionsHost = new VBox();
	private final Map<String, List<Node>> settingsSections = new LinkedHashMap<>();
	private String activeSettingsCategory = "general";
	private boolean rawLoaded;
	private boolean meetingsLoaded;
	private @Nullable Subscription meetingJobSubscription;
	private @Nullable PauseTransition meetingsRefreshTimer;
	private @Nullable AutomationsSettingsView automationsView;
	private dev.gravaai.ui.agent.AgentSettingsView agentSettingsView;

	public DashboardView(DashboardDependencies dependencies) {
		this.dependencies = dependencies;
		this.model = new DashboardModel(dependencies);
		this.loader = new DashboardDataLoader(dependencies);
		this.banner = new RecoverableRecordingsBannerView(model, dependencies);
		this.announcements = new DashboardAnnouncementsView(dependencies);
		this.toolbar = new DashboardToolbar(model, dependencies);
		this.filters = new DashboardFiltersBar(model, dependencies);

		shell.getStyleClass().add("dashboard-shell");
		content.getStyleClass().add("dashboard-content");
		content.setSpacing(0);

		errorLabel.getStyleClass().add("dashboard-error");
		errorLabel.setWrapText(true);
		errorLabel.setVisible(false);
		errorLabel.setManaged(false);

		grid.getStyleClass().add("dashboard-grid");
		grid.setHgap(28);
		grid.setVgap(40);
		grid.setAlignment(Pos.TOP_LEFT);
		grid.setPadding(new Insets(0, 0, 10, 0));

		skeletonHost.getStyleClass().add("dashboard-grid");
		skeletonHost.setSpacing(40);
		skeletonHost.getChildren().add(buildSkeletonGrid());

		emptyState.getStyleClass().add("dashboard-empty");
		emptyState.setAlignment(Pos.CENTER);

		StackPane.setAlignment(grid, Pos.TOP_LEFT);
		gridHost.getChildren().addAll(grid, skeletonHost, emptyState);
		gridHost.setAlignment(Pos.TOP_LEFT);

		gridScroll = new ScrollPane(gridHost);
		gridScroll.getStyleClass().addAll("dashboard-grid-scroll", "custom-scrollbar");
		gridScroll.setFitToWidth(true);
		gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		VBox.setVgrow(gridScroll, Priority.ALWAYS);

		buildSettingsContent();
		settingsScroll = new ScrollPane(settingsContent);
		settingsScroll.getStyleClass().addAll("dashboard-settings-scroll", "custom-scrollbar");
		settingsScroll.setFitToWidth(true);
		settingsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		VBox.setVgrow(settingsScroll, Priority.ALWAYS);
		settingsScroll.setVisible(false);
		settingsScroll.setManaged(false);

		content.getChildren().addAll(banner, announcements, toolbar, filters, errorLabel, gridScroll,
				settingsScroll);
		HBox.setHgrow(content, Priority.ALWAYS);
		content.setMaxWidth(Double.MAX_VALUE);
		HBox.setMargin(content, new Insets(12, 12, 12, 0));
		shell.getChildren().addAll(new DashboardSidebar(model, dependencies), content);

		model.visibleEntries().addListener((ListChangeListener<ProjectLibraryEntry>) change ->
				rebuildGrid());
		model.loadingProperty().addListener((observable, oldValue, loading) -> rebuildGrid());
		model.selectingProperty().addListener((observable, oldValue, selecting) -> rebuildGrid());
		model.errorProperty().addListener((observable, oldValue, error) -> updateError());
		model.sectionProperty().addListener((observable, oldValue, section) -> {
			if (automationsView != null && !(section instanceof DashboardSection.Settings)) {
				// Leaving the page inside the debounce window must not drop the last edit.
				automationsView.flushPendingSave();
			}
			updateContentVisibility();
			boolean needsRaw = model.isRaw() && !rawLoaded;
			// useMeetingLibrary re-runs its effect (and refresh) on every re-entry to the section.
			boolean needsMeetings = model.isMeetings();
			if (needsRaw || needsMeetings) {
				refresh(model.isRaw() || rawLoaded, model.isMeetings() || meetingsLoaded);
			}
		});

		if (dependencies.globalShortcuts() != null) {
			addSettingsSection("shortcuts", new GlobalShortcutsSettingsView(dependencies.globalShortcuts()));
		}
		// Settings -> AI control: ui-kit's P3.2 page (replaces the old dashboard page; the old
		// AgentSettingsView/AgentSettingsController stay unused for compatibility).
		agentSettingsView = new dev.gravaai.ui.agent.AgentSettingsView(
				dependencies.agentUiServiceOrDefault());
		addSettingsSection("ai", agentSettingsView);
		if (dependencies.automations() != null) {
			automationsView = new AutomationsSettingsView(dependencies.automations());
			addSettingsSection("automations", automationsView);
		}
		// Settings -> Permissions: the Linux page of RecordingPermissionsSettings.tsx.
		addSettingsSection("permissions", new RecordingPermissionsSettings());

		rebuildGrid();
		updateContentVisibility();
		updateError();
	}

	/** The dashboard shell; wrap it in a {@link KitRoot} for the window scene. */
	public Node ui() {
		return shell;
	}

	public DashboardModel model() {
		return model;
	}

	/** Switches the dashboard to Settings and selects one category (for the app and screenshots). */
	public void showSettingsSection(String category) {
		activeSettingsCategory = category == null || category.isBlank() ? "general" : category;
		settingsTabs.setSelected(activeSettingsCategory);
		model.setSection(DashboardSection.SETTINGS);
		rebuildSettingsSections();
	}

	/** Adds the content of one settings category (General, Recording, Shortcuts, ...). */
	public void addSettingsSection(String category, Node node) {
		String key = category == null ? "general" : category;
		settingsSections.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
		if (key.equals(activeSettingsCategory)) {
			rebuildSettingsSections();
		}
	}

	/** Releases the settings pages' subscriptions (the hosting window calls this when closing). */
	public void dispose() {
		if (agentSettingsView != null) {
			agentSettingsView.close();
		}
		if (meetingJobSubscription != null) {
			meetingJobSubscription.close();
			meetingJobSubscription = null;
		}
		if (meetingsRefreshTimer != null) {
			meetingsRefreshTimer.stop();
		}
	}

	/** Reloads the library off the UI thread; completes on the FX thread. */
	public CompletableFuture<Void> refresh() {
		return refresh(model.isRaw() || model.isMeetings() || rawLoaded,
				model.isMeetings() || meetingsLoaded);
	}

	private CompletableFuture<Void> refresh(boolean includeRaw) {
		return refresh(includeRaw, false);
	}

	private CompletableFuture<Void> refresh(boolean includeRaw, boolean includeMeetings) {
		ensureMeetingJobSubscription();
		model.loadingProperty().set(true);
		model.errorProperty().set("");
		return CompletableFuture.runAsync(() -> {
			try {
				DashboardSnapshot snapshot = loader.load(includeRaw, includeMeetings,
						ready -> Platform.runLater(() -> applyThumbnailReady(ready)));
				Platform.runLater(() -> {
					model.applySnapshot(snapshot);
					if (includeRaw) {
						rawLoaded = true;
					}
					if (includeMeetings) {
						meetingsLoaded = true;
					}
					model.loadingProperty().set(false);
					rebuildGrid();
				});
			} catch (Exception error) {
				Platform.runLater(() -> {
					model.errorProperty().set("Could not load the library: " + message(error));
					model.loadingProperty().set(false);
					rebuildGrid();
				});
			}
		}, LOAD_EXECUTOR);
	}

	/**
	 * Port of the {@code onMeetingJobUpdate} listener in {@code useMeetingLibrary.ts}: status badges
	 * and titles change while jobs run, so refresh 400 ms after a job moves on (running progress
	 * events that are not on a 50% step are ignored). Subscribed lazily because AppContext creates
	 * the meetings service after the dashboard in the HUD startup path.
	 */
	private void ensureMeetingJobSubscription() {
		if (meetingJobSubscription != null) {
			return;
		}
		var service = dependencies.meetingService();
		if (service == null) {
			return;
		}
		meetingJobSubscription = service.onJobUpdate(job ->
				Platform.runLater(() -> onMeetingJobUpdate(job)));
	}

	private void onMeetingJobUpdate(MeetingJob job) {
		if (job == null || !model.isMeetings()) {
			return;
		}
		if (job.status() == MeetingJob.Status.RUNNING && job.progress() != null
				&& job.progress() % 50 != 0) {
			return;
		}
		if (meetingsRefreshTimer == null) {
			meetingsRefreshTimer = new PauseTransition(Duration.millis(400));
			meetingsRefreshTimer.setOnFinished(event ->
					refresh(model.isRaw() || rawLoaded, true));
		}
		meetingsRefreshTimer.playFromStart();
	}

	/** Screenshot harness: the same scene the editor window will host. */
	public static Scene createScene(DashboardDependencies dependencies) {
		DashboardView view = new DashboardView(dependencies);
		view.refresh();
		Scene scene = new Scene(new KitRoot(view.ui()), 1280, 800);
		Theme.install(scene);
		installStyles(scene);
		return scene;
	}

	/** Adds the dashboard stylesheet to a window scene (idempotent). */
	public static void installStyles(Scene scene) {
		var resource = DashboardView.class.getResource(STYLESHEET);
		if (resource == null) {
			throw new IllegalStateException("Missing dashboard stylesheet on the classpath: " + STYLESHEET);
		}
		String url = resource.toExternalForm();
		if (!scene.getStylesheets().contains(url)) {
			scene.getStylesheets().add(url);
		}
	}

	private void applyThumbnailReady(ProjectThumbnailReady ready) {
		List<ProjectLibraryEntry> entries = model.projectEntries();
		for (int index = 0; index < entries.size(); index++) {
			ProjectLibraryEntry entry = entries.get(index);
			if (entry.path().equals(ready.path())) {
				entries.set(index, new ProjectLibraryEntry(entry.path(), entry.name(), entry.createdAt(),
						entry.updatedAt(), ready.thumbnailPath(), entry.isCurrent(),
						entry.isInProjectsDirectory()));
				return;
			}
		}
	}

	private void rebuildGrid() {
		if (model.isSettings()) {
			return;
		}
		boolean loading = model.loadingProperty().get() && model.visibleEntries().isEmpty();
		boolean empty = !loading && model.visibleEntries().isEmpty();

		grid.getChildren().clear();
		for (ProjectLibraryEntry entry : model.visibleEntries()) {
			grid.getChildren().add(new ProjectCardView(model, dependencies, entry));
		}
		grid.setVisible(!loading && !empty);
		grid.setManaged(!loading && !empty);

		skeletonHost.setVisible(loading);
		skeletonHost.setManaged(loading);

		emptyState.setVisible(empty);
		emptyState.setManaged(empty);
		if (empty) {
			rebuildEmptyState();
		}
	}

	private void rebuildEmptyState() {
		emptyState.getChildren().clear();
		boolean projectsLibrary = !model.isRaw() && !model.isMeetings();
		boolean filtered = model.hasActiveFilters();
		Node artwork;
		if (projectsLibrary && !filtered) {
			artwork = new javafx.scene.control.Label("🦗");
			((javafx.scene.control.Label) artwork).getStyleClass().add("dashboard-empty__cricket");
		} else {
			Icon icon = Icon.of(Icons.ImageSquare, 32, "fill");
			icon.getStyleClass().add("dashboard-empty__icon");
			artwork = icon;
		}
		javafx.scene.control.Label message = new javafx.scene.control.Label(emptyMessage(projectsLibrary, filtered));
		message.getStyleClass().add("dashboard-empty__text");
		emptyState.getChildren().addAll(artwork, message);
		if (projectsLibrary && !filtered) {
			Button record = new Button("Record your first video", Button.Variant.DEFAULT, Button.Size.SM);
			record.setGraphic(Icon.of(Icons.Plus, 16));
			record.setGraphicTextGap(8);
			record.setOnAction(event -> dependencies.actions().showRecordingHud());
			emptyState.getChildren().add(record);
		}
		if (!model.query().isBlank()) {
			Button clear = new Button("Clear search", Button.Variant.GHOST, Button.Size.SM);
			clear.setOnAction(event -> model.setQuery(""));
			emptyState.getChildren().add(clear);
		}
		if (model.isRaw() && model.errorProperty().get() != null && !model.errorProperty().get().isBlank()) {
			Button retry = new Button("Retry", Button.Variant.SECONDARY, Button.Size.SM);
			retry.setOnAction(event -> refresh(true));
			emptyState.getChildren().add(retry);
		}
	}

	private String emptyMessage(boolean projectsLibrary, boolean filtered) {
		if (model.isMeetings()) {
			// DashboardGrid.tsx: meetingError || (hasActiveFilters ? ... : ...).
			if (model.meetingsError() != null && !model.meetingsError().isBlank()) {
				return model.meetingsError();
			}
			return filtered
					? "No matching meetings"
					: "No meetings yet. Turn on meeting mode in the recorder, or import a recording.";
		}
		if (model.isRaw()) {
			return filtered ? "No matching raw files" : "No raw recordings yet";
		}
		return filtered ? "No matching projects" : "It's looking empty in here...";
	}

	private void updateError() {
		String error = model.errorProperty().get();
		boolean visible = error != null && !error.isBlank();
		errorLabel.setText(visible ? error : "");
		errorLabel.setVisible(visible);
		errorLabel.setManaged(visible);
		if (visible && shell.getScene() != null) {
			Toast.show(shell, error).variant(Toast.Variant.DANGER);
		}
	}

	private void updateContentVisibility() {
		boolean settings = model.isSettings();
		settingsScroll.setVisible(settings);
		settingsScroll.setManaged(settings);
		gridScroll.setVisible(!settings);
		gridScroll.setManaged(!settings);
		boolean hasRecoverable = !model.recoverableRecordings().isEmpty();
		banner.setVisible(!settings && hasRecoverable);
		banner.setManaged(!settings && hasRecoverable);
		announcements.setVisible(!settings && announcements.enabled());
		announcements.setManaged(!settings && announcements.enabled());
		toolbar.setVisible(!settings);
		toolbar.setManaged(!settings);
		filters.setVisible(!settings);
		filters.setManaged(!settings);
		announcements.setVisible(!settings && announcements.enabled());
		announcements.setManaged(!settings && announcements.enabled());
		if (!settings) {
			rebuildGrid();
		} else {
			rebuildSettingsSections();
		}
	}

	private void buildSettingsContent() {
		settingsContent.getStyleClass().add("dashboard-settings");
		Label title = new Label("Settings");
		title.getStyleClass().add("dashboard-settings__title");
		settingsTabs.getStyleClass().add("dashboard-settings__tabs");
		for (Map.Entry<String, String> category : SETTINGS_CATEGORIES.entrySet()) {
			settingsTabs.item(category.getKey(), category.getValue());
		}
		settingsTabs.setSelected("general");
		settingsTabs.onValueChange(value -> {
			activeSettingsCategory = value == null || value.isBlank() ? "general" : value;
			rebuildSettingsSections();
		});
		settingsSectionsHost.getStyleClass().add("dashboard-settings__sections");
		settingsSectionsHost.setSpacing(24);
		settingsContent.getChildren().addAll(title, settingsTabs, settingsSectionsHost);
		rebuildSettingsSections();
	}

	private void rebuildSettingsSections() {
		settingsSectionsHost.getChildren().clear();
		List<Node> injected = settingsSections.getOrDefault(activeSettingsCategory, List.of());
		settingsSectionsHost.getChildren().addAll(injected);
		if ("meetings".equals(activeSettingsCategory) && injected.isEmpty()) {
			// The meeting service is created after the dashboard; build its settings on first visit.
			dev.gravaai.ui.meeting.MeetingUiService meetings = dependencies.meetingService();
			if (meetings != null) {
				addSettingsSection("meetings", new dev.gravaai.ui.meeting.MeetingSettingsView(meetings));
				return;
			}
			javafx.scene.control.Label unavailable = new javafx.scene.control.Label(
					"Meeting settings are loading. Open this tab again in a moment.");
			unavailable.getStyleClass().add("dashboard-settings__hint");
			settingsSectionsHost.getChildren().add(unavailable);
		}
		if ("files".equals(activeSettingsCategory)) {
			settingsSectionsHost.getChildren().addAll(buildFilesSettings());
		}
	}

	private List<Node> buildFilesSettings() {
		List<Node> rows = new ArrayList<>();
		String projectsDir = model.projectsDir() == null ? "" : model.projectsDir().toString();
		Button showFolder = new Button("Show folder", Button.Variant.SECONDARY, Button.Size.SM);
		showFolder.setOnAction(event -> {
			if (model.projectsDir() != null) {
				dependencies.actions().revealInFolder(model.projectsDir().toString());
			}
		});
		rows.add(new SettingsRow("Projects folder", projectsDir, showFolder));
		Button openFile = new Button("Open file", Button.Variant.SECONDARY, Button.Size.SM);
		openFile.setOnAction(event -> dependencies.actions().importFile());
		rows.add(new SettingsRow("Open video or project", "", openFile));
		javafx.scene.control.Label hint = new javafx.scene.control.Label(
				"Named projects save automatically. Previews refresh when you return to Projects.");
		hint.getStyleClass().add("dashboard-settings__hint");
		hint.setWrapText(true);
		rows.add(hint);
		return rows;
	}

	private VBox buildSkeletonGrid() {
		VBox host = new VBox(16);
		for (int index = 0; index < 3; index++) {
			VBox cell = new VBox(16);
			Skeleton thumbnail = new Skeleton(278, 209);
			thumbnail.getStyleClass().add("skeleton--thumbnail");
			HBox caption = new HBox(12);
			Skeleton avatar = new Skeleton(48, 48);
			avatar.getStyleClass().add("skeleton--round");
			VBox lines = new VBox(8);
			lines.getChildren().addAll(new Skeleton(180, 16), new Skeleton(90, 12));
			caption.getChildren().addAll(avatar, lines);
			cell.getChildren().addAll(thumbnail, caption);
			host.getChildren().add(cell);
		}
		return host;
	}

	private static Map<String, String> settingsCategories() {
		Map<String, String> categories = new LinkedHashMap<>();
		categories.put("general", "General");
		categories.put("permissions", "Permissions");
		categories.put("motion", "Motion");
		categories.put("recording", "Recording");
		categories.put("shortcuts", "Shortcuts");
		categories.put("meetings", "Meetings");
		categories.put("automations", "Automations");
		categories.put("ai", "AI control");
		categories.put("files", "Files");
		return java.util.Collections.unmodifiableMap(categories);
	}

	private static String message(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
	}
}