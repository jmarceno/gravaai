package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.gravaai.ui.kit.Label;
import javafx.application.Platform;

/**
 * Linux variant of the recording permissions page (port of
 * {@code RecordingPermissionsSettings.tsx}). Needs a JavaFX toolkit (Label/Control static init), so
 * it only runs when {@code DISPLAY} is set like the other UI tests.
 */
@Timeout(60)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class RecordingPermissionsSettingsTest {

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

	/** Builds the page on the FX thread (toolkit-owned nodes). */
	private static RecordingPermissionsSettings page() throws Exception {
		AtomicReference<RecordingPermissionsSettings> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				result.set(new RecordingPermissionsSettings());
			} catch (Throwable error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(30, TimeUnit.SECONDS), "FX work timed out");
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
		return result.get();
	}

	@DisplayName("renders the Linux capture hint and the microphone/camera row")
	@Test
	void rendersLinuxContent() throws Exception {
		RecordingPermissionsSettings page = page();
		assertEquals(2, page.getChildren().size());
		Label hint = (Label) page.getChildren().get(0);
		assertEquals(RecordingPermissionsSettings.CAPTURE_HINT, hint.getText());
		assertTrue(hint.isWrapText());
		SettingsRow media = (SettingsRow) page.getChildren().get(1);
		// Stacked row: one column with the title/description block over the status control.
		assertEquals(1, media.getChildren().size());
		javafx.scene.layout.VBox column = (javafx.scene.layout.VBox) media.getChildren().get(0);
		assertEquals(2, column.getChildren().size());
		assertEquals(javafx.geometry.Pos.TOP_LEFT, column.getAlignment());
	}

	@DisplayName("texts match the React Linux strings")
	@Test
	void texts() {
		assertEquals("Choose what to capture when you start a recording.",
				RecordingPermissionsSettings.CAPTURE_HINT);
		assertEquals("Microphone and camera", RecordingPermissionsSettings.MEDIA_TITLE);
		assertEquals("Access is requested when you enable a microphone or camera for recording.",
				RecordingPermissionsSettings.MEDIA_DESCRIPTION);
		assertEquals("Requested when needed", RecordingPermissionsSettings.MEDIA_STATUS);
	}
}
