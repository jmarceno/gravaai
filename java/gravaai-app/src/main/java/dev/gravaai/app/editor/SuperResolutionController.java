package dev.gravaai.app.editor;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.media.superres.SuperResolutionEngine;
import dev.gravaai.services.superres.SuperResAssets;
import dev.gravaai.services.superres.SuperResInstaller;
import dev.gravaai.ui.editor.SuperResolutionUiService;
import javafx.application.Platform;

/**
 * Owns the optional deep-zoom upscaler for the editor: the on/off preference (an app setting, not
 * a project field, because it depends on what is installed on this machine), the model install,
 * and the engine handed to the exporter.
 *
 * <p>Export only, by construction: the preview renders through its own compositor and never asks
 * this controller for an engine, because a CPU inference per frame cannot hold 60 fps. The
 * preference therefore changes exactly one thing: whether an export installs an engine at all.
 *
 * <p>The model is a first-use download (4.9 MB), so this follows the auto-mask controller's shape:
 * mutations and installs run off the FX thread, UI state is published on it, and a failed install
 * is a reported state rather than an exception the editor has to carry.
 */
public final class SuperResolutionController {

	private static final System.Logger LOG =
			System.getLogger(SuperResolutionController.class.getName());

	/** {@code app-settings.json} key: export deep zooms with the installed upscaler. */
	public static final String ENABLED_SETTING = "editor_export_super_resolution";

	private final @Nullable AppSettingsStore settings;
	private final SuperResAssets assets;
	private final SuperResInstaller installer;
	private final List<Consumer<SuperResolutionUiService.State>> listeners =
			new CopyOnWriteArrayList<>();
	/** Cancels an install in flight when the user toggles it back off mid-download. */
	private final AtomicBoolean installing = new AtomicBoolean();
	/**
	 * Guards the one provider probe per JVM: {@link OnnxWebGpuPlugin} registers the plugin into a
	 * process-wide ORT registry, so a second probe must never re-register it.
	 */
	private static final AtomicBoolean PROVIDER_PROBE_STARTED = new AtomicBoolean();
	/** The probed provider label ("WebGPU · AMD Radeon RX 560" / "CPU"), null until it lands. */
	private static volatile @Nullable String probedProvider;
	/** Why the CPU is in use when no GPU device was found, null when on GPU or not probed yet. */
	private static volatile @Nullable String probedProviderDetail;

	private volatile SuperResolutionUiService.State state;

	public SuperResolutionController(@Nullable AppSettingsStore settings, SuperResAssets assets,
			SuperResInstaller installer) {
		this.settings = settings;
		this.assets = assets;
		this.installer = installer;
		this.state = newState(0, null, false, false);
	}

	/** The UI port this controller backs. */
	public SuperResolutionUiService service() {
		return new SuperResolutionUiService() {
			@Override
			public State state() {
				return state;
			}

			@Override
			public void setEnabled(boolean enabled) {
				SuperResolutionController.this.setEnabled(enabled);
			}

			@Override
			public void downloadModel() {
				SuperResolutionController.this.downloadModel();
			}

			@Override
			public void removeModel() {
				SuperResolutionController.this.removeModel();
			}

			@Override
			public void addListener(Consumer<State> listener) {
				listeners.add(listener);
				listener.accept(state);
			}

			@Override
			public void removeListener(Consumer<State> listener) {
				listeners.remove(listener);
			}
		};
	}

	/**
	 * Whether an export started now should install the engine. False until the user opts in and
	 * the model is installed: a project exported on a machine that never downloaded it must not
	 * silently start a download (or run without the promise the toggle makes).
	 */
	public boolean exportUpscalingEnabled() {
		return state.enabled() && state.modelInstalled();
	}

