package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

/**
 * Development harness that renders the editor shell to the UI gallery (plan §6.3). The same views
 * are snapshot by {@code EditorSnapshotTest} under TestFX; this main exists so the screenshots can
 * be produced from a plain classpath run while the module's other in-progress screens are red.
 *
 * <pre>{@code
 * DISPLAY=:0 java -cp "<classes>:<resources>:<deps>" dev.gravaai.ui.editor.EditorSnapshot [outDir]
 * }</pre>
 */
public final class EditorSnapshot {

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

	private EditorSnapshot() {}

	public static void main(String[] args) throws Exception {
		AppFonts.load();
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Path output = Path.of(args.length > 0 ? args[0] : "../parity/ui-java");
		Platform.startup(() -> {
			try {
				render(output, done);
			} catch (Throwable error) {
				failure.set(error);
				done.countDown();
			}
		});
		if (!done.await(60, TimeUnit.SECONDS)) {
			throw new IllegalStateException("editor snapshot timed out");
		}
		Platform.exit();
		if (failure.get() != null) {
			throw new IllegalStateException("editor snapshot failed", failure.get());
		}
	}

	private static void render(Path output, CountDownLatch done) {
		EditorStore store = new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark(FIXTURE_EDITOR)));
		EditorShellView view = new EditorShellView(store, new EditorViewActions() { },
				new IdlePlayback(6));
		view.header().setProjectName("Untitled Project");

		Scene scene = new Scene(new KitRoot(view), 1440, 1000);
		Theme.install(scene);
		EditorShellView.installStyles(scene);
		Stage stage = new Stage(javafx.stage.StageStyle.UNDECORATED);
		stage.setScene(scene);
		stage.show();
		view.requestFocus();

		PauseTransition settle = new PauseTransition(Duration.millis(900));
		settle.setOnFinished(event -> {
			try {
				write(scene, output.resolve("editor.png"));
				view.inspector().show(EditorSidebarRail.Section.SETTINGS);
				PauseTransition second = new PauseTransition(Duration.millis(400));
				second.setOnFinished(secondEvent -> {
					try {
						write(scene, output.resolve("editor-settings.png"));
						ShortcutsConfigDialog shortcuts = new ShortcutsConfigDialog(false,
								Shortcuts.defaults(), config -> { }, () -> { });
						shortcuts.show(view);
						PauseTransition third = new PauseTransition(Duration.millis(500));
						third.setOnFinished(thirdEvent -> {
							try {
								write(scene, output.resolve("editor-shortcuts.png"));
								shortcuts.close();
								TutorialHelpDialog tutorial = new TutorialHelpDialog();
								tutorial.show(view);
								PauseTransition fourth = new PauseTransition(Duration.millis(500));
								fourth.setOnFinished(fourthEvent -> {
									try {
										write(scene, output.resolve("editor-tutorial.png"));
										tutorial.dialog().close();
										Path thumbnail = bundledThumbnail();
										view.projectBrowser().setEntries(List.of(
												new ProjectBrowserDialog.Entry(
														Path.of("/home/user/recordings/Projects/"
																+ "UnboundMark - Board Demo 01.gravaai"),
														"UnboundMark - Board Demo 01",
														1_760_000_000_000L,
														thumbnail,
														false),
												new ProjectBrowserDialog.Entry(
														Path.of("/home/user/recordings/Projects/"
																+ "Untitled Project 1.gravaai"),
														"Untitled Project 1",
														1_759_000_000_000L, null, true),
												new ProjectBrowserDialog.Entry(
														Path.of("/home/user/recordings/Projects/"
																+ "Weekly standup.gravaai"),
														"Weekly standup",
														1_758_000_000_000L,
														thumbnail,
														false)));
										view.projectBrowser().open();
										PauseTransition fifth = new PauseTransition(
												Duration.millis(500));
										fifth.setOnFinished(fifthEvent -> {
											try {
												write(scene,
														output.resolve("editor-project-browser.png"));
												view.projectBrowser().close();
												AddCustomFontDialog addFont =
														view.openAddCustomFontDialog();
												PauseTransition sixth = new PauseTransition(
														Duration.millis(500));
												sixth.setOnFinished(sixthEvent -> {
													try {
														write(scene,
																output.resolve("editor-add-font.png"));
														addFont.dialog().close();
														view.announcementBanner().show(
																Announcements.parseFeed(
																		Json.parseJsonWithByteOrderMark(
																				DEMO_ANNOUNCEMENT))
																		.announcements());
														PauseTransition seventh =
																new PauseTransition(
																		Duration.millis(400));
														seventh.setOnFinished(seventhEvent -> {
															try {
																write(scene, output.resolve(
																		"editor-announcement.png"));
																System.out.println("wrote "
																		+ output.toAbsolutePath());
															} catch (IOException error) {
																error.printStackTrace();
															} finally {
																done.countDown();
															}
														});
														seventh.play();
													} catch (IOException error) {
														error.printStackTrace();
														done.countDown();
													}
												});
												sixth.play();
											} catch (IOException error) {
												error.printStackTrace();
												done.countDown();
											}
										});
										fifth.play();
									} catch (IOException error) {
										error.printStackTrace();
										done.countDown();
									}
								});
								fourth.play();
							} catch (IOException error) {
								error.printStackTrace();
								done.countDown();
							}
						});
						third.play();
					} catch (IOException error) {
						error.printStackTrace();
						done.countDown();
					}
				});
				second.play();
			} catch (IOException error) {
				error.printStackTrace();
				done.countDown();
			}
		});
		settle.play();
	}

	static void write(Scene scene, Path target) throws IOException {
		WritableImage image = scene.snapshot(null);
		BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
		PixelReader reader = image.getPixelReader();
		for (int y = 0; y < buffered.getHeight(); y++) {
			for (int x = 0; x < buffered.getWidth(); x++) {
				buffered.setRGB(x, y, reader.getArgb(x, y));
			}
		}
		Files.createDirectories(target.toAbsolutePath().getParent());
		ImageIO.write(buffered, "png", target.toFile());
	}

	/**
	 * A real project thumbnail for the gallery: the bundled Tahoe Light preview extracted to a
	 * temp file. The project browser takes a {@link Path}, and {@code public/} disappears in
	 * P3.6, so the classpath resource is the source of truth.
	 */
	static @Nullable Path bundledThumbnail() {
		String resource = "/ui/editor/wallpapers/previews/tahoe-light.jpg";
		try (java.io.InputStream stream = EditorSnapshot.class.getResourceAsStream(resource)) {
			if (stream == null) {
				return null;
			}
			Path file = Files.createTempFile("gravaai-project-thumbnail", ".jpg");
			file.toFile().deleteOnExit();
			Files.copy(stream, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return file;
		} catch (IOException error) {
			return null;
		}
	}

	/** Idle playback seam with a fixed duration, for screenshots. */
	static final class IdlePlayback implements EditorPlaybackPort {
		private final UiEvents<PlaybackState> changes = new UiEvents<>();
		private PlaybackState state;

		IdlePlayback(double durationSeconds) {
			state = new PlaybackState(false, 0, durationSeconds, 1);
		}

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
}
