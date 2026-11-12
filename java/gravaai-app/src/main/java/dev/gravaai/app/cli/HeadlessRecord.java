package dev.gravaai.app.cli;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

import dev.gravaai.app.CliCommand;
import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.core.storage.StorageFiles;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.platform.compositor.DesktopSession;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.services.recording.CosmicOutput;
import dev.gravaai.services.recording.CosmicSources;
import dev.gravaai.services.recording.CursorScreen;
import dev.gravaai.services.recording.KeystrokeCapture;
import dev.gravaai.services.recording.MicNoiseReductionFilters;
import dev.gravaai.services.recording.RecordingEvents;
import dev.gravaai.services.recording.RecordingFrameRate;
import dev.gravaai.services.recording.RecordingLibrary;
import dev.gravaai.services.recording.RecordingQuality;
import dev.gravaai.services.recording.RecordingSessionManifest;
import dev.gravaai.services.recording.WindowSources;
import dev.gravaai.services.recording.cursor.CursorCaptureSession;
import dev.gravaai.services.recording.cosmic.CosmicRecorder;
import dev.gravaai.services.recording.cosmic.CosmicRecorder.RecordingOptions;
import dev.gravaai.services.recording.cosmic.CosmicRecorder.StartResult;
import dev.gravaai.services.recording.cosmic.CosmicRecorder.StopResult;
import dev.gravaai.services.recording.ports.CompositorRecordingEnvironment;
import dev.gravaai.services.recording.ports.PlatformRecordingEnvironment;
import dev.gravaai.services.recording.ports.PlatformRecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.sources.CaptureArea;
import dev.gravaai.services.sources.CaptureSelection;
import dev.gravaai.services.sources.DisplayOutputResolver;
import dev.gravaai.services.sources.SelectedSource;
import tools.jackson.databind.JsonNode;

/**
 * {@code gravaai record --duration 30 [--out demo.mp4]}: capture the screen without the HUD (card
 * "Headless CLI").
 *
 * <p>It drives the same {@link CosmicRecorder} the app uses, so the capture helper is still
 * {@code gravaai-wayland-capture} (ext-image-copy-capture) or {@code gravaai-portal-capture}
 * depending on the session — and on a portal compositor the desktop's own screen-sharing dialog is
 * still the picker. The CLI only replaces what needs a window:
 *
 * <ul>
 *   <li>{@link HelperDisplay} answers the cursor mapper's "which monitor is this?" question from the
 *       capture helper's own output geometry instead of the JavaFX {@code Screen} API.
 *   <li>There is no HUD to hide, no area border to show and no cursor to restore.
 *   <li>{@code SIGINT}/{@code SIGTERM} stops and finalises, like the HUD's stop button: a script's
 *       {@code kill} yields a usable file rather than a recoverable session.
 * </ul>
 *
 * <p>Recording settings (mic noise reduction, frame rate, quality, keystroke capture) are read from
 * {@code app-settings.json}, the values the HUD's recorder reads, so a scripted take has the same
 * shape as an interactive one.
 */
final class HeadlessRecord {

	private static final System.Logger LOG = System.getLogger("gravaai.cli.record");

	private final CliEvents events;

	HeadlessRecord(CliEvents events) {
		this.events = events;
	}

