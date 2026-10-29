package dev.gravaai.app.recording;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.media.capture.WebcamDevices;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.services.recording.CosmicOutput;
import dev.gravaai.services.recording.CosmicRecorder;
import dev.gravaai.services.recording.CosmicRecorder.RecordingOptions;
import dev.gravaai.services.recording.CosmicRecorder.StopResult;
import dev.gravaai.services.recording.CountdownDelayStore;
import dev.gravaai.services.recording.CountdownService;
import dev.gravaai.services.recording.CursorCaptureSession;
import dev.gravaai.services.recording.CursorScreen;
import dev.gravaai.services.recording.KeystrokeCapture;
import dev.gravaai.services.recording.MicLevelMeterService;
import dev.gravaai.services.recording.MicNoiseReduction;
import dev.gravaai.services.recording.MicNoiseReductionFilters;
import dev.gravaai.services.recording.RecordingEvents;
import dev.gravaai.services.recording.RecordingImportService;
import dev.gravaai.services.recording.RecordingLibrary;
import dev.gravaai.services.recording.RecordingService;
import dev.gravaai.services.recording.RecordingThumbnails;
import dev.gravaai.services.recording.ports.PlatformRecordingEnvironment;
import dev.gravaai.services.recording.ports.PlatformRecordingProcesses;
import dev.gravaai.services.recording.ports.PlatformRecordingTrash;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.sources.CaptureArea;
import dev.gravaai.services.sources.CaptureSelection;
import dev.gravaai.services.sources.CapturePickerService;
import dev.gravaai.services.sources.CapturePickerWindow;
import dev.gravaai.services.sources.DisplayOutputResolver;
import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.services.sources.SourceArea;
import dev.gravaai.services.sources.SourceListPicker;
import dev.gravaai.services.sources.SourceService;
import dev.gravaai.ui.overlays.AreaRecordingBorder;
import dev.gravaai.ui.overlays.CapturePickerOverlay;
import dev.gravaai.ui.overlays.CountdownOverlay;
import dev.gravaai.ui.overlays.RecordingOverlayHooks;
import dev.gravaai.ui.overlays.SourceSelectorOverlay;
import tools.jackson.databind.JsonNode;

/**
 * The one-block recording wiring for {@code AppContext} (plan §5.1, §5.6): constructs and owns the
 * recording services, their platform adapters and the JavaFX overlays, in dependency order.
 *
 * <p>Usage from {@code AppContext.start} (JavaFX thread, after the toolkit is up):
 * <pre>{@code
 * RecordingWiring recordingWiring = RecordingWiring.create(userData, appSettings, hud);
 * recordingWiring.start();
 * // shutdown (plan §1.1.3: recording first):
 * recordingWiring.close();
 * }</pre>
 *
 * <p>Nothing starts a capture automatically: the HUD/shortcut dispatcher calls
 * {@code wiring.recording().setRecordingState(true)} and then
 * {@code wiring.recording().startNativeScreenRecording(source, options)}.
 * {@link #startDevRecording(String)} is the manual-gate entry point.
 */
