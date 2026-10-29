package dev.gravaai.app;

import org.jspecify.annotations.Nullable;

import dev.gravaai.services.shortcuts.ControlSocket;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Only one Gravaai runs per user-data directory. The first process holds an OS file lock for its
 * whole life (released by the kernel even when it crashes); a later process finds the lock taken,
 * hands its request to the running app over the control socket and exits.
 */
final class SingleInstance {

	/** The running app starts its control socket after the first window is up; wait that long. */
	private static final long FORWARD_WAIT_MS = 15_000;
	private static final long FORWARD_RETRY_MS = 250;

	/**
	 * Held until the process exits (a static field keeps the lock from being collected);
	 * {@code null} when the lock was not acquired.
	 */
	private static @Nullable FileLock heldLock;

	private SingleInstance() {
	}

	/**
	 * Tries to become the only instance.
	 *
	 * @return true when this process holds the lock (or the lock could not be checked)
	 */
	static boolean acquire(Path lockFile) {
		try {
			Files.createDirectories(lockFile.getParent());
			FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			FileLock lock;
			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException error) {
				lock = null;
			}
			if (lock == null) {
				channel.close();
				return false;
			}
			heldLock = lock;
			return true;
		} catch (IOException error) {
			// An unusable lock file must not stop the app from starting.
			Startup.log("single-instance lock unavailable: " + error);
			return true;
		}
	}

	/**
	 * Sends {@code line} to the running instance, retrying while it is still starting up.
	 *
	 * @return true when the running app accepted it
	 */
	static boolean forward(String line) {
		Path socketPath = ControlSocket.getControlSocketPath();
		long deadline = System.currentTimeMillis() + FORWARD_WAIT_MS;
		while (true) {
			ControlSocket.SendResult result = ControlSocket.send(line, socketPath);
			if (result == ControlSocket.SendResult.OK) {
				return true;
			}
			if (System.currentTimeMillis() >= deadline) {
				return false;
			}
			try {
				Thread.sleep(FORWARD_RETRY_MS);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}
}
