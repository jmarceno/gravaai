package dev.gravaai.services.recording.ports;

import java.io.IOException;
import java.util.List;

/**
 * Starts child processes for the recording pipeline. The app wires a {@code ProcessRunner} adapter
 * here; tests provide a deterministic fake.
 */
public interface RecordingProcesses {

	/** Which standard streams of the child are piped. */
	record SpawnOptions(boolean stdin, boolean stdout, boolean stderr) {
	}

	SpawnedProcess spawn(List<String> command, SpawnOptions options) throws IOException;
}
