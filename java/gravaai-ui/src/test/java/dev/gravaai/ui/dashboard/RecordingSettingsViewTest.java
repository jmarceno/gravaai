package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.settings.AppSettingsStore;
import javafx.application.Platform;

/** Settings → Recording (DashboardSettings.tsx): key-press capture and mic noise reduction. */
@Timeout(60)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class RecordingSettingsViewTest {

	@TempDir
	Path temp;

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			Platform.runLater(() -> { });
		}
	}

	private static <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				result.set(action.call());
			} catch (Throwable error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(30, TimeUnit.SECONDS));
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
		return result.get();
	}

	@Test
	@DisplayName("defaults to shortcuts and saves the key-press mode the recorder reads")
	void savesKeyPressMode() throws Exception {
		Path file = temp.resolve("settings.json");
		AppSettingsStore settings = new AppSettingsStore(file);
		RecordingSettingsView view = onFx(() -> new RecordingSettingsView(settings));
		assertEquals("shortcuts", view.keyMode());
		assertEquals("off", view.micNoise());
		assertTrue(!Files.exists(file) || !Files.readString(file).contains("keystrokeCaptureMode"),
				"loading never writes settings");

		onFx(() -> {
			view.selectKeyMode("all");
			return null;
		});
		assertEquals("all",
				new AppSettingsStore(file).readSetting("keystrokeCaptureMode").asString());

		RecordingSettingsView reloaded = onFx(() -> new RecordingSettingsView(new AppSettingsStore(file)));
		assertEquals("all", reloaded.keyMode());
	}
}
