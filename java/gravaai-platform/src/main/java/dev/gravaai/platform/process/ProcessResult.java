package dev.gravaai.platform.process;

/**
 * Result of a finished child process.
 *
 * @param exitCode       the process exit value
 * @param timedOut       {@code true} when the configured timeout expired and the tree was killed
 * @param cancelled      {@code true} when {@link RunningProcess#cancel()} killed the tree
 * @param stdout         captured stdout (empty for a {@link ProcessRunner#rawStdout()} run)
 * @param stderr         captured stderr (empty for a {@link ProcessRunner#rawStderr()} run)
 * @param durationMillis wall-clock time from start to exit
 */
public record ProcessResult(int exitCode, boolean timedOut, boolean cancelled, String stdout, String stderr,
		long durationMillis) {

	/** {@code true} when the process exited with status 0 and was neither killed nor timed out. */
	public boolean succeeded() {
		return !timedOut && !cancelled && exitCode == 0;
	}
}
