package dev.gravaai.services.sources;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import dev.gravaai.platform.compositor.CaptureBackend;
import dev.gravaai.platform.compositor.DesktopSession;
import dev.gravaai.services.recording.CosmicOutput;

/**
 * The capture-picker flow, ported from the Linux Wayland part of
 * {@code electron/ipc/register/capturePicker.ts} (plan §7.1 P1.6).
 *
 * <p>The portal screencast is unreliable on COSMIC, so the user clicks anywhere on the screen they
 * want and the output under the pointer is recorded directly.
 *
 * <p>Which picker a session gets is decided by the {@link DesktopSession}: a portal compositor uses
 * the desktop's own dialog, a direct-capture compositor waits for a click, and an X11 session opens
 * the source-list window. This class never inspects the environment itself.
 */
public final class CapturePickerService {

	/** Waits for the next click and reports the output under it. */
	public interface OutputClickWaiter {
		/** @return a future with the picked output, or null when nothing was picked */
		CompletableFuture<@org.jspecify.annotations.Nullable CosmicOutput> waitForCosmicOutputClick(
				long timeoutMs);
	}

	private final DesktopSession session;
	private final Supplier<Boolean> cursorCaptureActive;
	private final OutputClickWaiter clickWaiter;
	private final SourceService sources;
	private final Supplier<List<SourceArea.PickerDisplay>> displays;
	private final Supplier<SourceListPicker> sourceListPicker;

	private @org.jspecify.annotations.Nullable CompletableFuture<SourceService.PickResult> activePick;

	public CapturePickerService(
			DesktopSession session,
			Supplier<Boolean> cursorCaptureActive,
			OutputClickWaiter clickWaiter,
			SourceService sources,
			Supplier<List<SourceArea.PickerDisplay>> displays,
			Supplier<SourceListPicker> sourceListPicker) {
		this.session = session;
		this.cursorCaptureActive = cursorCaptureActive;
		this.clickWaiter = clickWaiter;
		this.sources = sources;
		this.displays = displays;
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
		// X11 keeps the source-list window.
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
}