	/**
	 * Runs one recording to completion.
	 *
	 * @param request the parsed request
	 * @param userData the user-data directory (recordings folder, settings)
	 * @return the process exit code: 0 when the file was written, 1 when it was not
	 */
	int run(CliCommand.Record request, UserData userData) {
		try {
			Path recordingsDir = new RecordingsDirectory(userData).getRecordingsDir();
			AppSettingsStore settings = new AppSettingsStore(userData.appSettingsFile());
			RecordingProcesses processes = new PlatformRecordingProcesses();
			RecordingEnvironment environment = new CompositorRecordingEnvironment(
					new PlatformRecordingEnvironment(), DesktopSession.current());

			// The recorder owns the output list (and thus the helper's --list contract); it needs a
			// cursor session, which needs the selected source, which needs that list. The holder
			// breaks the cycle the same way RecordingWiring does, without a second list path.
			AtomicReference<SelectedSource> selected = new AtomicReference<>();
			try (CursorCaptureSession cursorSession = new CursorCaptureSession(processes,
					environment, new HelperDisplay(environment), selected::get,
					() -> stringOf(settings.readSetting(
							KeystrokeCapture.KEYSTROKE_CAPTURE_MODE_SETTING)));
					CosmicRecorder recorder = new CosmicRecorder(() -> recordingsDir, processes,
							environment, new RecordingEvents(), cursorSession, cursorSession,
							() -> setting(settings,
									MicNoiseReductionFilters.MIC_NOISE_REDUCTION_SETTING,
									MicNoiseReductionFilters::normalizeMicNoiseReduction),
							() -> setting(settings, RecordingFrameRate.CAPTURE_FPS_SETTING,
									value -> RecordingFrameRate.normalize(stringOf(value))),
							() -> setting(settings, RecordingQuality.VIDEO_QUALITY_SETTING,
									value -> RecordingQuality.normalize(stringOf(value))))) {
				return record(request, recorder, cursorSession, selected);
			}
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "headless record failed: " + error);
			return fail(message(error));
		}
	}

	/** Starts, waits for and finalises one capture on an open recorder. */
	private int record(CliCommand.Record request, CosmicRecorder recorder,
			CursorCaptureSession cursorSession, AtomicReference<SelectedSource> selected)
			throws IOException {
		SelectedSource source;
		String label;
		if (request.window().isPresent()) {
			SelectedSource window = pickWindow(recorder, request.window().get());
			if (window == null) {
				return fail("no window matches \"" + request.window().get() + "\"");
			}
			source = window;
			label = window.name();
		} else {
			List<CosmicOutput> outputs = recorder.listCosmicOutputs();
			Optional<CosmicOutput> target = pickOutput(outputs, request.screen());
			if (target.isEmpty()) {
				return fail(outputs.isEmpty()
						? "no screen outputs found; the capture helper reported none (is a Wayland "
								+ "session running?)"
						: "no output named \"" + request.screen().orElse("") + "\"; available: "
								+ outputs.stream().map(CosmicOutput::name).toList());
			}
			source = CaptureSelection.createCosmicScreenSource(target.get(), displayInfos(outputs));
			label = target.get().name();
		}
		selected.set(source);

		StartResult started = recorder.startCosmicRecording(source,
				new RecordingOptions(request.systemAudio(), request.microphone(), null, null,
						false));
		if (!started.success()) {
			return fail("could not start the recording: " + started.message());
		}
		// The HUD starts sampling once the recorder has grabbed the first frame; the sidecar writer
		// shifts the samples by the difference, so this order anchors the cursor.
		cursorSession.start();

		events.emit("record_start", Map.of(
				"source", label,
				"window", source.sourceType() == dev.gravaai.services.sources.SourceType.WINDOW,
				"durationSeconds", request.duration().orElse(0L),
				"systemAudio", request.systemAudio(),
				"microphone", request.microphone()));
		events.note("recording " + label + " ("
				+ request.duration().map(seconds -> seconds + " s").orElse("until interrupted")
				+ ")");

		if (awaitStop(request.duration())) {
			events.note("stopping: finalising the recording");
		}
		StopResult result = recorder.stopCosmicRecording();
		cursorSession.stop();
		if (!result.success() || result.path() == null) {
			return fail("could not finalise the recording: " + result.error());
		}
		Path saved = Path.of(result.path());
		if (request.out().isPresent()) {
			Path destination = request.out().get().toAbsolutePath().normalize();
			moveRecordingBundle(saved, destination);
			saved = destination;
		}
		Map<String, Object> facts = new LinkedHashMap<>();
		facts.put("cursorTelemetry",
				Files.isRegularFile(Path.of(saved + StorageFiles.CURSOR_TELEMETRY_SUFFIX)));
		facts.put("keystrokes",
				Files.isRegularFile(Path.of(saved + StorageFiles.KEYSTROKE_TELEMETRY_SUFFIX)));
		events.result(saved, facts);
		events.note("saved " + saved);
		return 0;
	}

	private int fail(String message) {
		events.emit("error", Map.of("message", message));
		events.note("gravaai record: " + message);
		return 1;
	}

	private static String message(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.toString() : message;
	}

	/** A stored setting through its own normaliser; a missing key reads as the normalised default. */
	private static <T> T setting(AppSettingsStore settings, String key,
			Function<JsonNode, T> normaliser) {
		return normaliser.apply(settings.readSetting(key));
	}

	private static String stringOf(JsonNode value) {
		return value != null && value.isString() ? value.asString() : "";
	}

	/**
	 * Waits for the duration or for a signal, answering true when a signal stopped it early.
	 *
	 * <p>The handler is a shutdown hook so {@code Ctrl-C} and {@code kill} behave like the HUD's stop
	 * button: the recording is finalised before the JVM exits.
	 */
	private static boolean awaitStop(Optional<Long> durationSeconds) {
		CountDownLatch stop = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread hook = new Thread(() -> {
			interrupted.set(true);
			stop.countDown();
		}, "gravaai-cli-record-stop");
		Runtime.getRuntime().addShutdownHook(hook);
		try {
			if (durationSeconds.isPresent()) {
				stop.await(durationSeconds.get(), TimeUnit.SECONDS);
			} else {
				stop.await();
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			interrupted.set(true);
		} finally {
			try {
				Runtime.getRuntime().removeShutdownHook(hook);
			} catch (IllegalStateException shuttingDown) {
				// The JVM is already exiting: the hook either ran or is about to.
			}
		}
		return interrupted.get();
	}

	/**
	 * The window a {@code --window} request names.
	 *
	 * <p>The argument is matched against the compositor's stable identifiers first (the exact
	 * spelling {@code --list-windows} reports, with or without the {@code window:} prefix), then
	 * against each window's title and app id. A title can repeat, so an ambiguous match is refused
	 * instead of picking one of the candidates: a script that records the wrong window is worse
	 * than a script that stops.
	 *
	 * @return the window source, or null when nothing matches
	 * @throws IOException when several windows match, listing them
	 */
	private static @Nullable SelectedSource pickWindow(CosmicRecorder recorder, String requested)
			throws IOException {
		List<WindowSources.CaptureWindow> windows = recorder.listCaptureWindows();
		if (windows.isEmpty()) {
			return null;
		}
		String wanted = requested;
		if (requested.startsWith(WindowSources.WINDOW_SOURCE_PREFIX)) {
			String identifier = WindowSources.windowIdentifier(requested);
			if (identifier != null) {
				wanted = identifier;
			}
		}
		for (WindowSources.CaptureWindow window : windows) {
			if (window.identifier().equals(wanted)) {
				return CaptureSelection.asWindowSource(window);
			}
		}
		String needle = wanted.toLowerCase(Locale.ROOT);
		List<WindowSources.CaptureWindow> matches = new ArrayList<>();
		for (WindowSources.CaptureWindow window : windows) {
			boolean titleMatches = window.title().toLowerCase(Locale.ROOT).contains(needle);
			boolean appMatches = window.appId() != null
					&& window.appId().toLowerCase(Locale.ROOT).contains(needle);
			if (titleMatches || appMatches) {
				matches.add(window);
			}
		}
		if (matches.size() > 1) {
			throw new IOException("several windows match \"" + requested + "\": "
					+ matches.stream().map(WindowSources::label).toList()
					+ " (use an identifier from --list-windows)");
		}
		return matches.size() == 1 ? CaptureSelection.asWindowSource(matches.getFirst()) : null;
	}

	/** The requested output by name, the first one when unnamed, or empty when the name is gone. */
	private static Optional<CosmicOutput> pickOutput(List<CosmicOutput> outputs,
			Optional<String> screen) {
		if (screen.isPresent()) {
			String requested = screen.get();
			String wanted = requested.startsWith(CosmicSources.COSMIC_SOURCE_PREFIX)
					? CosmicSources.outputNameForSourceId(requested)
					: requested;
			return outputs.stream().filter(output -> output.name().equals(wanted)).findFirst();
		}
		return outputs.stream().findFirst();
	}

	/** Pixel geometry per output, in the helper's order (display ids are 1-based positions). */
	private static List<DisplayOutputResolver.DisplayInfo> displayInfos(List<CosmicOutput> outputs) {
		return IntStream.range(0, outputs.size())
				.mapToObj(index -> {
					CosmicOutput output = outputs.get(index);
					return new DisplayOutputResolver.DisplayInfo(index + 1, new CaptureArea(
							output.x(), output.y(), output.width(), output.height()));
				})
				.toList();
	}

	/**
	 * Moves the finished recording and every sidecar that belongs to it to {@code destination}.
	 *
	 * <p>The recorder names its output {@code recording-<timestamp>.mp4} because that name keys its
	 * temp files and crash-recovery manifest, so {@code --out} is honoured by moving the finished
	 * bundle afterwards. {@link RecordingLibrary#belongsToRecording} decides what that bundle is; the
	 * sidecars then take the destination's stem and keep the rest of their names.
	 */
	static void moveRecordingBundle(Path saved, Path destination) throws IOException {
		Path normalizedSaved = saved.toAbsolutePath().normalize();
		Path normalizedDestination = destination.toAbsolutePath().normalize();
		if (normalizedSaved.equals(normalizedDestination)) {
			return;
		}
		String savedName = normalizedSaved.getFileName().toString();
		String destinationName = normalizedDestination.getFileName().toString();
		Path parent = normalizedDestination.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		List<String> sidecars = new java.util.ArrayList<>();
		Path savedDir = normalizedSaved.getParent();
		if (savedDir != null) {
			try (var entries = Files.list(savedDir)) {
				for (Path entry : entries.toList()) {
					String name = entry.getFileName().toString();
					if (Files.isRegularFile(entry) && !name.equals(savedName)
							&& RecordingLibrary.belongsToRecording(name, savedName)) {
						sidecars.add(name);
					}
				}
			}
		}
		Files.move(normalizedSaved, normalizedDestination, StandardCopyOption.REPLACE_EXISTING);
		for (String name : sidecars) {
			Path sidecar = savedDir.resolve(name);
			Path renamed = parent == null ? Path.of(rename(name, savedName, destinationName))
					: parent.resolve(rename(name, savedName, destinationName));
			Files.move(sidecar, renamed, StandardCopyOption.REPLACE_EXISTING);
		}
		rewriteSessionManifest(normalizedDestination);
	}

	/**
	 * Rewrites the moved session manifest so it names the recording's new file.
	 *
	 * <p>The manifest stores {@code videoFileName}, which the move just changed. Reading it back and
	 * persisting it again through {@link RecordingSessionManifest} is the same write the HUD does
	 * when a take ends with a webcam track, so the link keeps resolving after the move.
	 */
	private static void rewriteSessionManifest(Path recording) throws IOException {
		var session = RecordingSessionManifest.resolveRecordingSessionManifest(
				recording.toString());
		if (session != null) {
			RecordingSessionManifest.persistRecordingSessionManifest(session);
		}
	}

	/**
	 * The sidecar's new name: the recording's name replaced by the destination's, keeping the rest.
	 *
	 * <p>A recording's files come in two namings. Telemetry is written next to the video's file name
	 * ({@code recording-1.mp4.cursor.json}), while the audio captures, the audio manifest, the
	 * session manifest and the peaks cache use its stem ({@code recording-1.mic.wav}); a sidecar
	 * keeps whichever base it had, so moving a take never changes how its sidecars are spelled.
	 */
	static String rename(String sidecarName, String savedName, String destinationName) {
		if (sidecarName.startsWith(savedName)) {
			return destinationName + sidecarName.substring(savedName.length());
		}
		String savedStem = stemOf(savedName);
		if (sidecarName.startsWith(savedStem)) {
			return stemOf(destinationName) + sidecarName.substring(savedStem.length());
		}
		return sidecarName;
	}

	private static String stemOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	/**
	 * {@link CursorScreen} over the capture helper's output geometry: the headless stand-in for the
	 * JavaFX {@code Screen} adapter.
	 *
	 * <p>Only the display a screen source covers matters to the cursor mapper, and the helper lists
	 * every output's logical geometry, so the mapping is the compositor's own. A pointer position
	 * always comes from the native helper while a recording runs; the centre fallback only applies
	 * before its first sample.
	 */
	private static final class HelperDisplay implements CursorScreen {

		private final List<Display> displays;

		/**
		 * @param environment the session's helpers; its {@code --list} output is the geometry source
		 */
		HelperDisplay(RecordingEnvironment environment) {
			this.displays = probe(environment);
			if (displays.isEmpty()) {
				LOG.log(System.Logger.Level.WARNING,
						"no displays from " + environment.captureHelper()
								+ "; cursor positions fall back to the frame centre");
			}
		}

		/** The helper's {@code OUTPUT name x y w h} lines as displays. */
		private static List<Display> probe(RecordingEnvironment environment) {
			List<CosmicOutput> outputs;
			try {
				var result = dev.gravaai.platform.process.ProcessRunner
						.of(List.of(environment.captureHelper().toString(), "--list"))
						.timeout(java.time.Duration.ofSeconds(10)).run();
				outputs = CosmicSources.parseOutputs(result.stdout());
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "could not read the display geometry: " + error);
				return List.of();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				return List.of();
			}
			return IntStream.range(0, outputs.size())
					.mapToObj(index -> {
						CosmicOutput output = outputs.get(index);
						return new Display(index + 1, output.x(), output.y(), output.width(),
								output.height(), 1);
					})
					.toList();
		}

		@Override
		public Point cursorScreenPoint() {
			Display primary = primaryDisplay();
			return new Point(primary.x() + primary.width() / 2,
					primary.y() + primary.height() / 2);
		}

		@Override
		public List<Display> allDisplays() {
			return displays;
		}

		@Override
		public Display primaryDisplay() {
			return displays.isEmpty() ? new Display(1, 0, 0, 1, 1, 1) : displays.getFirst();
		}

		@Override
		public Display displayNearestPoint(double x, double y) {
			return displays.stream()
					.filter(display -> x >= display.x() && x < display.x() + display.width()
							&& y >= display.y() && y < display.y() + display.height())
					.findFirst()
					.orElseGet(this::primaryDisplay);
		}
	}
}
