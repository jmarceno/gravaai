package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.services.recording.CosmicOutput;
import dev.gravaai.services.recording.CosmicSources;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * Lists COSMIC outputs and picks one by clicking it, ported from
 * {@code electron/ipc/register/capturePicker.ts}.
 *
 * <p>The portal screencast is unreliable on COSMIC, so the user clicks anywhere on the screen they
 * want and the output under the pointer is recorded directly. The helper reports {@code POS} and
 * {@code INTERACTION:mousedown} lines; the click settles the pick.
 */
final class CosmicOutputPicker {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	private static final Pattern POSITION_LINE = Pattern.compile("^POS:(-?\\d+):(-?\\d+)$");

	private final RecordingEnvironment environment;
	private final RecordingProcesses processes;
	private final CosmicProcessIo processIo;
	private final CosmicDiskGuard timer;

	CosmicOutputPicker(
			RecordingEnvironment environment,
			RecordingProcesses processes,
			CosmicProcessIo processIo,
			CosmicDiskGuard timer) {
		this.environment = environment;
		this.processes = processes;
		this.processIo = processIo;
		this.timer = timer;
	}

	/** Lists the COSMIC outputs by running {@code gravaai-wayland-capture --list}. */
	List<CosmicOutput> listOutputs() throws IOException {
		byte[] stdout = processIo.runAndCapture(List.of(
				environment.captureHelper().toString(), "--list"));
		return new ArrayList<>(CosmicSources.parseOutputs(new String(stdout, StandardCharsets.UTF_8)));
	}

	/**
	 * Waits for the next mouse click anywhere and resolves with the output under it.
	 *
	 * @return a future with the picked output, or null when nothing was picked (timeout, no click,
	 *     helper failure)
	 */
	CompletableFuture<@Nullable CosmicOutput> waitForClick(long timeoutMs) {
		CompletableFuture<@Nullable CosmicOutput> result = new CompletableFuture<>();
		SpawnedProcess helper;
		try {
			helper = processes.spawn(
					List.of(environment.cursorHelper().toString()),
					new SpawnOptions(true, true, false));
		} catch (IOException error) {
			return CompletableFuture.<@Nullable CosmicOutput>completedFuture(null);
		}
		AtomicBoolean settled = new AtomicBoolean(false);
		AtomicReference<PickPoint> point = new AtomicReference<>();
		ScheduledFuture<?> timeout = timer.schedule(
				() -> finish(result, settled, helper, null), timeoutMs);
		InputStream helperStdout = helper.stdout();
		Thread.ofPlatform().daemon().name("gravaai-output-pick").start(() -> {
			PickPoint picked = readPick(helperStdout, settled, point);
			timeout.cancel(false);
			finish(result, settled, helper, picked);
		});
		return result;
	}

	/** Reads helper lines until a press lands on a known point, or the helper stops. */
	private static @Nullable PickPoint readPick(
			@Nullable InputStream helperStdout, AtomicBoolean settled, AtomicReference<PickPoint> point) {
		if (helperStdout == null) {
			return null;
		}
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(helperStdout, StandardCharsets.UTF_8))) {
			String line;
			while (!settled.get() && (line = reader.readLine()) != null) {
				Matcher position = POSITION_LINE.matcher(line);
				if (position.matches()) {
					point.set(new PickPoint(
							Integer.parseInt(position.group(1)), Integer.parseInt(position.group(2))));
					continue;
				}
				PickPoint current = point.get();
				if (line.startsWith("INTERACTION:mousedown") && current != null) {
					return current;
				}
			}
		} catch (IOException error) {
			// Helper exited.
		}
		return null;
	}

	private void finish(
			CompletableFuture<@Nullable CosmicOutput> result,
			AtomicBoolean settled,
			SpawnedProcess helper,
			@Nullable PickPoint picked) {
		if (!settled.compareAndSet(false, true)) {
			return;
		}
		CosmicProcessIo.writeCommand(helper, "");
		helper.destroy();
		if (picked == null) {
			result.complete(null);
			return;
		}
		Threads.startVirtual("gravaai-output-pick-resolve", () -> {
			try {
				result.complete(CosmicSources.outputAt(listOutputs(), picked.x(), picked.y()));
			} catch (IOException | RuntimeException error) {
				result.complete(null);
			}
		});
	}

	/** One click position in helper coordinates. */
	private record PickPoint(int x, int y) {
	}
}
