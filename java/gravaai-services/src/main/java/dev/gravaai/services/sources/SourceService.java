package dev.gravaai.services.sources;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.gravaai.platform.process.ProcessRunner;
import dev.gravaai.services.recording.RecordingPaths;

/**
 * The source service (Appendix A {@code SourceService}), ported from the Linux Wayland parts of
 * {@code electron/ipc/register/sources.ts} and {@code electron/ipc/register/sourceMapping.ts}
 * (plan §7.1 P1.6).
 *
 * <p>On COSMIC the portal screencast is unreliable, so the source list offers only the portal
 * sentinel and the capture picker records a COSMIC output directly; the app targets Linux Wayland
 * (plan §2), so Electron's {@code desktopCapturer} enumeration and the macOS/Windows branches are
 * not ported (plan §2.3).
 */
public final class SourceService {

	private static final System.Logger LOG = System.getLogger("gravaai.sources");

	/** {@code SourceListPickResult} of {@code pick-capture-target}. */
	public record PickResult(boolean success, boolean canceled, String message, SelectedSource source) {

		public static PickResult picked(SelectedSource source) {
			return new PickResult(true, false, null, source);
		}

		public static PickResult canceledPick() {
			return new PickResult(false, true, null, null);
		}

		public static PickResult failed(String message) {
			return new PickResult(false, false, message, null);
		}
	}

	/** The UI actions the source flow needs; implemented by {@code dev.gravaai.ui.overlays}. */
	public interface SourceSelectorUi {

		/** Creates or raises the source selector window. */
		void open();

		void focus();

		void close();
	}

	private static final long SOURCE_LIST_CACHE_TTL_MS = 1200;

	private record CacheEntry(String key, long expiresAt, List<SelectedSource> value) {
	}

	private final Map<String, String> env;
	private final String platform;
	private final Supplier<Boolean> cursorCaptureActive;
	private final CaptureSelection captureSelection;
	private final SourceSelectorUi sourceSelectorUi;
	private final List<Consumer<SelectedSource>> selectedSourceListeners = new CopyOnWriteArrayList<>();

	private volatile SelectedSource selectedSource;
	private CacheEntry sourceListCache;

	public SourceService(
			Map<String, String> env,
			String platform,
			Supplier<Boolean> cursorCaptureActive,
			CaptureSelection captureSelection,
			SourceSelectorUi sourceSelectorUi) {
		this.env = env;
		this.platform = platform;
		this.cursorCaptureActive = cursorCaptureActive;
		this.captureSelection = captureSelection;
		this.sourceSelectorUi = sourceSelectorUi;
	}

	public boolean isWaylandSession() {
		return SourceMapping.isLikelyLinuxWaylandSession(env);
	}

	/** The current in-memory selection; a fresh process starts unselected (never restored). */
	public SelectedSource getSessionCaptureSource() {
		return selectedSource;
	}

	public SelectedSource getSelectedSource() {
		return selectedSource;
	}

	/** Subscribes to {@code selected-source-changed}; close the handle to unsubscribe. */
	public AutoCloseable onSelectedSourceChanged(Consumer<SelectedSource> listener) {
		selectedSourceListeners.add(listener);
		return () -> selectedSourceListeners.remove(listener);
	}

	private void broadcastSelectedSourceChange() {
		for (Consumer<SelectedSource> listener : selectedSourceListeners) {
			listener.accept(selectedSource);
		}
	}

	private void setSelectedSource(SelectedSource source) {
		selectedSource = source;
	}

	/** {@code get-sources}: the source list for one window's request. */
	public List<SelectedSource> getDesktopSources(List<String> types) {
		boolean includeScreens = types == null || types.contains("screen");
		if ("linux".equals(platform) && isWaylandSession()) {
			if (types != null && !includeScreens) {
				return List.of();
			}
			return List.of(SelectedSource.screen(
					SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null));
		}
		// The Java app targets Linux Wayland only: Electron's desktopCapturer has no replacement
		// and the window list is not ported (plan §2.3).
		return List.of();
	}

	/**
	 * {@code select-source}: validates the candidate first and never substitutes a different
	 * source; the selection stays in memory for this app session.
	 */
	public SelectedSource selectSource(SelectedSource source) throws IOException {
		if (cursorCaptureActive.get()) {
			return selectedSource;
		}
		SelectedSource candidate = source;
		if ("linux".equals(platform)) {
			candidate = captureSelection.validateCaptureSource(source, List.of());
			if (candidate == null) {
				throw new IOException(
						"That window or screen is no longer available. Refresh the source list.");
			}
		}
		if (candidate.id() != null && candidate.id().startsWith("window:")) {
			bringSelectedWindowForward(candidate);
		}
		if (cursorCaptureActive.get()) {
			return selectedSource;
		}
		setSelectedSource(candidate);
		broadcastSelectedSourceChange();
		return selectedSource;
	}

	/** {@code open-source-selector}. */
	public void openSourceSelector() {
		if (cursorCaptureActive.get()) {
			return;
		}
		if ("linux".equals(platform) && isWaylandSession()) {
			setSelectedSource(SelectedSource.screen(
					SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null));
			broadcastSelectedSourceChange();
			return;
		}
		sourceSelectorUi.open();
	}

	/**
	 * Raises the selected window (best effort; selection and capture continue when it fails).
	 * Ported from {@code bringSelectedWindowForward}; X11 only, so on COSMIC it is a no-op.
	 */
	private void bringSelectedWindowForward(SelectedSource source) {
		Integer windowId = RecordingPaths.parseWindowId(source.id());
		if (windowId == null) {
			return;
		}
		if (!"linux".equals(platform) || isWaylandSession()) {
			return;
		}
		try {
			ProcessRunner.of("wmctrl", "-i", "-a", "0x" + Integer.toHexString(windowId))
					.timeout(Duration.ofMillis(1500))
					.run();
			Thread.sleep(250);
		} catch (IOException | InterruptedException error) {
			// Raising the source is best-effort; selection and capture can still continue.
			if (error instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LOG.log(System.Logger.Level.DEBUG, "Could not raise the selected window: " + error);
		}
	}

	/** Applies a source that was just built and validated by the capture picker. */
	/** The monitors that can be recorded, one screen source per COSMIC output. */
	public List<SelectedSource> getScreenSources(List<DisplayOutputResolver.DisplayInfo> displays) {
		return captureSelection.listCosmicScreenSources(displays);
	}

	void applySelection(SelectedSource source) {
		setSelectedSource(source);
		broadcastSelectedSourceChange();
	}
}
