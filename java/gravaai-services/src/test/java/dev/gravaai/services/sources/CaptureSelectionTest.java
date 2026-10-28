package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.CosmicOutput;

/**
 * Ported from {@code electron/ipc/captureSelection.test.ts}. Only the Linux Wayland cases are
 * ported: Electron's {@code desktopCapturer} window list and the macOS ScreenCaptureKit bounds are
 * platform code (plan §2.3).
 */
class CaptureSelectionTest {

	private static final Map<String, String> WAYLAND = Map.of("XDG_SESSION_TYPE", "wayland");
	private static final Map<String, String> X11 = Map.of("XDG_SESSION_TYPE", "x11");
	private static final CosmicOutput OUTPUT = new CosmicOutput("DP-1", 0, 0, 1728, 1117);

	private static final class FakeOutputs implements CaptureSelection.CosmicOutputs {

		final List<CosmicOutput> outputs = new ArrayList<>();
		int calls;
		RuntimeException failure;

		@Override
		public List<CosmicOutput> list() {
			calls++;
			if (failure != null) {
				throw failure;
			}
			return List.copyOf(outputs);
		}
	}

	private static SelectedSource window() {
		return SelectedSource.window("window:101:0", "Document", "1", "Notes", "Document");
	}

	@Test
	@DisplayName("rejects old X11 IDs on Wayland without enumerating portal sources")
	void rejectsOldX11IdsOnWayland() {
		FakeOutputs outputs = new FakeOutputs();
		CaptureSelection selection = new CaptureSelection(outputs, WAYLAND);
		assertNull(selection.validateCaptureSource(window()));
		assertNull(selection.validateCaptureSource(SelectedSource.screen("screen:1:0", "Screen", "1")));
		assertEquals(0, outputs.calls);
	}

	@Test
	@DisplayName("accepts the portal sentinel only on a Wayland session")
	void validatesThePortalSentinel() {
		SelectedSource candidate = SelectedSource.screen(
				SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null);
		SelectedSource validated = new CaptureSelection(new FakeOutputs(), WAYLAND)
				.validateCaptureSource(candidate);
		assertEquals(
				SelectedSource.screen(SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null),
				validated);
		assertNull(new CaptureSelection(new FakeOutputs(), X11).validateCaptureSource(candidate));
	}

	@Test
	@DisplayName("revalidates cosmic outputs and never substitutes a vanished one")
	void revalidatesCosmicOutputs() {
		FakeOutputs outputs = new FakeOutputs();
		outputs.outputs.add(OUTPUT);
		CaptureSelection selection = new CaptureSelection(outputs, WAYLAND);
		assertEquals(
				CaptureSelection.createCosmicScreenSource(OUTPUT, List.of()),
				selection.validateCaptureSource(
						SelectedSource.screen("screen:cosmic:DP-1", "Screen DP-1", null)));
		assertNull(selection.validateCaptureSource(
				SelectedSource.screen("screen:cosmic:HDMI-A-1", "Screen HDMI-A-1", null)));
		assertNull(selection.validateCaptureSource(window()));
	}

	@Test
	@DisplayName("treats a failed output listing as no outputs")
	void handlesListingFailures() {
		FakeOutputs outputs = new FakeOutputs();
		outputs.failure = new IllegalStateException("helper missing");
		CaptureSelection selection = new CaptureSelection(outputs, WAYLAND);
		assertNull(selection.validateCaptureSource(
				SelectedSource.screen("screen:cosmic:DP-1", "Screen DP-1", null)));
		assertEquals(
				SelectedSource.screen(SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null),
				selection.validateCaptureSource(
						SelectedSource.screen(SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null)));
		assertEquals(1, outputs.calls);
	}
}
