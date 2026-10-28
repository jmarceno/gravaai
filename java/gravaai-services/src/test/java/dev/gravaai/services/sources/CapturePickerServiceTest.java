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

import dev.gravaai.services.recording.CosmicOutput;

/** Ported from the Linux Wayland cases of {@code electron/ipc/register/capturePicker.test.ts}. */
class CapturePickerServiceTest {

	private static final Map<String, String> WAYLAND = Map.of("XDG_SESSION_TYPE", "wayland");
	private static final Map<String, String> X11 = Map.of("XDG_SESSION_TYPE", "x11");
	private static final CosmicOutput DP1 = new CosmicOutput("DP-1", 0, 0, 1728, 1117);
	private static final List<SourceArea.PickerDisplay> DISPLAYS =
			List.of(new SourceArea.PickerDisplay(1, new CaptureArea(0, 0, 1728, 1117)));
	private static final List<CapturePickerWindow> WINDOWS = List.of(
			new CapturePickerWindow("window:42:0", "Safari", "Docs", "1", 10, 40, 400, 300),
			new CapturePickerWindow("window:43:0", "Safari", "Offscreen", "1", 5000, 40, 400, 300));

	private final AtomicBoolean recording = new AtomicBoolean(false);
	private final AtomicReference<CompletableFuture<CosmicOutput>> clicks = new AtomicReference<>();
	private final AtomicInteger clickCalls = new AtomicInteger();
	private final AtomicInteger pickerWindowsCreated = new AtomicInteger();
	private final AtomicBoolean uiSelectorOpened = new AtomicBoolean();
	private final SourceListPicker sourceListPicker = new SourceListPicker(pickerWindowsCreated::incrementAndGet);

	private SourceService sources(Map<String, String> env) {
		return new SourceService(
				env,
				"linux",
				recording::get,
				new CaptureSelection(() -> List.of(DP1), env),
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
				env,
				"linux",
				recording::get,
				timeoutMs -> {
					clickCalls.incrementAndGet();
					CompletableFuture<CosmicOutput> click = clicks.get();
					return click != null ? click : CompletableFuture.completedFuture(null);
				},
				sources,
				() -> DISPLAYS,
				() -> WINDOWS,
				() -> new double[] {0, 0},
				() -> sourceListPicker);
	}

	@Test
	@DisplayName("picks a Wayland output by click, without overlays or geometry probes")
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
	@DisplayName("opens a fresh picker even when an area is already selected")
	void opensAFreshPickerEvenWhenAnAreaIsSelected() {
		SourceService sources = sources(WAYLAND);
		sources.applySelection(SelectedSource.area(
				"1", new CaptureArea(100, 200, 400, 300), "Area 400×300"));
		CapturePickerService picker = picker(WAYLAND, sources);

		CapturePickerService.CapturePickerContext context = picker.getCapturePickerContext(1);

		assertNull(context.lastArea());
		assertEquals(new CaptureArea(0, 0, 1728, 1117), context.displayBounds());
		assertEquals(false, context.allowArea());
		assertEquals(1, context.windows().size());
		assertEquals(0.0, context.cursorX());
	}

	@Test
	@DisplayName("returns no display bounds for an unknown display")
	void returnsNoBoundsForAnUnknownDisplay() {
		CapturePickerService picker = picker(WAYLAND, sources(WAYLAND));
		CapturePickerService.CapturePickerContext context = picker.getCapturePickerContext(9);
		assertNull(context.displayBounds());
		assertTrue(context.windows().isEmpty());
	}
}
