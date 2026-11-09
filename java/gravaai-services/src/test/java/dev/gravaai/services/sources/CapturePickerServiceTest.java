package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.platform.compositor.DesktopSession;
import dev.gravaai.services.recording.CosmicOutput;

/** Ported from the Linux Wayland cases of {@code electron/ipc/register/capturePicker.test.ts}. */
class CapturePickerServiceTest {

	private static final Map<String, String> WAYLAND = Map.of("XDG_SESSION_TYPE", "wayland");
	private static final Map<String, String> KDE_WAYLAND =
			Map.of("XDG_SESSION_TYPE", "wayland", "XDG_CURRENT_DESKTOP", "KDE");
	private static final Map<String, String> X11 = Map.of("XDG_SESSION_TYPE", "x11");
	private static final CosmicOutput DP1 = new CosmicOutput("DP-1", 0, 0, 1728, 1117);
	private static final List<SourceArea.PickerDisplay> DISPLAYS =
			List.of(new SourceArea.PickerDisplay(1, new CaptureArea(0, 0, 1728, 1117)));

	private final AtomicBoolean recording = new AtomicBoolean(false);
	private final AtomicReference<CompletableFuture<CosmicOutput>> clicks = new AtomicReference<>();
	private final AtomicInteger clickCalls = new AtomicInteger();
	private final AtomicInteger pickerWindowsCreated = new AtomicInteger();
	private final AtomicBoolean uiSelectorOpened = new AtomicBoolean();
	private final SourceListPicker sourceListPicker = new SourceListPicker(pickerWindowsCreated::incrementAndGet);

	private SourceService sources(Map<String, String> env) {
		return new SourceService(
				DesktopSession.detect(env),
				recording::get,
				new CaptureSelection(() -> List.of(DP1), DesktopSession.detect(env)),
				new SourceService.SourceSelectorUi() {
					@Override
					public void open() {
						uiSelectorOpened.set(true);
					}

					@Override
					public void focus() {
					}

					@Override
					public void close() {
					}
				});
	}

	private CapturePickerService picker(Map<String, String> env, SourceService sources) {
		return new CapturePickerService(
				DesktopSession.detect(env),
				recording::get,
				timeoutMs -> {
					clickCalls.incrementAndGet();
					CompletableFuture<CosmicOutput> click = clicks.get();
					return click != null ? click : CompletableFuture.completedFuture(null);
				},
				sources,
				() -> DISPLAYS,
				() -> sourceListPicker);
	}

	@Test
	@DisplayName("picks a Wayland output by click, without a window list")
	void picksAWaylandOutputByClick() {
		clicks.set(CompletableFuture.completedFuture(DP1));
		SourceService sources = sources(WAYLAND);
		CapturePickerService picker = picker(WAYLAND, sources);

		SourceService.PickResult result = picker.pickCaptureTarget().join();

		assertEquals(true, result.success());
		assertEquals(
				CaptureSelection.createCosmicScreenSource(DP1, List.of(
						new DisplayOutputResolver.DisplayInfo(1, new CaptureArea(0, 0, 1728, 1117)))),
				result.source());
		assertEquals(result.source(), sources.getSelectedSource());
		assertEquals(1, clickCalls.get());
		assertEquals(0, pickerWindowsCreated.get());
		assertEquals(false, uiSelectorOpened.get());
	}

	@Test
	@DisplayName("does not open any source picker during recording or pause")
	void doesNotOpenAnyPickerDuringRecording() {
		recording.set(true);
		CapturePickerService picker = picker(WAYLAND, sources(WAYLAND));

		assertEquals(SourceService.PickResult.canceledPick(), picker.pickCaptureTarget().join());
		assertEquals(0, clickCalls.get());
		assertEquals(0, pickerWindowsCreated.get());
	}

	@Test
	@DisplayName("rejects a pending selection if recording starts while the picker is open")
	void rejectsAPendingSelectionIfRecordingStarts() {
		CompletableFuture<CosmicOutput> pending = new CompletableFuture<>();
		clicks.set(pending);
		SourceService sources = sources(WAYLAND);
		CapturePickerService picker = picker(WAYLAND, sources);
		CompletableFuture<SourceService.PickResult> selection = picker.pickCaptureTarget();

		recording.set(true);
		pending.complete(DP1);

		assertEquals(SourceService.PickResult.canceledPick(), selection.join());
		assertNull(sources.getSelectedSource());
	}

	@Test
	@DisplayName("keeps X11 on the source list and coalesces concurrent opens")
	void keepsX11OnTheSourceListAndCoalescesOpens() {
		SourceService sources = sources(X11);
		CapturePickerService picker = picker(X11, sources);

		CompletableFuture<SourceService.PickResult> first = picker.pickCaptureTarget();
		CompletableFuture<SourceService.PickResult> second = picker.pickCaptureTarget();
		assertSame(first, second);
		assertEquals(1, pickerWindowsCreated.get());

		SelectedSource source = SelectedSource.screen("screen:1:0", "Screen 1", "1");
		sourceListPicker.complete(source);
		assertEquals(SourceService.PickResult.picked(source), first.join());
		assertEquals(SourceService.PickResult.picked(source), second.join());
	}

	@Test
	@DisplayName("a portal compositor uses the desktop's own picker instead of waiting for a click")
	void portalCompositorUsesTheSystemPicker() {
		SourceService sources = sources(KDE_WAYLAND);
		CapturePickerService picker = picker(KDE_WAYLAND, sources);

		SourceService.PickResult result = picker.pickCaptureTarget().join();

		assertEquals(true, result.success());
		assertEquals(SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, result.source().id());
		assertEquals(result.source(), sources.getSelectedSource());
		// The portal dialog chose the screen; a click would never arrive.
		assertEquals(0, clickCalls.get());
		assertEquals(0, pickerWindowsCreated.get());
	}

	@Test
	@DisplayName("the HUD monitor list comes from the session's screen sources")
	void listsTheSessionsScreenSources() {
		SourceService sources = sources(WAYLAND);
		CapturePickerService picker = picker(WAYLAND, sources);

		List<SelectedSource> screens = picker.screenSources();
		assertEquals(1, screens.size());
		assertTrue(picker.selectScreen(screens.getFirst().id()));
		assertEquals(screens.getFirst(), sources.getSelectedSource());
	}
}