	/**
	 * Opens the engine for one export, or returns null when upscaling is off or the model is not
	 * installed. The caller hands it to the export provider, which closes it with the GL context.
	 *
	 * <p>Opening is cheap (one session load, ~40 ms); the network only runs on deep-zoom frames.</p>
	 */
	public @Nullable SuperResolutionEngine openEngineForExport(Path model) {
		if (!exportUpscalingEnabled()) {
			return null;
		}
		try {
			return SuperResolutionEngine.open(model);
		} catch (RuntimeException error) {
			// An export must never fail because the optional upscaler is unusable: report it and
			// export with the sharpening pass, exactly as if the model were not installed.
			LOG.log(System.Logger.Level.WARNING,
					"super-resolution unavailable, exporting with sharpening only: " + error);
			return null;
		}
	}

	/**
	 * Where this machine's installed model lives, for the exporter to open. Separate from
	 * {@link #openEngineForExport} so the exporter can resolve the path without knowing the asset
	 * layout.
	 */
	public Path installedModelPath() {
		return assets.modelFile();
	}

	private void setEnabled(boolean enabled) {
		if (enabled == state.enabled()) {
			return;
		}
		// Turning the feature on without the model starts its install: the toggle is the user's
		// intent, and making them find a separate button would leave the export silently unchanged.
		if (enabled && !state.modelInstalled()) {
			writeSetting(true);
			state = newState(state.percent(), state.message(), state.failed(), true);
			publish();
			probeProviderAsync();
			downloadModel();
			return;
		}
		writeSetting(enabled);
		state = newState(state.percent(), state.message(), state.failed(), enabled);
		publish();
		if (enabled) {
			// The provider row is the promise this toggle makes; fill it in as soon as it matters.
			probeProviderAsync();
		}
	}

	private void downloadModel() {
		if (!installing.compareAndSet(false, true)) {
			return;
		}
		state = newState(0, "Downloading the upscaler (4.9 MB)…", false, state.enabled());
		publish();
		Threads.startVirtual("gravaai-superres-download", () -> {
			// The downloader reports every 64 KB read (~75 times for this file), so publishing per
			// callback would flood the FX thread. Publish when the whole percent changes instead.
			java.util.concurrent.atomic.AtomicInteger lastPercent = new java.util.concurrent.atomic.AtomicInteger(-1);
			// Set when Remove stopped this install: that is the user's choice, not a failure.
			AtomicBoolean cancelledByUser = new AtomicBoolean();
			try {
				installer.ensure(percent -> {
					if (!installing.get()) {
						cancelledByUser.set(true);
						throw new IllegalStateException("super-resolution install cancelled");
					}
					if (percent == lastPercent.getAndSet(percent)) {
						return;
					}
					SuperResolutionUiService.State current = state;
					state = newState(percent,
							percent >= 100 ? null : "Downloading the upscaler…", false,
							current.enabled());
					publish();
				});
				state = newState(100, null, false, state.enabled());
			} catch (RuntimeException error) {
				if (cancelledByUser.get()) {
					// Remove already published "not installed, off"; do not paint it as an error.
					state = newState(0, null, false, state.enabled());
				} else {
					LOG.log(System.Logger.Level.WARNING,
							"super-resolution model install failed: " + error);
					state = newState(state.percent(), errorMessage(error), true, state.enabled());
				}
			} finally {
				installing.set(false);
				publish();
				// The model just landed: the provider row can now describe what exports will use.
				probeProviderAsync();
			}
		});
	}

	private void removeModel() {
		installing.set(false);
		try {
			installer.remove();
			// Removing the model turns the export pass off with it: the toggle must not claim an
			// upscale that cannot run.
			writeSetting(false);
			state = newState(0, null, false, false);
		} catch (RuntimeException error) {
			state = newState(0, errorMessage(error), true, state.enabled());
		}
		publish();
	}

