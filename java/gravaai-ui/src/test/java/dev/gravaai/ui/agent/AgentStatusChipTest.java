package dev.gravaai.ui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.ui.AppFonts;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/** {@link AgentStatusChip}: visibility rules and the revert/disable escape hatches (P3.2). */
@Timeout(60)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
@ExtendWith(ApplicationExtension.class)
class AgentStatusChipTest {

	private PlaceholderAgentUiService service;
	private AgentStatusChip chip;

	@BeforeAll
	static void loadFonts() {
		AppFonts.load();
	}

	@Start
	void start(Stage stage) {
		service = new PlaceholderAgentUiService();
		chip = new AgentStatusChip(service);
		Scene scene = new Scene(new KitRoot(chip), 600, 120);
		Theme.install(scene);
		stage.setScene(scene);
		stage.show();
	}

	@DisplayName("a connected agent shows the chip, the dot and the revert button")
	@Test
	void connectedState() throws Exception {
		onFx(() -> {
			assertTrue(chip.isVisible());
			assertTrue(chip.isManaged());
			assertEquals("AI editing", chip.titleLabel().getText());
			assertTrue(chip.revertButton().isVisible());
			assertTrue(chip.revertButton().isManaged());
		});
	}

	@DisplayName("revert clears the last summary and hides the revert button")
	@Test
	void revertAction() throws Exception {
		onFx(() -> {
			chip.revertButton().fire();
			assertNull(service.snapshot().lastSummary());
			assertFalse(chip.revertButton().isVisible());
		});
	}

	@DisplayName("the close button disables AI control and hides the chip")
	@Test
	void disableAction() throws Exception {
		onFx(() -> {
			chip.disableButton().fire();
			assertFalse(service.snapshot().enabled());
			assertEquals(AgentUiService.Status.OFF, service.snapshot().status());
			assertFalse(chip.isVisible());
			assertFalse(chip.isManaged());
		});
	}

	@DisplayName("disconnected with a summary stays visible; disabling hides it")
	@Test
	void disconnectedWithSummary() throws Exception {
		onFx(() -> {
			service.setClients(List.of());
			assertTrue(chip.isVisible());
			assertTrue(service.snapshot().lastSummary() != null);
			service.setEnabled(false);
			assertFalse(chip.isVisible());
		});
	}

	/** Runs on the FX thread (the chip and the service publish there). */
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
		assertTrue(done.await(15, TimeUnit.SECONDS), "FX action timed out");
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
	}
}