public final class RecordingWiring implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.recording.wiring");

	/** The HUD mic-meter listener holder; the meter service reads it on every update. */
	private final AtomicReference<Consumer<Double>> micLevelListener;
	/** The saved-recording listener holder; the recording service reads it on every save. */
	private final AtomicReference<Consumer<StopResult>> recordingSavedListener;

	private final RecordingsDirectory recordingsDirectory;
	private final Supplier<Path> recordingsDirSupplier;
	private final CursorScreen cursorScreen;
	private final CosmicRecorder cosmic;
	private final RecordingService recording;
	private final SourceService sources;
	private final SourceSelectorOverlay sourceSelector;
	private final CountdownDelayStore countdownDelay;
	private final CountdownOverlay countdownOverlay;
	private final CountdownService countdown;
	private final ScheduledExecutorService countdownScheduler;
	private final Supplier<CapturePickerService> capturePickerFactory;
	private final java.util.function.Function<CapturePickerService, CapturePickerOverlay> capturePickerOverlayFactory;
	private volatile @Nullable CapturePickerService capturePicker;
	private volatile @Nullable CapturePickerOverlay capturePickerOverlay;
	private final MicLevelMeterService micLevelMeter;
	private final AreaRecordingBorder areaBorder;
	private final RecordingThumbnails thumbnails;
	private final RecordingImportService importService;
	private final RecordingEvents events;
	private final AutoCloseable recordingStateSubscription;
	private final AutoCloseable selectedSourceSubscription;

	private RecordingWiring(
			AtomicReference<Consumer<Double>> micLevelListener,
			AtomicReference<Consumer<StopResult>> recordingSavedListener,
			RecordingsDirectory recordingsDirectory,
			Supplier<Path> recordingsDirSupplier,
			CursorScreen cursorScreen,
			CosmicRecorder cosmic,
			RecordingService recording,
			SourceService sources,
			SourceSelectorOverlay sourceSelector,
			CountdownDelayStore countdownDelay,
			CountdownOverlay countdownOverlay,
			CountdownService countdown,
			ScheduledExecutorService countdownScheduler,
			Supplier<CapturePickerService> capturePickerFactory,
			java.util.function.Function<CapturePickerService, CapturePickerOverlay> capturePickerOverlayFactory,
			MicLevelMeterService micLevelMeter,
			AreaRecordingBorder areaBorder,
			RecordingThumbnails thumbnails,
			RecordingImportService importService,
			RecordingEvents events,
			AutoCloseable recordingStateSubscription,
			AutoCloseable selectedSourceSubscription) {
		this.micLevelListener = micLevelListener;
		this.recordingSavedListener = recordingSavedListener;
		this.recordingsDirectory = recordingsDirectory;
		this.recordingsDirSupplier = recordingsDirSupplier;
		this.cursorScreen = cursorScreen;
		this.cosmic = cosmic;
		this.recording = recording;
		this.sources = sources;
		this.sourceSelector = sourceSelector;
		this.countdownDelay = countdownDelay;
		this.countdownOverlay = countdownOverlay;
		this.countdown = countdown;
		this.countdownScheduler = countdownScheduler;
		this.capturePickerFactory = capturePickerFactory;
		this.capturePickerOverlayFactory = capturePickerOverlayFactory;
		this.micLevelMeter = micLevelMeter;
		this.areaBorder = areaBorder;
		this.thumbnails = thumbnails;
		this.importService = importService;
		this.events = events;
		this.recordingStateSubscription = recordingStateSubscription;
		this.selectedSourceSubscription = selectedSourceSubscription;
	}

	/**
	 * Constructs every recording service and overlay. Call on the JavaFX application thread:
	 * the display adapter reads {@link Screen} and the overlays create their scenes lazily.
	 */
	public static RecordingWiring create(UserData userData, AppSettingsStore settings, HudController hud) {
		HudController hudController = hud == null ? HudController.noOp() : hud;
		RecordingsDirectory recordingsDirectory = new RecordingsDirectory(userData);
		Supplier<Path> recordingsDir = () -> {
			try {
				return recordingsDirectory.getRecordingsDir();
			} catch (IOException error) {
				throw new UncheckedIOException("Could not resolve the recordings directory", error);
			}
		};
		RecordingProcesses processes = new PlatformRecordingProcesses();
		RecordingEnvironment environment = new PlatformRecordingEnvironment();
		FxCursorScreen cursorScreen = new FxCursorScreen();

		RecordingLibrary library = new RecordingLibrary(recordingsDir, new PlatformRecordingTrash());
		RecordingEvents events = new RecordingEvents();

		// The services are mutually referential (cursor session -> selected source; source service
		// -> recording state; picker -> selector). Atomic holders break the cycles locally.
		AtomicReference<SourceService> sourcesRef = new AtomicReference<>();
		AtomicReference<RecordingService> recordingRef = new AtomicReference<>();
		AtomicReference<SourceSelectorOverlay> selectorRef = new AtomicReference<>();
		AtomicReference<SourceListPicker> listPickerRef = new AtomicReference<>();
		AtomicReference<Consumer<Double>> micLevelRef = new AtomicReference<>(level -> {
		});
		AtomicReference<Consumer<StopResult>> savedRef = new AtomicReference<>(result -> {
		});

		Supplier<SelectedSource> selectedSource = () -> {
			SourceService service = sourcesRef.get();
			return service == null ? null : service.getSelectedSource();
		};
		BooleanSupplier recordingActive =
				() -> recordingRef.get() != null && recordingRef.get().isRecordingActive();
		Supplier<String> keystrokeMode = () -> {
			JsonNode value = settings.readSetting(KeystrokeCapture.KEYSTROKE_CAPTURE_MODE_SETTING);
			return value != null && value.isString() ? value.asString() : null;
		};
		Supplier<MicNoiseReduction> micNoiseReduction = () -> {
			JsonNode value = settings.readSetting(MicNoiseReductionFilters.MIC_NOISE_REDUCTION_SETTING);
			return MicNoiseReductionFilters.normalizeMicNoiseReduction(
					value != null && value.isString() ? value.asString() : null);
		};

		CursorCaptureSession cursorSession = new CursorCaptureSession(
				processes, environment, cursorScreen, selectedSource, () -> null, keystrokeMode);
		CosmicRecorder cosmic = new CosmicRecorder(
				recordingsDir, processes, environment, events, cursorSession, cursorSession, micNoiseReduction);
		CaptureSelection captureSelection = new CaptureSelection(() -> {
			try {
				return cosmic.listCosmicOutputs();
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "Could not list COSMIC outputs: " + error);
				return List.of();
			}
		}, System.getenv());

		SourceService.SourceSelectorUi selectorUi = new SourceService.SourceSelectorUi() {
			@Override
			public void open() {
				SourceSelectorOverlay selector = selectorRef.get();
				if (selector != null) {
					selector.open();
				}
			}

			@Override
			public void focus() {
				SourceSelectorOverlay selector = selectorRef.get();
				if (selector != null) {
					selector.focus();
				}
			}

			@Override
			public void close() {
				SourceSelectorOverlay selector = selectorRef.get();
				if (selector != null) {
					selector.close();
				}
			}
		};
		SourceService sources = new SourceService(
				System.getenv(), "linux", recordingActive::getAsBoolean, captureSelection, selectorUi);
		sourcesRef.set(sources);
		SourceSelectorOverlay sourceSelector =
				new SourceSelectorOverlay(sources, recordingActive::getAsBoolean);
		selectorRef.set(sourceSelector);

		AreaRecordingBorder areaBorder = new AreaRecordingBorder();
		RecordingOverlayHooks overlayHooks = new RecordingOverlayHooks(
				hudController::hideForCapture,
				hudController::restoreAfterCapture,
				hudController::showCursor,
				areaBorder);
		RecordingService recording = new RecordingService(
				recordingsDir,
				settings,
				cosmic,
				library,
				cursorSession,
				events,
				overlayHooks,
				selectedSource,
				result -> savedRef.get().accept(result));
		recordingRef.set(recording);

		RecordingThumbnails thumbnails = new RecordingThumbnails(
				recordingsDir, () -> userData.dir(), processes, environment);
		recording.setThumbnails(thumbnails);
		RecordingImportService importService = new RecordingImportService(
				recordingsDir, processes, environment, library);
		recording.setImportService(importService);

		CountdownDelayStore countdownDelay = new CountdownDelayStore(userData.countdownSettingsFile());
		CountdownOverlay countdownOverlay = new CountdownOverlay();
		ScheduledExecutorService countdownScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "gravaai-countdown");
			thread.setDaemon(true);
			return thread;
		});
		CountdownService countdown = new CountdownService(
				countdownOverlay,
				hudController::setCountdown,
				CountdownService.Scheduler.of(countdownScheduler));

		Supplier<List<SourceArea.PickerDisplay>> pickerDisplays = () -> {
			List<SourceArea.PickerDisplay> displays = new ArrayList<>();
			for (CursorScreen.Display display : cursorScreen.allDisplays()) {
				displays.add(new SourceArea.PickerDisplay(
						display.id(),
						new CaptureArea(display.x(), display.y(), display.width(), display.height())));
			}
			return displays;
		};
		Supplier<List<CapturePickerWindow>> pickableWindows = List::of;
		Supplier<double[]> cursorPoint = () -> {
			CursorScreen.Point point = cursorScreen.cursorScreenPoint();
			return new double[] {point.x(), point.y()};
		};
		Supplier<SourceListPicker> listPicker = () -> {
			SourceListPicker picker = new SourceListPicker(sourceSelector::open);
			listPickerRef.set(picker);
			return picker;
		};
		// The capture picker (and its overlay) is not needed to show the HUD: build it on first use
		// so it never sits on the startup path (COSMIC uses the click-pick path, not the overlay).
		Supplier<CapturePickerService> capturePickerFactory = () -> new CapturePickerService(
				System.getenv(),
				"linux",
				recordingActive::getAsBoolean,
				cosmic::waitForCosmicOutputClick,
				sources,
				pickerDisplays,
				pickableWindows,
				cursorPoint,
				listPicker);
		java.util.function.Function<CapturePickerService, CapturePickerOverlay> capturePickerOverlayFactory =
				picker -> {
					List<CapturePickerOverlay.DisplayOverlay> overlayDisplays = new ArrayList<>();
					for (Screen screen : FxCursorScreen.screens()) {
						overlayDisplays.add(
								new CapturePickerOverlay.DisplayOverlay(FxCursorScreen.idOf(screen), screen));
					}
					return new CapturePickerOverlay(picker, overlayDisplays);
				};

		MicLevelMeterService micLevelMeter = new MicLevelMeterService(level -> {
			Consumer<Double> listener = micLevelRef.get();
			if (listener != null) {
				listener.accept(level);
			}
		});

		AutoCloseable recordingStateSubscription = events.onRecordingStateChanged(state ->
				Platform.runLater(() -> sourceSelector.recordingStateChanged(state.recording())));
		AutoCloseable selectedSourceSubscription = sources.onSelectedSourceChanged(selected -> {
			SourceListPicker picker = listPickerRef.get();
			if (picker != null && picker.isOpen()) {
				picker.complete(selected);
			}
		});

		return new RecordingWiring(
				micLevelRef,
				savedRef,
				recordingsDirectory,
				recordingsDir,
				cursorScreen,
				cosmic,
				recording,
				sources,
				sourceSelector,
				countdownDelay,
				countdownOverlay,
				countdown,
				countdownScheduler,
				capturePickerFactory,
				capturePickerOverlayFactory,
				micLevelMeter,
				areaBorder,
				thumbnails,
				importService,
				events,
				recordingStateSubscription,
				selectedSourceSubscription);
	}

	// ── Public API (one block for AppContext) ──────────────────────────────

	public RecordingService recording() {
		return recording;
	}

	public SourceService sources() {
		return sources;
	}

	public CountdownService countdown() {
		return countdown;
	}

	public SourceSelectorOverlay sourceSelector() {
		return sourceSelector;
	}

	/** Countdown delay setting (Appendix A get/set-countdown-delay). */
	public CountdownDelayStore countdownDelay() {
		return countdownDelay;
	}

	/** The mic level meter; the HUD opens it while its menu is visible. */
	public MicLevelMeterService micLevelMeter() {
		return micLevelMeter;
	}

	/** The COSMIC/source click-picker used by the HUD "pick screen" action (built on first use). */
	public CapturePickerService capturePicker() {
		CapturePickerService picker = capturePicker;
		if (picker == null) {
			synchronized (this) {
				picker = capturePicker;
				if (picker == null) {
					picker = capturePickerFactory.get();
					capturePicker = picker;
				}
			}
		}
		return picker;
	}

	/** The capture-picker overlay (FX thread; not used by the COSMIC click path). */
	public CapturePickerOverlay capturePickerOverlay() {
		CapturePickerOverlay overlay = capturePickerOverlay;
		if (overlay != null) {
			return overlay;
		}
		if (!Platform.isFxApplicationThread()) {
			throw new IllegalStateException("The capture-picker overlay must be created on the JavaFX thread");
		}
		synchronized (this) {
			overlay = capturePickerOverlay;
			if (overlay == null) {
				overlay = capturePickerOverlayFactory.apply(capturePicker());
				capturePickerOverlay = overlay;
			}
		}
		return overlay;
	}

	/** The recordings folder supplier (honours the custom directory setting). */
	public Supplier<Path> recordingsDir() {
		return recordingsDirSupplier;
	}

	public AreaRecordingBorder areaRecordingBorder() {
		return areaBorder;
	}

	public RecordingThumbnails thumbnails() {
		return thumbnails;
	}

	public RecordingImportService imports() {
		return importService;
	}

	public CosmicRecorder cosmicRecorder() {
		return cosmic;
	}

	/** Events for tray/UI listeners ({@code recording-state-changed}, stop requested, ...). */
	public RecordingEvents events() {
		return events;
	}

	/** The HUD/other services subscribe to the 0..100 microphone level here. */
	public void onMicLevel(Consumer<Double> listener) {
		micLevelListener.set(listener == null ? level -> {
		} : listener);
	}

	/**
	 * The AppContext listener for saved recordings: map to
	 * {@code AutomationTriggers.RecordingSavedResult} and register meeting recordings.
	 */
	public void onRecordingSaved(Consumer<StopResult> listener) {
		recordingSavedListener.set(listener == null ? result -> {
		} : listener);
	}

	/**
	 * Initialises the wiring (plan P1.6/P1.8/P1.9). Nothing records yet: the HUD/shortcut
	 * dispatcher starts a capture through {@link #recording()}.
	 */
	public void start() {
		// Deliberately cheap: this runs after the startup window is visible. Device enumeration
		// (PulseAudio/cameras) is lazy in HudRecordingPortAdapter until the HUD menu opens.
		try {
			Path recordings = recordingsDirectory.getRecordingsDir();
			LOG.log(System.Logger.Level.INFO, "recording wiring ready; recordings in " + recordings);
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "recordings directory unavailable: " + error);
		}
	}

	/**
	 * Manual-gate helper: records the named COSMIC output (or the first one) with system audio and
	 * microphone. Never called from normal UI flows.
	 */
	public CosmicRecorder.StartResult startDevRecording(String outputName) throws IOException {
		List<CosmicOutput> outputs = cosmic.listCosmicOutputs();
		CosmicOutput output = outputs.stream()
				.filter(candidate -> outputName == null || candidate.name().equals(outputName))
				.findFirst()
				.orElse(outputs.isEmpty() ? null : outputs.get(0));
		if (output == null) {
			return CosmicRecorder.StartResult.failed("No COSMIC outputs found.");
		}
		List<DisplayOutputResolver.DisplayInfo> displays = new ArrayList<>();
		for (CursorScreen.Display display : cursorScreen.allDisplays()) {
			displays.add(new DisplayOutputResolver.DisplayInfo(
					display.id(),
					new CaptureArea(display.x(), display.y(), display.width(), display.height())));
		}
		SelectedSource source = CaptureSelection.createCosmicScreenSource(output, displays);
		return recording.startNativeScreenRecording(
				source, new RecordingOptions(true, true, null, null, false));
	}

	/** Manual-gate helper: stops the dev recording started by {@link #startDevRecording}. */
	public StopResult stopDevRecording() {
		return recording.stopNativeScreenRecording();
	}

	// ── Shutdown ───────────────────────────────────────────────────────────

	@Override
	public void close() {
		LOG.log(System.Logger.Level.INFO, "closing recording wiring");
		if (recording.isRecordingActive()) {
			try {
				StopResult result = recording.stopNativeScreenRecording();
				LOG.log(System.Logger.Level.INFO, "recording finalised on shutdown: " + result);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "could not finalise the recording on shutdown: " + error);
			}
		}
		closeQuietly(recordingStateSubscription);
		closeQuietly(selectedSourceSubscription);
		sourceSelector.close();
		CapturePickerOverlay pickerOverlay = capturePickerOverlay;
		if (pickerOverlay != null) {
			pickerOverlay.close();
		}
		countdown.cancel();
		countdownOverlay.closeActive();
		countdownScheduler.shutdownNow();
		areaBorder.hide();
		micLevelMeter.close();
		thumbnails.close();
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING, "could not close a recording subscription: " + error);
		}
	}

	// ── JavaFX Screen adapter ──────────────────────────────────────────────

	/**
	 * {@link CursorScreen} over JavaFX {@link Screen} (plan §5.5: JavaFX Screen for geometry).
	 *
	 * <p>Display ids are the 1-based position in {@code Screen.getScreens()}, stable for the app
	 * run and shared with the capture picker. There is no global pointer API in JavaFX: the real
	 * cursor position comes from {@code gravaai-wayland-cursor} while a recording runs (the cursor
	 * session backfills the early samples); this fallback is only used before the first sample.
	 */
	private static final class FxCursorScreen implements CursorScreen {

		static List<Screen> screens() {
			return List.copyOf(Screen.getScreens());
		}

		static int idOf(Screen screen) {
			int index = screens().indexOf(screen);
			return index < 0 ? 0 : index + 1;
		}

		private static CursorScreen.Display toDisplay(Screen screen) {
			Rectangle2D bounds = screen.getBounds();
			return new CursorScreen.Display(
					idOf(screen),
					bounds.getMinX(),
					bounds.getMinY(),
					bounds.getWidth(),
					bounds.getHeight(),
					screen.getOutputScaleX());
		}

		@Override
		public CursorScreen.Point cursorScreenPoint() {
			// No JavaFX API for the global pointer; the wayland cursor helper supplies the real
			// position during a recording. Before its first POS line, use the primary centre.
			Rectangle2D bounds = Screen.getPrimary().getBounds();
			return new CursorScreen.Point(
					bounds.getMinX() + bounds.getWidth() / 2,
					bounds.getMinY() + bounds.getHeight() / 2);
		}

		@Override
		public List<CursorScreen.Display> allDisplays() {
			List<CursorScreen.Display> displays = new ArrayList<>();
			for (Screen screen : screens()) {
				displays.add(toDisplay(screen));
			}
			return List.copyOf(displays);
		}

		@Override
		public CursorScreen.Display primaryDisplay() {
			return toDisplay(Screen.getPrimary());
		}

		@Override
		public CursorScreen.@Nullable Display displayNearestPoint(double x, double y) {
			for (Screen screen : screens()) {
				Rectangle2D bounds = screen.getBounds();
				if (x >= bounds.getMinX() && x < bounds.getMaxX()
						&& y >= bounds.getMinY() && y < bounds.getMaxY()) {
					return toDisplay(screen);
				}
			}
			Screen nearest = null;
			double nearestDistance = Double.POSITIVE_INFINITY;
			for (Screen screen : screens()) {
				Rectangle2D bounds = screen.getBounds();
				double dx = x - (bounds.getMinX() + bounds.getWidth() / 2);
				double dy = y - (bounds.getMinY() + bounds.getHeight() / 2);
				double distance = dx * dx + dy * dy;
				if (distance < nearestDistance) {
					nearestDistance = distance;
					nearest = screen;
				}
			}
			return nearest == null ? null : toDisplay(nearest);
		}
	}
}