	private void writeSetting(boolean enabled) {
		if (settings == null) {
			return;
		}
		try {
			settings.writeSetting(ENABLED_SETTING, Json.nodeFactory().booleanNode(enabled));
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING,
					"could not save the super-resolution preference: " + error);
		}
	}

	private boolean readEnabledSetting() {
		if (settings == null) {
			return false;
		}
		var value = settings.readSetting(ENABLED_SETTING);
		return value != null && value.isBoolean() && value.booleanValue();
	}

	private SuperResolutionUiService.State newState(int percent, @Nullable String message,
			boolean failed, boolean enabled) {
		return newState(percent, message, failed, enabled, assets.isInstalled());
	}

	private SuperResolutionUiService.State newState(int percent, @Nullable String message,
			boolean failed, boolean enabled, boolean installed) {
		// Every mutation keeps the probed provider: it is a property of the machine, not of the
		// install, and dropping it would blank the row on every toggle.
		return new SuperResolutionUiService.State(enabled, installed, percent, message, failed,
				probedProvider, probedProviderDetail);
	}

	/** Re-reads the setting and the model from disk (the window's initial state). */
	public void refresh() {
		state = newState(100, null, false, readEnabledSetting());
		publish();
		probeProviderAsync();
	}

	/**
	 * Probes the execution provider once per process and republishes the state with what exports
	 * will actually run on.
	 *
	 * <p>Only when the feature can matter (the model is installed or the user turned the toggle on):
	 * the probe loads the plugin and creates a Vulkan device, and an untouched machine must not pay
	 * that at editor open. Off the FX thread on purpose — it touches the GPU driver. The result is
	 * process-wide (the plugin registration is), so the first window that needs it pays and every
	 * later one reuses the label.</p>
	 */
	private void probeProviderAsync() {
		if (!state.modelInstalled() && !state.enabled()) {
			return;
		}
		String known = probedProvider;
		if (known != null) {
			publishProvider(known, probedProviderDetail);
			return;
		}
		if (!PROVIDER_PROBE_STARTED.compareAndSet(false, true)) {
			return;
		}
		Threads.startVirtual("gravaai-superres-provider-probe", () -> {
			String provider;
			String detail;
			try {
				var selection = dev.gravaai.media.onnx.OnnxWebGpuPlugin.probe();
				provider = selection.provider().label();
				detail = selection.detail();
			} catch (RuntimeException error) {
				// The probe itself never throws for a missing driver, but a broken native binding
				// must still not take the Settings panel down.
				LOG.log(System.Logger.Level.WARNING, "execution provider probe failed: " + error);
				provider = dev.gravaai.media.onnx.OnnxExecutionProvider.CPU.label();
				detail = error.getMessage() == null ? error.toString() : error.getMessage();
			}
			probedProvider = provider;
			probedProviderDetail = detail;
			publishProvider(provider, detail);
		});
	}

	private void publishProvider(String provider, @Nullable String detail) {
		SuperResolutionUiService.State current = state;
		if (provider.equals(current.provider()) && java.util.Objects.equals(detail,
				current.providerDetail())) {
			return;
		}
		state = current.withProvider(provider, detail);
		publish();
	}

	private void publish() {
		SuperResolutionUiService.State snapshot = state;
		Runnable notify = () -> {
			for (Consumer<SuperResolutionUiService.State> listener : listeners) {
				try {
					listener.accept(snapshot);
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.WARNING, "super-resolution listener failed: "
							+ error);
				}
			}
		};
		if (Platform.isFxApplicationThread()) {
			notify.run();
		} else {
			Platform.runLater(notify);
		}
	}

	private static String errorMessage(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.toString() : message;
	}

	/** Whether the model file is present (the UI's Remove button gate). */
	public boolean modelInstalled() {
		return assets.isInstalled();
	}

	/** The model directory, for logs and diagnostics. */
	public Path modelFile() {
		return assets.modelFile();
	}

	/** Whether a download is in flight (tests). */
	public boolean isInstalling() {
		return installing.get();
	}

	/** Creates the controller for a profile directory. */
	public static SuperResolutionController create(@Nullable AppSettingsStore settings,
			Path userDataDir) {
		SuperResAssets assets = new SuperResAssets(userDataDir);
		return new SuperResolutionController(settings, assets,
				new SuperResInstaller(new dev.gravaai.services.meeting.HttpFileDownloader(),
						assets));
	}
}
