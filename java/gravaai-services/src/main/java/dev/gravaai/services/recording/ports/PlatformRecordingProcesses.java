package dev.gravaai.services.recording.ports;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import dev.gravaai.platform.process.ProcessRunner;
import dev.gravaai.platform.process.RunningProcess;

/**
 * {@link RecordingProcesses} backed by the platform {@code ProcessRunner} (plan §5.5). The app wires
 * it into {@code AppContext}.
 *
 * <p>The runner always pipes a child's stdin; for a child that should see {@code /dev/null} instead
 * (ffmpeg mux runs, parec) the pipe is closed immediately so the child gets EOF, the same end
 * result as Electron's {@code stdio: ["ignore", ...]}.
 */
public final class PlatformRecordingProcesses implements RecordingProcesses {

	@Override
	public SpawnedProcess spawn(List<String> command, SpawnOptions options) throws IOException {
		ProcessRunner runner = ProcessRunner.of(command);
		if (options.stdout()) {
			runner = runner.rawStdout();
		}
		if (options.stderr()) {
			runner = runner.rawStderr();
		}
		RunningProcess running = runner.start();
		if (!options.stdin()) {
			try {
				running.stdin().close();
			} catch (IOException error) {
				// The child is already gone; nothing to close.
			}
		}
		return new SpawnedProcess() {
			@Override
			public OutputStream stdin() {
				return running.stdin();
			}

			@Override
			public InputStream stdout() {
				return options.stdout() ? running.stdout() : null;
			}

			@Override
			public InputStream stderr() {
				return options.stderr() ? running.stderr() : null;
			}

			@Override
			public long pid() {
				return running.pid();
			}

			@Override
			public boolean isAlive() {
				return running.isAlive();
			}

			@Override
			public int waitFor() throws InterruptedException {
				return running.waitForExit().exitCode();
			}

			@Override
			public void destroy() {
				running.cancel();
			}

			@Override
			public void destroyForcibly() {
				running.cancel();
			}
		};
	}
}
