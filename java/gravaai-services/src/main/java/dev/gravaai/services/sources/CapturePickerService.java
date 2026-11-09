package dev.gravaai.services.sources;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.gravaai.platform.compositor.CaptureBackend;
import dev.gravaai.platform.compositor.DesktopSession;
import dev.gravaai.services.recording.CosmicOutput;

/**
 * The capture-picker flow, ported from the Linux Wayland part of
 * {@code electron/ipc/register/capturePicker.ts} (plan §7.1 P1.6).
 *
 * <p>The portal screencast is unreliable on COSMIC, so the user clicks anywhere on the screen they
 * want and the output under the pointer is recorded directly. The Electron overlay picker and the
 * macOS screen/area rules are platform code (plan §2.3); the selection-only completion helpers are
 * kept for a future X11 source list.
 *
 * <p>Which picker a session gets is decided by the {@link DesktopSession}: a portal compositor uses
 * the desktop's own dialog, a direct-capture compositor waits for a click, and an X11 session opens
 * the source list. This class never inspects the environment itself.
 */
public final class CapturePickerService {

	/** Waits for the next click and reports the output under it. */
	public interface OutputClickWaiter {
		/** @return a future with the picked output, or null when nothing was picked */
		CompletableFuture<@org.jspecify.annotations.Nullable CosmicOutput> waitForCosmicOutputClick(
				long timeoutMs);
	}

	/** What the capture-picker overlay asks for. */
	public record CapturePickerContext(
			@Nullable CaptureArea displayBounds,
			@Nullable CaptureArea lastArea,
			boolean allowArea,
			List<CapturePickerWindow> windows,
			double cursorX,
			double cursorY) {
	}

	private final DesktopSession session;
	private final Supplier<Boolean> cursorCaptureActive;
	private final OutputClickWaiter clickWaiter;
	private final SourceService sources;
	private final Supplier<List<SourceArea.PickerDisplay>> displays;
	private final Supplier<List<CapturePickerWindow>> pickableWindows;
	private final Supplier<double[]> cursorPoint;
	private final Supplier<SourceListPicker> sourceListPicker;

	private @Nullable CompletableFuture<SourceService.PickResult> activePick;
	private @Nullable Consumer<CapturePick> resolvePendingPick;
	private List<CapturePickerWindow> loadedWindows = List.of();

	public CapturePickerService(
			DesktopSession session,
			Supplier<Boolean> cursorCaptureActive,
			OutputClickWaiter clickWaiter,
			SourceService sources,
			Supplier<List<SourceArea.PickerDisplay>> displays,
			Supplier<List<CapturePickerWindow>> pickableWindows,
			Supplier<double[]> cursorPoint,
			Supplier<SourceListPicker> sourceListPicker) {
		this.session = session;
		this.cursorCaptureActive = cursorCaptureActive;
		this.clickWaiter = clickWaiter;
		this.sources = sources;
		this.displays = displays;
		this.pickableWindows = pickableWindows;
		this.cursorPoint = cursorPoint;
		this.sourceListPicker = sourceListPicker;
	}

	/** {@code pick-capture-target}: simultaneous requests share one picker. */
	public CompletableFuture<SourceService.PickResult> pickCaptureTarget() {
		synchronized (this) {
			if (activePick != null) {
				return activePick;
			}
			CompletableFuture<SourceService.PickResult> pick = pickTarget()
					.exceptionally(error -> SourceService.PickResult.failed(
							error.getMessage() == null ? "Unable to open the source picker." : error.getMessage()))
					.whenComplete((result, error) -> {
						synchronized (CapturePickerService.this) {
							activePick = null;
						}
					});
			activePick = pick;
			return pick;
		}
	}

