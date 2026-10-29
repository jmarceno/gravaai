package dev.gravaai.services.recording.ports;

import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * One child process the recording pipeline started, with separate stdout/stderr streams (plan §5.5).
 *
 * <p>Implemented by the platform {@code ProcessRunner} adapter the app wires in {@code AppContext}.
 */
public interface SpawnedProcess {

	/** The child's stdin, or null when it was not piped. */
	@Nullable OutputStream stdin();

	/** The child's stdout, or null when it was not piped. */
	@Nullable InputStream stdout();

	/** The child's stderr, or null when it was not piped. */
	@Nullable InputStream stderr();

	long pid();

	boolean isAlive();

	/** Waits for the process to exit and returns its exit code (-1 when killed by a signal). */
	int waitFor() throws InterruptedException;

	void destroy();

	void destroyForcibly();
}
