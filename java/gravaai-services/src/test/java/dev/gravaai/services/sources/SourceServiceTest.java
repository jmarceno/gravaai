package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.CosmicOutput;

/** Ported from the Linux Wayland cases of {@code electron/ipc/register/sources.test.ts}. */
class SourceServiceTest {

	private static final Map<String, String> WAYLAND = Map.of("XDG_SESSION_TYPE", "wayland");
	private static final Map<String, String> X11 = Map.of("XDG_SESSION_TYPE", "x11");

	private static final class FakeSelectorUi implements SourceService.SourceSelectorUi {

		int opened;
		int focused;
		int closed;

		@Override
		public void open() {
			opened++;
		}

		@Override
		public void focus() {
			focused++;
		}

		@Override
		public void close() {
			closed++;
		}
	}

	private final AtomicBoolean recording = new AtomicBoolean(false);
	private final AtomicInteger cosmicCalls = new AtomicInteger();
	private final FakeSelectorUi selectorUi = new FakeSelectorUi();

	private SourceService service(Map<String, String> env) {
		CaptureSelection selection = new CaptureSelection(() -> {
			cosmicCalls.incrementAndGet();
			return List.of(new CosmicOutput("DP-1", 0, 0, 1728, 1117));
		}, env);
		return new SourceService(env, "linux", recording::get, selection, selectorUi);
	}

	@Test
	@DisplayName("never enumerates or previews Wayland sources before recording")
	void neverEnumeratesWaylandSourcesBeforeRecording() {
		SourceService sourceService = service(WAYLAND);
		assertEquals(
				List.of(SelectedSource.screen(
						SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null)),
				sourceService.getDesktopSources(List.of("screen", "window")));
		assertEquals(List.of(), sourceService.getDesktopSources(List.of("window")));
	}

	@Test
	@DisplayName("Wayland starts unselected and prepares its portal only after an explicit picker request")
	void waylandStartsUnselectedAndPreparesThePortalOnRequest() {
		SourceService sourceService = service(WAYLAND);
		assertNull(sourceService.getSessionCaptureSource());
		sourceService.openSourceSelector();
		assertEquals(
				SelectedSource.screen(SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null),
				sourceService.getSessionCaptureSource());
		assertEquals(0, selectorUi.opened);
	}

	@Test
	@DisplayName("starts with no source and keeps explicit selection within the session")
	void keepsExplicitSelectionWithinTheSession() throws IOException {
		SourceService sourceService = service(WAYLAND);
		assertNull(sourceService.getSessionCaptureSource());
		SelectedSource candidate = SelectedSource.screen("screen:cosmic:DP-1", "Screen DP-1", null);
		SelectedSource selected = sourceService.selectSource(candidate);
		assertEquals("screen:cosmic:DP-1", selected.id());
		assertEquals(selected, sourceService.getSessionCaptureSource());
	}

	@Test
	@DisplayName("leaves the selection unchanged when a source is no longer available")
	void rejectsUnavailableSources() {
		SourceService sourceService = new SourceService(
				WAYLAND,
				"linux",
				recording::get,
				new CaptureSelection(() -> List.of(), WAYLAND),
				selectorUi);
		IOException error = assertThrows(
				IOException.class,
				() -> sourceService.selectSource(
						SelectedSource.screen("screen:cosmic:GONE", "Screen GONE", null)));
		assertEquals(
				"That window or screen is no longer available. Refresh the source list.",
				error.getMessage());
		assertNull(sourceService.getSessionCaptureSource());
	}

	@Test
	@DisplayName("does not open any source picker during recording or pause")
	void doesNotOpenTheSelectorDuringRecording() {
		SourceService sourceService = service(WAYLAND);
		recording.set(true);
		sourceService.openSourceSelector();
		assertNull(sourceService.getSessionCaptureSource());
		assertEquals(0, selectorUi.opened);
	}

	@Test
	@DisplayName("does not change sources if recording starts during validation")
	void doesNotChangeSourcesIfRecordingStartsDuringValidation() throws IOException {
		AtomicBoolean active = new AtomicBoolean(false);
		CaptureSelection selection = new CaptureSelection(() -> {
			active.set(true);
			return List.of(new CosmicOutput("DP-1", 0, 0, 1728, 1117));
		}, WAYLAND);
		SourceService sourceService = new SourceService(
				WAYLAND, "linux", active::get, selection, selectorUi);
		sourceService.selectSource(SelectedSource.screen("screen:cosmic:DP-1", "Screen DP-1", null));
		assertNull(sourceService.getSessionCaptureSource());
	}
}