	private CompletableFuture<SourceService.PickResult> pickTarget() {
		if (cursorCaptureActive.get()) {
			return CompletableFuture.completedFuture(SourceService.PickResult.canceledPick());
		}
		if (session.isWayland() && session.captureBackend() == CaptureBackend.PORTAL) {
			// On a portal compositor the desktop's own screen-sharing dialog is the picker:
			// it asks the user which monitor to share and remembers the choice. Waiting for a
			// click would never resolve, because the portal hides where the cursor is.
			SelectedSource source = SelectedSource.screen(
					SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null);
			sources.applySelection(source);
			return CompletableFuture.completedFuture(SourceService.PickResult.picked(source));
		}
		if (session.isWayland()) {
			// The portal screencast is unreliable on COSMIC, so record an output
			// directly: the user clicks anywhere on the screen they want.
			return clickWaiter.waitForCosmicOutputClick(15_000).thenApply(output -> {
				if (output == null || cursorCaptureActive.get()) {
					return SourceService.PickResult.canceledPick();
				}
				SelectedSource source = CaptureSelection.createCosmicScreenSource(output, displayInfos());
				sources.applySelection(source);
				return SourceService.PickResult.picked(source);
			});
		}
		// X11 keeps the existing source-list window.
		return sourceListPicker.get().open().thenApply(result -> {
			SelectedSource source = result.source();
			return result.success() && source != null
					? SourceService.PickResult.picked(source)
					: SourceService.PickResult.canceledPick();
		});
	}

	/** The monitors the HUD lists, so the user chooses one without clicking a screen. */
	public List<SelectedSource> screenSources() {
		return sources.getScreenSources(displayInfos());
	}

	/** Selects one monitor from {@link #screenSources()}; false when it is gone. */
	public boolean selectScreen(String sourceId) {
		if (sourceId == null || cursorCaptureActive.get()) {
			return false;
		}
		for (SelectedSource screen : screenSources()) {
			if (sourceId.equals(screen.id())) {
				sources.applySelection(screen);
				return true;
			}
		}
		return false;
	}

	private List<DisplayOutputResolver.DisplayInfo> displayInfos() {
		List<DisplayOutputResolver.DisplayInfo> infos = new ArrayList<>();
		for (SourceArea.PickerDisplay display : displays.get()) {
			infos.add(new DisplayOutputResolver.DisplayInfo(display.id(), display.bounds()));
		}
		return infos;
	}

	/**
	 * {@code complete-capture-pick}: the overlay reports a pick; the selection is forced to
	 * selection-only and validated against the displays and windows the picker offered.
	 *
	 * <p>An area pick is rejected unless the picker offered one. The Linux pickers never do, so a
	 * rectangle from a stale overlay can never become a recording selection.
	 */
	public void completeCapturePick(@Nullable CapturePickRequest input) {
		if (input == null || cursorCaptureActive.get()) {
			finishPick(null);
			return;
		}
		if ("area".equals(input.kind()) && !areaSelectionSupported()) {
			return;
		}
		CapturePickRequest selectionOnly = new CapturePickRequest(
				input.kind(),
				input.x(),
				input.y(),
				input.width(),
				input.height(),
				input.windowId(),
				input.displayId(),
				false);
		finishPick(SourceArea.normalizeCapturePick(selectionOnly, displays.get(), loadedWindows));
	}

	/** Whether this session's picker can offer a rectangle. Linux pickers cannot. */
	private boolean areaSelectionSupported() {
		return false;
	}

	private void finishPick(@Nullable CapturePick pick) {
		Consumer<CapturePick> resolve;
		synchronized (this) {
			resolve = resolvePendingPick;
			resolvePendingPick = null;
		}
		if (resolve != null) {
			resolve.accept(pick);
		}
	}

	/** {@code get-capture-picker-context}: the windows that intersect one display. */
	public CapturePickerContext getCapturePickerContext(int displayId) {
		List<CapturePickerWindow> windows = pickableWindows.get();
		loadedWindows = windows;
		SourceArea.PickerDisplay display = null;
		for (SourceArea.PickerDisplay candidate : displays.get()) {
			if (candidate.id() == displayId) {
				display = candidate;
				break;
			}
		}
		double[] cursor = cursorPoint.get();
		if (display == null) {
			return new CapturePickerContext(null, null, false, List.of(), cursor[0], cursor[1]);
		}
		CaptureArea bounds = display.bounds();
		List<CapturePickerWindow> intersecting = new ArrayList<>();
		for (CapturePickerWindow window : windows) {
			if (window.x() < bounds.x() + bounds.width()
					&& window.x() + window.width() > bounds.x()
					&& window.y() < bounds.y() + bounds.height()
					&& window.y() + window.height() > bounds.y()) {
				intersecting.add(window);
			}
		}
		// A previous area is never offered again: always draw a fresh picker.
		return new CapturePickerContext(
				bounds, null, areaSelectionSupported(), List.copyOf(intersecting), cursor[0], cursor[1]);
	}
}
