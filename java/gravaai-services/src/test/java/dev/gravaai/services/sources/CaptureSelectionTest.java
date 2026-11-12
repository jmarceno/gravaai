package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.platform.compositor.DesktopSession;
import dev.gravaai.services.recording.CosmicOutput;
import dev.gravaai.services.recording.WindowSources;

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

	/** The helper's window list, as {@code --list-windows} reports it. */
	private static final class FakeWindows implements CaptureSelection.CaptureWindows {

		final List<WindowSources.CaptureWindow> windows = new ArrayList<>();
		Exception failure;

		@Override
		public List<WindowSources.CaptureWindow> list() throws Exception {
			if (failure != null) {
				throw failure;
			}
			return List.copyOf(windows);
		}
	}

	private static final WindowSources.CaptureWindow EDITOR =
			new WindowSources.CaptureWindow("5f1c", "code", "main.rs - gravaai");

	@Test
	@DisplayName("rejects old X11 IDs on Wayland without enumerating portal sources")
	void rejectsOldX11IdsOnWayland() {
		FakeOutputs outputs = new FakeOutputs();
		CaptureSelection selection = new CaptureSelection(outputs, DesktopSession.detect(WAYLAND));
		assertNull(selection.validateCaptureSource(window()));
		assertNull(selection.validateCaptureSource(SelectedSource.screen("screen:1:0", "Screen", "1")));
		assertEquals(0, outputs.calls);
	}

	@Test
	@DisplayName("accepts the portal sentinel only on a Wayland session")
	void validatesThePortalSentinel() {
		SelectedSource candidate = SelectedSource.screen(
				SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null);
		SelectedSource validated = new CaptureSelection(new FakeOutputs(), DesktopSession.detect(WAYLAND))
				.validateCaptureSource(candidate);
		assertEquals(
				SelectedSource.screen(SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null),
				validated);
		assertNull(new CaptureSelection(new FakeOutputs(), DesktopSession.detect(X11)).validateCaptureSource(candidate));
	}

	@Test
	@DisplayName("revalidates cosmic outputs and never substitutes a vanished one")
	void revalidatesCosmicOutputs() {
		FakeOutputs outputs = new FakeOutputs();
		outputs.outputs.add(OUTPUT);
		CaptureSelection selection = new CaptureSelection(outputs, DesktopSession.detect(WAYLAND));
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
		CaptureSelection selection = new CaptureSelection(outputs, DesktopSession.detect(WAYLAND));
		assertNull(selection.validateCaptureSource(
				SelectedSource.screen("screen:cosmic:DP-1", "Screen DP-1", null)));
		assertEquals(
				SelectedSource.screen(SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null),
				selection.validateCaptureSource(
						SelectedSource.screen(SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null)));
		assertEquals(1, outputs.calls);
	}

	@Test
	@DisplayName("lists the compositor's windows as selectable sources")
	void listsWindows() {
		FakeWindows windows = new FakeWindows();
		windows.windows.add(EDITOR);
		CaptureSelection selection =
				new CaptureSelection(new FakeOutputs(), DesktopSession.detect(WAYLAND), windows);

		List<SelectedSource> sources = selection.listCaptureWindowSources();

		assertEquals(1, sources.size());
		assertEquals("window:5f1c", sources.get(0).id());
		assertEquals("main.rs - gravaai", sources.get(0).name());
		assertEquals(SourceType.WINDOW, sources.get(0).sourceType());
	}

	@Test
	@DisplayName("a closed window is refused instead of substituted")
	void refusesAClosedWindow() {
		FakeWindows windows = new FakeWindows();
		windows.windows.add(EDITOR);
		CaptureSelection selection =
				new CaptureSelection(new FakeOutputs(), DesktopSession.detect(WAYLAND), windows);

		assertEquals("window:5f1c",
				selection.validateCaptureSource(CaptureSelection.asWindowSource(EDITOR)).id());
		windows.windows.clear();
		assertNull(selection.validateCaptureSource(CaptureSelection.asWindowSource(EDITOR)));
	}

	@Test
	@DisplayName("a window that is listed again is rebuilt from the live entry")
	void revalidatesWindowsFromTheLiveList() {
		FakeWindows windows = new FakeWindows();
		windows.windows.add(EDITOR);
		CaptureSelection selection =
				new CaptureSelection(new FakeOutputs(), DesktopSession.detect(WAYLAND), windows);

		// A stale name from an earlier list must not survive revalidation.
		SelectedSource stale = SelectedSource.window("window:5f1c", "old title", null, "code", "old title");
		assertEquals("main.rs - gravaai",
				selection.validateCaptureSource(stale).name());
	}

	@Test
	@DisplayName("the portal window source needs no list: the dialog decides")
	void acceptsThePortalWindowSource() {
		CaptureSelection selection = new CaptureSelection(new FakeOutputs(), DesktopSession.detect(WAYLAND));

		SelectedSource validated = selection.validateCaptureSource(
				CaptureSelection.portalWindowSource("Share a window…"));
		assertEquals("window:portal", validated.id());
		assertEquals(SourceType.WINDOW, validated.sourceType());
	}

	@Test
	@DisplayName("window capture is not offered outside Wayland")
	void doesNotListWindowsOffWayland() {
		FakeWindows windows = new FakeWindows();
		windows.windows.add(EDITOR);
		CaptureSelection selection =
				new CaptureSelection(new FakeOutputs(), DesktopSession.detect(X11), windows);

		assertEquals(List.of(), selection.listCaptureWindowSources());
		assertNull(selection.validateCaptureSource(CaptureSelection.asWindowSource(EDITOR)));
	}

	@Test
	@DisplayName("a failed window listing is an empty list, not an error")
	void handlesWindowListingFailures() {
		FakeWindows windows = new FakeWindows();
		windows.windows.add(EDITOR);
		windows.failure = new IllegalStateException("helper missing");
		CaptureSelection selection =
				new CaptureSelection(new FakeOutputs(), DesktopSession.detect(WAYLAND), windows);

		assertEquals(List.of(), selection.listCaptureWindowSources());
		assertNull(selection.validateCaptureSource(CaptureSelection.asWindowSource(EDITOR)));
	}
}
