package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.event.UiEvents;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.ui.AppFonts;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/**
 * Renders the editor shell for the UI gallery (plan §6.3, P2.6). Writes
 * {@code java/parity/ui-java/editor.png} and {@code editor-settings.png} at the same 1440x1000
 * size as the React references {@code java/parity/ui/editor*.png}.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
@ExtendWith(ApplicationExtension.class)
class EditorSnapshotTest {

	private static final String FIXTURE_EDITOR = """
			{
			  "wallpaper": "/wallpapers/tahoe-light.jpg",
			  "shadowIntensity": 0.67,
			  "backgroundBlur": 0,
			  "aspectRatio": "16:9",
			  "borderRadius": 0,
			  "padding": {"top": 20, "bottom": 20, "left": 20, "right": 20, "linked": true},
			  "clipRegions": [{"id": "clip-1", "startMs": 0, "endMs": 6000, "speed": 1}]
			}
			""";

	private static final String DEMO_ANNOUNCEMENT =
			"{\"announcements\":[{\"id\":\"demo\",\"title\":\"New in Gravaai\","
					+ "\"body\":\"Background blur and click effects are here.\","
					+ "\"presentation\":\"banner\",\"audience\":\"editor\",\"priority\":10,"
					+ "\"displayDurationSeconds\":30,"
					+ "\"controls\":{\"close\":true,\"action\":true}}]}";

	private Stage stage;
	private EditorShellView shell;

	@BeforeAll
	static void loadFonts() {
		AppFonts.load();
	}

	/** TestFX starts the toolkit; the snapshot tests create their own undecorated stage. */
	@Start
	void start(javafx.stage.Stage testFxStage) {
		// nothing to wire: the real stage is created per test for the 1440x1000 snapshot
	}

	@AfterEach
	void tearDown() {
		if (shell != null) {
			shell.dispose();
		}
		if (stage != null) {
			javafx.application.Platform.runLater(stage::close);
		}
	}

	@Test
	@DisplayName("renders the editor shell and its settings panel for the UI gallery")
	void rendersEditorShell() throws Exception {
		CompletableFuture<Path> snapshot = new CompletableFuture<>();
		Platform.runLater(() -> render(snapshot));
		Path editor = snapshot.get(90, TimeUnit.SECONDS);

		BufferedImage image = ImageIO.read(editor.toFile());
		assertNotNull(image);
		assertEquals(1440, image.getWidth());
		assertEquals(1000, image.getHeight());
		assertTrue(Files.isRegularFile(editor.resolveSibling("editor-settings.png")));
	}

	@Test
	@DisplayName("renders the project browser, add-font dialog and announcement banner")
	void rendersDialogGallery() throws Exception {
		CompletableFuture<Path> snapshot = new CompletableFuture<>();
		Platform.runLater(() -> renderDialogs(snapshot));
		Path browser = snapshot.get(90, TimeUnit.SECONDS);

		BufferedImage image = ImageIO.read(browser.toFile());
		assertNotNull(image);
		assertEquals(1440, image.getWidth());
		assertEquals(1000, image.getHeight());
		assertTrue(Files.isRegularFile(browser.resolveSibling("editor-add-font.png")));
		assertTrue(Files.isRegularFile(browser.resolveSibling("editor-announcement.png")));
	}

	private static Path projectThumbnail() {
		return EditorSnapshot.bundledThumbnail();
	}

	private void renderDialogs(CompletableFuture<Path> result) {
		try {
			EditorStore store = new EditorStore(ProjectPersistence.normalizeProjectEditor(
					Json.parseJsonWithByteOrderMark(FIXTURE_EDITOR)));
			EditorShellView view = new EditorShellView(store, new EditorViewActions() { },
					new FakePlayback());
			view.header().setProjectName("Untitled Project");
			this.shell = view;
			Scene scene = new Scene(new KitRoot(view), 1440, 1000);
			Theme.install(scene);
			EditorShellView.installStyles(scene);
			Stage window = new Stage(javafx.stage.StageStyle.UNDECORATED);
			this.stage = window;
			window.setScene(scene);
			window.show();
			view.requestFocus();

			Path thumbnail = projectThumbnail();
			view.projectBrowser().setEntries(List.of(
					new ProjectBrowserDialog.Entry(Path.of("/home/user/recordings/Projects/"
							+ "UnboundMark - Board Demo 01.gravaai"),
							"UnboundMark - Board Demo 01", 1_760_000_000_000L, thumbnail, false),
					new ProjectBrowserDialog.Entry(Path.of("/home/user/recordings/Projects/"
							+ "Untitled Project 1.gravaai"), "Untitled Project 1",
							1_759_000_000_000L, null, true),
					new ProjectBrowserDialog.Entry(Path.of("/home/user/recordings/Projects/"
							+ "Weekly standup.gravaai"), "Weekly standup", 1_758_000_000_000L,
							thumbnail, false)));

			PauseTransition settle = new PauseTransition(Duration.millis(900));
			settle.setOnFinished(event -> {
				try {
					view.projectBrowser().open();
					PauseTransition browserSettle = new PauseTransition(Duration.millis(500));
					browserSettle.setOnFinished(browserEvent -> {
						try {
							Path browserPng =
									writeSnapshot(scene, "editor-project-browser.png");
							view.projectBrowser().close();
							AddCustomFontDialog addFont = view.openAddCustomFontDialog();
							PauseTransition fontSettle = new PauseTransition(
									Duration.millis(500));
							fontSettle.setOnFinished(fontEvent -> {
								try {
									writeSnapshot(scene, "editor-add-font.png");
									addFont.dialog().close();
									view.announcementBanner().show(Announcements.parseFeed(
											Json.parseJsonWithByteOrderMark(DEMO_ANNOUNCEMENT))
											.announcements());
									PauseTransition bannerSettle = new PauseTransition(
											Duration.millis(400));
									bannerSettle.setOnFinished(bannerEvent2 -> {
										try {
											writeSnapshot(scene, "editor-announcement.png");
											CropEditorDialog crop = new CropEditorDialog(
													new dev.gravaai.core.project.CropRegion(
															0.1, 0.15, 0.7, 0.6),
													1920, 1080, region -> { });
											if (thumbnail != null) {
												crop.setFrame(new javafx.scene.image.Image(
														thumbnail.toUri().toString()));
											}
											crop.show(view);
											PauseTransition cropSettle = new PauseTransition(
													Duration.millis(400));
											cropSettle.setOnFinished(cropEvent -> {
												try {
													writeSnapshot(scene, "editor-crop.png");
													crop.dialog().close();
													result.complete(browserPng);
												} catch (Exception failure) {
													result.completeExceptionally(failure);
												}
											});
											cropSettle.play();
										} catch (Exception failure) {
											result.completeExceptionally(failure);
										}
									});
									bannerSettle.play();
								} catch (Exception failure) {
									result.completeExceptionally(failure);
								}
							});
							fontSettle.play();
						} catch (Exception failure) {
							result.completeExceptionally(failure);
						}
					});
					browserSettle.play();
				} catch (Exception failure) {
					result.completeExceptionally(failure);
				}
			});
			settle.play();
		} catch (RuntimeException error) {
			result.completeExceptionally(error);
		}
	}


	private static final String ANNOTATION_FIXTURE = """
			{
			  "wallpaper": "/wallpapers/tahoe-light.jpg",
			  "shadowIntensity": 0.67,
			  "backgroundBlur": 0,
			  "aspectRatio": "16:9",
			  "borderRadius": 0,
			  "padding": {"top": 20, "bottom": 20, "left": 20, "right": 20, "linked": true},
			  "annotationRegions": [{"id": "annotation-1", "startMs": 0, "endMs": 6000,
			    "type": "text", "content": "Hello annotation", "textContent": "Hello annotation",
			    "position": {"x": 50, "y": 50}, "size": {"width": 30, "height": 20}, "zIndex": 0}]
			}
			""";

	@Test
	@DisplayName("renders the annotation appearance settings panel")
	void rendersAnnotationSettings() throws Exception {
		CompletableFuture<Path> snapshot = new CompletableFuture<>();
		Platform.runLater(() -> renderAnnotationSettings(snapshot));
		Path png = snapshot.get(90, TimeUnit.SECONDS);
		BufferedImage image = ImageIO.read(png.toFile());
		assertNotNull(image);
		assertEquals(1440, image.getWidth());
		assertEquals(1000, image.getHeight());
	}

	private void renderAnnotationSettings(CompletableFuture<Path> result) {
		try {
			EditorStore store = new EditorStore(ProjectPersistence.normalizeProjectEditor(
					Json.parseJsonWithByteOrderMark(ANNOTATION_FIXTURE)));
			store.setSelectedAnnotationId("annotation-1");
			EditorShellView view = new EditorShellView(store, new EditorViewActions() { },
					new FakePlayback());
			view.header().setProjectName("Untitled Project");
			view.inspector().showAnnotationSettings();
			this.shell = view;
			Scene scene = new Scene(new KitRoot(view), 1440, 1000);
			Theme.install(scene);
			EditorShellView.installStyles(scene);
			Stage window = new Stage(javafx.stage.StageStyle.UNDECORATED);
			this.stage = window;
			window.setScene(scene);
			window.show();
			view.requestFocus();
			PauseTransition settle = new PauseTransition(Duration.millis(1200));
			settle.setOnFinished(event -> {
				try {
					result.complete(writeSnapshot(scene, "editor-annotation-settings.png"));
				} catch (Exception failure) {
					result.completeExceptionally(failure);
				}
			});
			settle.play();
		} catch (RuntimeException error) {
			result.completeExceptionally(error);
		}
	}

	private void render(CompletableFuture<Path> result) {
		try {
			EditorStore store = new EditorStore(ProjectPersistence.normalizeProjectEditor(
					Json.parseJsonWithByteOrderMark(FIXTURE_EDITOR)));
			FakePlayback playback = new FakePlayback();
			EditorShellView view = new EditorShellView(store, new EditorViewActions() { }, playback);
			view.header().setProjectName("Untitled Project");
			this.shell = view;

			Scene scene = new Scene(new KitRoot(view), 1440, 1000);
			Theme.install(scene);
			EditorShellView.installStyles(scene);
			Stage window = new Stage(javafx.stage.StageStyle.UNDECORATED);
			this.stage = window;
			window.setScene(scene);
			window.show();
			view.requestFocus();

			PauseTransition settle = new PauseTransition(Duration.millis(900));
			settle.setOnFinished(event -> {
				try {
					Path editor = writeSnapshot(scene, "editor.png");
					view.inspector().show(EditorSidebarRail.Section.SETTINGS);
					PauseTransition settingsSettle = new PauseTransition(Duration.millis(400));
					settingsSettle.setOnFinished(settingsEvent -> {
						try {
							writeSnapshot(scene, "editor-settings.png");
							view.setCaptionModelService(new FakeCaptionModels());
							view.inspector().show(EditorSidebarRail.Section.CAPTIONS);
							view.inspector().applyCss();
							view.inspector().layout();
							writeSnapshot(scene, "editor-captions.png");
							result.complete(editor);
						} catch (Exception failure) {
							result.completeExceptionally(failure);
						}
					});
					settingsSettle.play();
				} catch (Exception failure) {
					result.completeExceptionally(failure);
				}
			});
			settle.play();
		} catch (RuntimeException error) {
			result.completeExceptionally(error);
		}
	}

	private static Path writeSnapshot(Scene scene, String fileName) throws IOException {
		WritableImage image = scene.snapshot(null);
		BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
		PixelReader reader = image.getPixelReader();
		for (int y = 0; y < buffered.getHeight(); y++) {
			for (int x = 0; x < buffered.getWidth(); x++) {
				buffered.setRGB(x, y, reader.getArgb(x, y));
			}
		}
		String override = System.getProperty("gravaai.snapshot.dir");
		Path target = override != null && !override.isBlank()
				? Path.of(override).resolve(fileName)
				: Path.of("..", "parity", "ui-java").resolve(fileName);
		Files.createDirectories(target.toAbsolutePath().getParent());
		ImageIO.write(buffered, "png", target.toFile());
		return target;
	}

	/** In-memory playback seam: 6 s timeline at 0:00, like the reference screenshot. */
	private static final class FakePlayback implements EditorPlaybackPort {
		private final UiEvents<PlaybackState> changes = new UiEvents<>();
		private PlaybackState state = new PlaybackState(false, 0, 6, 1);

		@Override
		public PlaybackState state() {
			return state;
		}

		@Override
		public UiEvents<PlaybackState> changes() {
			return changes;
		}

		@Override
		public void togglePlayPause() {
			state = new PlaybackState(!state.isPlaying(), state.currentTimeSeconds(),
					state.durationSeconds(), state.volume());
			changes.publish(state);
		}

		@Override
		public void seek(double seconds) {
			state = new PlaybackState(state.isPlaying(), seconds, state.durationSeconds(),
					state.volume());
			changes.publish(state);
		}

		@Override
		public void skip(double deltaSeconds) {
			seek(Math.max(0, state.currentTimeSeconds() + deltaSeconds));
		}

		@Override
		public void setVolume(double volume) {
			state = new PlaybackState(state.isPlaying(), state.currentTimeSeconds(),
					state.durationSeconds(), volume);
			changes.publish(state);
		}
	}

	private static final class FakeCaptionModels
			implements dev.gravaai.ui.editor.transcript.CaptionModelUiService {
		@Override
		public State state() {
			return new State("small", null, DownloadStatus.DOWNLOADING, 42, false);
		}

		@Override
		public AutoCloseable addListener(java.util.function.Consumer<State> listener) {
			return () -> { };
		}

		@Override
		public void selectModel(String modelId) {
		}

		@Override
		public void downloadSmallModel() {
		}

		@Override
		public void deleteSmallModel() {
		}

		@Override
		public void pickCustomModel() {
		}

		@Override
		public void generateCaptions() {
		}

		@Override
		public dev.gravaai.ui.editor.transcript.TranscriptUiService transcript() {
			return null;
		}
	}
}
