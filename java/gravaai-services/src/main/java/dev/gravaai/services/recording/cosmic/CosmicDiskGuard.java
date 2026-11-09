package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.services.recording.RecordingEvents;

/**
 * Disk-space checks for a recording, ported from the watchdog in
 * {@code electron/ipc/recording/cosmic.ts}.
 *
 * <p>A recording must not start below 2 GB free, and one that runs out of room is stopped (and
 * saved) through {@link RecordingEvents}, instead of failing mid-write. The class owns the
 * scheduler the checks and the click-picker timeout run on.
 */
final class CosmicDiskGuard implements AutoCloseable {

	static final long MIN_FREE_BYTES_TO_START = 2L * 1024 * 1024 * 1024;
	static final long MIN_FREE_BYTES_WHILE_RECORDING = 512L * 1024 * 1024;
	private static final long DISK_CHECK_INTERVAL_MS = 15_000;

	private final ScheduledExecutorService diskChecker = Threads.daemonScheduler("gravaai-disk-check");

	/** Free bytes available to this user in dir, or null when unknown. */
	static @Nullable Long freeBytes(Path dir) {
		try {
			FileStore store = Files.getFileStore(dir);
			return store.getUsableSpace();
		} catch (IOException error) {
			return null;
		}
	}

	/**
	 * The message that refuses a start, or null when there is enough room.
	 *
	 * @param dir the folder the recording would be written to
	 */
	static @Nullable String checkFreeSpaceToStart(Path dir) {
		Long free = freeBytes(dir);
		return free != null && free < MIN_FREE_BYTES_TO_START
				? "Not enough free disk space to record (less than 2 GB free in " + dir + ")."
				: null;
	}

	/**
	 * Watches {@code dir} and reports the free byte count once it drops below the running threshold.
	 * The callback cancels the watch, so a full disk is reported once, not every tick.
	 *
	 * @return the running task, to cancel on stop
	 */
	@SuppressWarnings("FutureReturnValueIgnored") // The returned task is the caller's to cancel.
	ScheduledFuture<?> watch(Path dir, LongConsumer onLowDisk) {
		return diskChecker.scheduleWithFixedDelay(() -> {
			Long free = freeBytes(dir);
			if (free == null || free >= MIN_FREE_BYTES_WHILE_RECORDING) {
				return;
			}
			onLowDisk.accept(free);
		}, DISK_CHECK_INTERVAL_MS, DISK_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	/** Runs a delayed task on the watchdog's scheduler. */
	ScheduledFuture<?> schedule(Runnable task, long delayMs) {
		return diskChecker.schedule(task, delayMs, TimeUnit.MILLISECONDS);
	}

	@Override
	public void close() {
		diskChecker.shutdownNow();
	}
}
