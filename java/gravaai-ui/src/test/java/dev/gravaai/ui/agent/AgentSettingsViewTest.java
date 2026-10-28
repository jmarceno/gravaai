package dev.gravaai.ui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import dev.gravaai.ui.AppFonts;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/** Settings -> AI control page (P3.2): content, token reveal, toggle and the parity snapshot. */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
@ExtendWith(ApplicationExtension.class)
class AgentSettingsViewTest {

	private PlaceholderAgentUiService service;
	private AgentSettingsView view;
	private Scene scene;
	private StackPane background;

	@BeforeAll
	static void loadFonts() {
		AppFonts.load();
	}

	@Start
	void start(Stage stage) {
		service = new PlaceholderAgentUiService();
		view = new AgentSettingsView(service);
		background = new StackPane();
		background.setStyle("-fx-background-color: #121418; -fx-padding: 48 72 48 72;");
		ScrollPane scroll = new ScrollPane(view);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
		background.getChildren().add(scroll);
		scene = new Scene(new KitRoot(background), 1440, 1000);
		Theme.install(scene);
		AgentSettingsView.installStyles(scene);
		stage.setScene(scene);
		stage.show();
	}

	@DisplayName("the page renders status, commands, token and recent activity")
	@Test
	void content() throws Exception {
		onFx(() -> {
			assertNotNull(view.currentSnapshot());
			assertTrue(view.statusLabel().getText().contains("connected: Claude Code"));
			assertEquals("gravaai --mcp --ozone-platform=headless", view.mcpCommandValue().getText());
			assertTrue(view.tokenLabel().getText().contains("•"));
			assertFalse(view.tokenLabel().getText().equals(view.currentSnapshot().token()));
			assertTrue(view.activityRowsBox().getChildren().size() >= 3);
			assertTrue(view.enabledSwitch().isChecked());
			assertTrue(view.chip().isVisible());
		});
	}

	@DisplayName("reveal shows the token and regenerating rotates it")
	@Test
	void tokenRevealAndRegenerate() throws Exception {
		onFx(() -> {
			String token = view.currentSnapshot().token();
			view.revealButton().fire();
			assertEquals(token, view.tokenLabel().getText());
			view.regenerateButton().fire();
			assertFalse(token.equals(view.currentSnapshot().token()));
			assertTrue(view.statusLabel().getText().contains("connected"));
		});
	}

	@DisplayName("turning AI control off updates the status and disables regeneration")
	@Test
	void toggleOff() throws Exception {
		onFx(() -> {
			view.enabledSwitch().setChecked(false);
			assertFalse(service.snapshot().enabled());
			assertEquals("Off", view.statusLabel().getText());
			assertTrue(view.regenerateButton().isDisabled());
		});
	}

	@DisplayName("writes the AI control parity snapshot")
	@Test
	void writesParitySnapshot() throws Exception {
		// Surefire runs in the module directory: java/parity/ui-java.
		Path target = Path.of("..", "parity", "ui-java", "agent-settings.png")
				.toAbsolutePath().normalize();
		onFx(() -> {
			try {
				// The window manager clamps the shown stage to the screen; render the page in a
				// detached scene at its natural height so the token and activity rows stay visible.
				PlaceholderAgentUiService snapshotService = new PlaceholderAgentUiService();
				AgentSettingsView snapshotView = new AgentSettingsView(snapshotService);
				StackPane page = new StackPane(new ScrollPane(snapshotView));
				((ScrollPane) page.getChildren().get(0)).setFitToWidth(true);
				((ScrollPane) page.getChildren().get(0)).setHbarPolicy(
						ScrollPane.ScrollBarPolicy.NEVER);
				page.setStyle("-fx-background-color: #121418; -fx-padding: 48 72 48 72;");
				KitRoot offscreenRoot = new KitRoot(page);
				Scene offscreen = new Scene(offscreenRoot, 1440, 1000);
				Theme.install(offscreen);
				AgentSettingsView.installStyles(offscreen);
				offscreenRoot.resize(1440, 1000);
				offscreenRoot.applyCss();
				offscreenRoot.layout();
				int height = (int) Math.min(1_600,
						Math.max(1_000, Math.ceil(snapshotView.prefHeight(1440 - 144) + 96)));
				offscreenRoot.resize(1440, height);
				offscreenRoot.applyCss();
				offscreenRoot.layout();
				WritableImage image = offscreenRoot.snapshot(null, null);
				assertEquals(1440, (int) image.getWidth());
				assertEquals(height, (int) image.getHeight());
				BufferedImage buffered = new BufferedImage((int) image.getWidth(),
						(int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
				PixelReader reader = image.getPixelReader();
				for (int y = 0; y < buffered.getHeight(); y++) {
					for (int x = 0; x < buffered.getWidth(); x++) {
						buffered.setRGB(x, y, reader.getArgb(x, y));
					}
				}
				Files.createDirectories(target.getParent());
				ImageIO.write(buffered, "png", target.toFile());
				assertTrue(Files.size(target) > 10_000, "snapshot should not be empty: " + target);
				System.out.println("wrote " + target);
			} catch (IOException error) {
				throw new AssertionError(error);
			}
		});
	}

	/** Runs on the FX thread (nodes and service publishing are FX-confined). */
	private static void onFx(Runnable action) throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Platform.runLater(() -> {
			try {
				action.run();
			} catch (Throwable error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(30, TimeUnit.SECONDS), "FX action timed out");
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
	}
}