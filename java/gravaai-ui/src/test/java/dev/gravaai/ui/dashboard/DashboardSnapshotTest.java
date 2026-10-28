package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.RecentProjectsStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.platform.trash.Trash;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RecordingLibraryScan;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Renders the dashboard against a scratch profile and snapshots it for the UI gallery (plan §6.3).
 *
 * <p>Needs a display, so it only runs when {@code DISPLAY} is set (Lead decision for task-9); the
 * packaged app screenshot is produced by {@code gravaai-app --dashboard} instead. The PNG lands in
 * the module target directory, never in the repository.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class DashboardSnapshotTest {

	@TempDir
	Path tempDir;

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			// TestFX (ApplicationExtension) may have started the toolkit earlier in the same test
			// JVM; the dashboard snapshot only needs a running toolkit.
			Platform.runLater(() -> { });
		}
		// Keep the toolkit alive after this class closes its stage: later test classes in the same
		// surefire fork (DashboardMeetingsUiTest, MeetingLibraryViewTest, ...) reuse it.
		Platform.setImplicitExit(false);
	}

	@Test
	@DisplayName("renders the dashboard scene for the UI gallery")
	void rendersDashboardScene() throws Exception {
		DashboardDependencies dependencies = scratchProfile();
		CompletableFuture<Path> snapshot = new CompletableFuture<>();
		Platform.runLater(() -> render(dependencies, snapshot));
		Path png = snapshot.get(90, TimeUnit.SECONDS);

		assertTrue(Files.isRegularFile(png), "snapshot missing: " + png);
		BufferedImage image = ImageIO.read(png.toFile());
		assertNotNull(image);
		assertTrue(image.getWidth() >= 1000 && image.getHeight() >= 700,
				"unexpected snapshot size " + image.getWidth() + "x" + image.getHeight());
	}

	private void render(DashboardDependencies dependencies, CompletableFuture<Path> result) {
		try {
			DashboardView view = new DashboardView(dependencies);
			CompletableFuture<Void> loaded = view.refresh();
			Scene scene = new Scene(new KitRoot(view.ui()), 1280, 800);
			Theme.install(scene);
			DashboardView.installStyles(scene);
			Stage stage = new Stage();
			stage.setScene(scene);
			stage.show();
			loaded.whenComplete((ignored, error) -> {
				if (error != null) {
					result.completeExceptionally(error);
					stage.close();
					return;
				}
				PauseTransition settle = new PauseTransition(Duration.millis(700));
				settle.setOnFinished(event -> {
					try {
						Path main = writeSnapshot(scene, "dashboard.png");
						List<ProjectLibraryEntry> entries = view.model().visibleEntries();
						DashboardDeleteDialog.show(view.ui(),
								List.of(entries.get(0).path()), deleteRecordings -> { });
						PauseTransition dialogSettle = new PauseTransition(Duration.millis(400));
						dialogSettle.setOnFinished(dialogEvent -> {
							try {
								writeSnapshot(scene, "dashboard-delete-dialog.png");
							} catch (Exception failure) {
								result.completeExceptionally(failure);
								stage.close();
								return;
							}
							// Close the dialog, then show Settings -> Shortcuts.
							KitLauncher.closeDialogs(scene);
							view.showSettingsSection("shortcuts");
							PauseTransition settingsSettle = new PauseTransition(Duration.millis(500));
							settingsSettle.setOnFinished(settingsEvent -> {
								try {
									writeSnapshot(scene, "dashboard-shortcuts-settings.png");
								} catch (Exception failure) {
									result.completeExceptionally(failure);
									stage.close();
									return;
								}
								view.showSettingsSection("ai");
								PauseTransition aiSettle = new PauseTransition(Duration.millis(500));
								aiSettle.setOnFinished(aiEvent -> {
									try {
										writeSnapshot(scene, "dashboard-ai-settings.png");
									} catch (Exception failure) {
										result.completeExceptionally(failure);
										stage.close();
										return;
									}
									view.showSettingsSection("automations");
									PauseTransition automationsSettle =
											new PauseTransition(Duration.millis(600));
									automationsSettle.setOnFinished(automationsEvent -> {
										try {
											writeSnapshot(scene, "dashboard-automations-settings.png");
											result.complete(main);
										} catch (Exception failure) {
											result.completeExceptionally(failure);
										} finally {
											stage.close();
										}
									});
									automationsSettle.play();
								});
								aiSettle.play();
							});
							settingsSettle.play();
						});
						dialogSettle.play();
					} catch (Exception failure) {
						result.completeExceptionally(failure);
						stage.close();
					}
				});
				settle.play();
			});
		} catch (RuntimeException error) {
			result.completeExceptionally(error);
		}
	}

	private Path writeSnapshot(Scene scene, String fileName) throws IOException {
		WritableImage image = scene.snapshot(null);
		BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
		PixelReader reader = image.getPixelReader();
		for (int y = 0; y < buffered.getHeight(); y++) {
			for (int x = 0; x < buffered.getWidth(); x++) {
				buffered.setRGB(x, y, reader.getArgb(x, y));
			}
		}
		String override = System.getProperty("gravaai.screenshot.dir");
		Path target = override != null && !override.isBlank()
				? Path.of(override).resolve(fileName)
				: Path.of(System.getProperty("java.io.tmpdir"), "gravaai-ui-screenshots", fileName);
		Files.createDirectories(target.getParent());
		ImageIO.write(buffered, "png", target.toFile());
		return target;
	}

	private DashboardDependencies scratchProfile() throws Exception {
		UserData userData = new UserData(tempDir.resolve("Gravaai"));
		RecordingsDirectory recordingsDirectory = new RecordingsDirectory(userData);
		byte[] poster = posterPng();
		ProjectManager projects = new ProjectManager(userData, recordingsDirectory,
				new RecentProjectsStore(userData.recentProjectsFile()), source -> poster);
		Path recordingsDir = Files.createDirectories(userData.recordingsDir());
		long now = System.currentTimeMillis();
		Path video = recordingsDir.resolve("recording-1.mp4");
		Files.writeString(video, "video");
		Path launch = writeProject(projects, "Launch demo", video, now - 2 * 3_600_000L);
		writeProject(projects, "Team sync", video, now - 26 * 3_600_000L);
		writeProject(projects, "Retro notes", video, now - 3 * 86_400_000L);
		// One folder so the sidebar folder row and the card chips appear in the gallery.
		new DashboardLibraryStore(userData).save(new DashboardLibraryData(
				java.util.List.of(new ProjectFolder("folder-1", "Work", "#de85ac",
						java.util.List.of(launch.toAbsolutePath().normalize().toString()))),
				java.util.List.of(), java.util.Map.of(), java.util.List.of("#a68ccc")));
		Files.writeString(recordingsDir.resolve("recording-2.mkv"), "video");
		Files.writeString(recordingsDir.resolve("music.wav"), "audio");
		return new DashboardDependencies(
				userData,
				projects,
				new RecordingLibraryScan(recordingsDirectory),
				List::of,
				new DashboardLibraryStore(userData),
				new Trash(tempDir.resolve("data"), tempDir.resolve("home")),
				new NoopActions(),
				fakeShortcuts(),
				fakeAgent(),
				fakeAutomations());
	}

	private static Path writeProject(ProjectManager projects, String name, Path video, long updatedAt)
			throws IOException {
		Path project = projects.getProjectsDir().resolve(name + ".gravaai");
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("version", 2);
		data.put("videoPath", video.toString());
		data.put("editor", new LinkedHashMap<>());
		Json.writeFile(project, data);
		Files.setLastModifiedTime(project, FileTime.fromMillis(updatedAt));
		return project;
	}

	private static byte[] posterPng() throws IOException {
		BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int red = 24 + (x * 60 / image.getWidth());
				int green = 30 + (y * 80 / image.getHeight());
				int blue = 48 + ((x + y) * 60 / (image.getWidth() + image.getHeight()));
				image.setRGB(x, y, (red << 16) | (green << 8) | blue);
			}
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", bytes);
		return bytes.toByteArray();
	}

	/** Closes any kit dialog layer children (the snapshot test opens one for the gallery). */
	private static final class KitLauncher {
		static void closeDialogs(Scene scene) {
			if (scene.getRoot() instanceof KitRoot root) {
				root.dialogLayer().getChildren().clear();
			}
		}
	}

	private static AgentSettingsController fakeAgent() {
		return new AgentSettingsController() {
			private State state = new State(true, List.of(new Client("Claude Code")),
					"\"/opt/gravaai/gravaai\" --mcp",
					"{\n  \"mcpServers\": {\n    \"gravaai\": {\n      \"command\": \"/opt/gravaai/gravaai\",\n      \"args\": [\"--mcp\"]\n    }\n  }\n}");

			@Override
			public State state() {
				return state;
			}

			@Override
			public State setEnabled(boolean enabled) {
				return state;
			}

			@Override
			public State regenerateToken() {
				return state;
			}
		};
	}

	private static AutomationsController fakeAutomations() {
		dev.gravaai.services.automations.AutomationRule stored =
				new dev.gravaai.services.automations.AutomationRule(
						"11111111-1111-1111-1111-111111111111",
						"Copy recording path",
						true,
						dev.gravaai.services.automations.AutomationTrigger.RECORDING_SAVED,
						java.util.List.of(new dev.gravaai.services.automations.AutomationCondition.DurationAtLeast(60)),
						java.util.List.of(new dev.gravaai.services.automations.AutomationAction.CopyPath()));
		dev.gravaai.services.automations.AutomationTemplate template =
				dev.gravaai.services.automations.Automations.AUTOMATION_TEMPLATES.get(2);
		dev.gravaai.services.automations.AutomationRule fromTemplate =
				new dev.gravaai.services.automations.AutomationRule(
						"22222222-2222-2222-2222-222222222222",
						template.rule().name(), template.rule().enabled(), template.rule().trigger(),
						template.rule().conditions(), template.rule().actions());
		return new AutomationsController() {
			private dev.gravaai.services.automations.AutomationsFile file =
					new dev.gravaai.services.automations.AutomationsFile(1,
							java.util.List.of(stored, fromTemplate));

			@Override
			public dev.gravaai.services.automations.AutomationsFile getAutomations() {
				return file;
			}

			@Override
			public dev.gravaai.core.result.Result<dev.gravaai.services.automations.AutomationsFile>
					saveAutomations(tools.jackson.databind.JsonNode value) {
				file = dev.gravaai.services.automations.Automations.normalizeAutomationsFile(value);
				return dev.gravaai.core.result.Result.ok(file);
			}

			@Override
			public dev.gravaai.core.result.Result<dev.gravaai.services.automations.AutomationRunResult>
					runAutomationOnFile(String ruleId, String samplePath, String notesPath) {
				return dev.gravaai.core.result.Result.err("not run in the snapshot test");
			}

			@Override
			public java.nio.file.Path chooseFolder(String currentFolder) {
				return null;
			}

			@Override
			public java.nio.file.Path chooseSampleFile() {
				return null;
			}
		};
	}

	private static GlobalShortcutsController fakeShortcuts() {
		return new GlobalShortcutsController() {
			private dev.gravaai.services.shortcuts.GlobalShortcutsConfig config =
					dev.gravaai.services.shortcuts.GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS;

			@Override
			public dev.gravaai.services.shortcuts.GlobalShortcutsConfig readConfig() {
				return config;
			}

			@Override
			public dev.gravaai.services.shortcuts.GlobalShortcutsStatus status() {
				return new dev.gravaai.services.shortcuts.GlobalShortcutsStatus(
						dev.gravaai.services.shortcuts.GlobalShortcutsStatus.Backend.COSMIC,
						java.util.Map.of(), java.util.Map.of(), null, java.util.Map.of());
			}

			@Override
			public dev.gravaai.services.shortcuts.GlobalShortcutsStatus apply(
					dev.gravaai.services.shortcuts.GlobalShortcutsConfig next) {
				config = next;
				return status();
			}

			@Override
			public boolean cosmicDesktop() {
				return true;
			}
		};
	}

	/** The app shell wires real navigation; the snapshot test does not navigate. */
	private static final class NoopActions implements DashboardDependencies.Actions {
		@Override
		public void openProject(String path) {
		}

		@Override
		public void openRecording(String path) {
		}

		@Override
		public void importFile() {
		}

		@Override
		public void revealInFolder(String path) {
		}

		@Override
		public void showRecordingHud() {
		}

		@Override
		public void openExternalUrl(String url) {
		}

		@Override
		public void recoverRecording(dev.gravaai.services.projects.RecoverableRecording recording) {
		}

		@Override
		public void discardRecording(dev.gravaai.services.projects.RecoverableRecording recording) {
		}
	}
}