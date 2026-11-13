package dev.gravaai.services.recording.cursor;

import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.services.recording.KeystrokeCapture;
import dev.gravaai.services.recording.KeystrokeCaptureMode;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * The {@code gravaai-wayland-cursor} child process and its line protocol, ported from
 * {@code electron/ipc/cursor/telemetry.ts} and {@code electron/ipc/cursor/interaction.ts}.
 *
 * <p>One helper serves pointer positions, mouse buttons and (only when a capture mode asks for it)
 * key presses. Its stdout is a stream of {@code POS:x:y}, {@code INTERACTION:...} and
 * {@code KEY:...} lines; anything else is ignored. Keys stay in memory here and are handed to the
 * recorder once, when the recording is saved.
 */
final class CursorHelperSession {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	private static final Pattern POSITION_LINE = Pattern.compile("^POS:(-?\\d+):(-?\\d+)$");
	private static final Pattern INTERACTION_LINE =
			Pattern.compile("^INTERACTION:(mousedown|mouseup)(?::([123]))?$");

	/** Receives the helper's decoded protocol lines. */
	interface Listener {

		/** One {@code POS} reading, in the helper's logical coordinates. */
		void onPosition(long logicalX, long logicalY);

		/** One raw {@code KEY:...} line, already known to be one. */
		void onKeyLine(String line);

		/**
		 * One mouse-button interaction.
		 *
		 * @param mouseDown true for {@code mousedown}, false for {@code mouseup}
		 * @param button the normalized button (1/2/3), or null when the helper did not say
		 */
		void onInteraction(boolean mouseDown, @Nullable Integer button);
	}

	private final RecordingProcesses processes;
	private final RecordingEnvironment environment;
	private final Supplier<String> keystrokeCaptureModeSetting;
	/** True when key lines are wanted beyond the keystroke capture setting (push-to-talk's hold). */
	private final BooleanSupplier keysWanted;

	private volatile KeystrokeCapture.@Nullable KeystrokeRecorder keystrokeRecorder;
	private volatile @Nullable SpawnedProcess helper;
	private volatile boolean helperActive;
	private volatile @Nullable Listener listener;

	CursorHelperSession(
			RecordingProcesses processes,
			RecordingEnvironment environment,
			Supplier<String> keystrokeCaptureModeSetting,
			BooleanSupplier keysWanted) {
		this.processes = processes;
		this.environment = environment;
		this.keystrokeCaptureModeSetting = keystrokeCaptureModeSetting;
		this.keysWanted = keysWanted;
	}

	void setListener(Listener listener) {
		this.listener = listener;
	}

	/** Returns and clears the presses the helper reported. */
	List<KeystrokeEvent> takeRecordedKeystrokes() {
		KeystrokeCapture.KeystrokeRecorder recorder = keystrokeRecorder;
		return recorder == null ? List.of() : recorder.takeEvents();
	}

	/**
	 * Feeds one {@code KEY:...} line to the recorder, if key capture is on.
	 *
	 * @param record false while the recording is paused: modifier state keeps tracking so a Ctrl
	 *     released during a pause cannot stick, but no press is stored
	 */
	void feedKeyLine(String line, long timeMs, boolean record) {
		KeystrokeCapture.KeystrokeRecorder recorder = keystrokeRecorder;
		if (recorder != null) {
			recorder.handleLine(line, timeMs, record);
		}
	}

	/** Starts the helper, asking for key presses when the setting or the mic gate needs them. */
	synchronized void start() {
		stop();
		// Privacy first: keys are off unless the user picked a mode — or enabled push-to-talk,
		// which needs the key's press and release to gate the microphone.
		KeystrokeCaptureMode mode =
				KeystrokeCapture.normalizeKeystrokeCaptureMode(keystrokeCaptureModeSetting.get());
		keystrokeRecorder = mode == KeystrokeCaptureMode.OFF
				? null
				: KeystrokeCapture.createKeystrokeRecorder(mode);
		KeystrokeCapture.setActiveKeystrokeRecorder(keystrokeRecorder);
		List<String> command = new ArrayList<>();
		command.add(environment.cursorHelper().toString());
		if (keystrokeRecorder != null || keysWanted.getAsBoolean()) {
			command.add("--keys");
		}
		try {
			helper = processes.spawn(command, new SpawnOptions(true, true, true));
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Wayland cursor helper failed: " + error);
			helper = null;
			return;
		}
		helperActive = true;
		SpawnedProcess currentHelper = helper;
		Thread.ofPlatform().daemon().name("gravaai-wayland-cursor").start(() -> readHelper(currentHelper));
		drainErrors(currentHelper);
	}

	/** Stops the helper process. */
	synchronized void stop() {
		helperActive = false;
		SpawnedProcess currentHelper = helper;
		helper = null;
		if (currentHelper != null) {
			closeStdin(currentHelper);
			currentHelper.destroy();
		}
	}

	/** True while the helper child process is running. */
	boolean isHelperActive() {
		return helperActive;
	}

	private void readHelper(SpawnedProcess process) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.stdout(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				dispatch(line);
			}
		} catch (IOException error) {
			// Helper exited.
		}
	}

	private void dispatch(String line) {
		Listener target = listener;
		if (target == null) {
			return;
		}
		Matcher position = POSITION_LINE.matcher(line);
		if (position.matches()) {
			target.onPosition(Long.parseLong(position.group(1)), Long.parseLong(position.group(2)));
			return;
		}
		if ((keystrokeRecorder != null || keysWanted.getAsBoolean()) && line.startsWith("KEY:")) {
			target.onKeyLine(line);
			return;
		}
		Matcher interaction = INTERACTION_LINE.matcher(line);
		if (!interaction.matches()) {
			return;
		}
		boolean mouseDown = !"mouseup".equals(interaction.group(1));
		target.onInteraction(mouseDown, interaction.group(2) == null
				? null
				: Integer.valueOf(interaction.group(2)));
	}

	private static void drainErrors(SpawnedProcess process) {
		var stderr = process.stderr();
		if (stderr == null) {
			return;
		}
		Thread.ofPlatform().daemon().name("gravaai-wayland-cursor-err").start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					LOG.log(System.Logger.Level.WARNING, "Wayland cursor helper: " + line.trim());
				}
			} catch (IOException error) {
				// Helper exited.
			}
		});
	}

	private static void closeStdin(SpawnedProcess process) {
		try {
			OutputStream stdin = process.stdin();
			if (stdin != null) {
				stdin.close();
			}
		} catch (IOException error) {
			// Already closed.
		}
	}
}
