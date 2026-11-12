package dev.gravaai.app.cli;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How a headless take ends: an explicit state machine shared by the recording thread and the
 * JVM shutdown hook that {@code Ctrl-C} and {@code kill} run.
 *
 * <pre>
 *   RECORDING ──duration elapsed──▶ STOPPING_ON_DURATION ──close()──▶ FINISHED
 *   RECORDING ──SIGINT / SIGTERM──▶ STOPPING_ON_SIGNAL   ──close()──▶ FINISHED
 * </pre>
 *
 * <p>The first transition out of {@link Phase#RECORDING} wins, so a signal that races the end of
 * the duration is reported once, whichever came first. The hook does not return until the
 * recording thread reports {@link Phase#FINISHED} (bounded by {@link #FINALISE_TIMEOUT}): the JVM
 * halts as soon as every shutdown hook has returned, so a hook that only raised the flag would cut
 * the finalisation short and leave a crash-recovery session instead of the file the script asked
 * for.
 */
final class TakeStop implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.cli.record");

	/** How long a signal waits for the take to be finalised before the process exits anyway. */
	static final Duration FINALISE_TIMEOUT = Duration.ofSeconds(60);

	/** Where the take is in its life. */
	enum Phase {
		RECORDING,
		STOPPING_ON_DURATION,
		STOPPING_ON_SIGNAL,
		FINISHED
	}

	/** Why recording stopped. */
	enum Cause {
		DURATION,
		SIGNAL
	}

	private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RECORDING);
	private final CountDownLatch signalled = new CountDownLatch(1);
	private final CountDownLatch finished = new CountDownLatch(1);
	private final Duration finaliseTimeout;
	private final Optional<Thread> hook;

	private TakeStop(Duration finaliseTimeout, boolean installHook) {
		this.finaliseTimeout = finaliseTimeout;
		this.hook = installHook
				? Optional.of(new Thread(this::onSignal, "gravaai-cli-record-stop"))
				: Optional.empty();
		hook.ifPresent(Runtime.getRuntime()::addShutdownHook);
	}

	/** Arms the stop for one take: from now on a signal finalises the recording first. */
	static TakeStop arm() {
		return new TakeStop(FINALISE_TIMEOUT, true);
	}

	/** A stop with no shutdown hook, driven by {@link #onSignal()} directly (tests). */
	static TakeStop unhooked(Duration finaliseTimeout) {
		return new TakeStop(finaliseTimeout, false);
	}

	/**
	 * The signal path (the shutdown hook's body): asks the take to stop, then holds the JVM open
	 * until it is finalised or the timeout passes.
	 */
	void onSignal() {
		if (phase.compareAndSet(Phase.RECORDING, Phase.STOPPING_ON_SIGNAL)) {
			signalled.countDown();
		}
		try {
			if (!finished.await(finaliseTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
				LOG.log(System.Logger.Level.WARNING, "the recording did not finalise within "
						+ finaliseTimeout.toSeconds() + " s of the stop signal; exiting anyway");
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Blocks while the take records: for {@code duration} seconds, or until a signal when there is
	 * no duration (the documented "record until Ctrl-C").
	 *
	 * @return why the take stopped
	 */
	Cause awaitStop(Optional<Long> durationSeconds) {
		try {
			if (durationSeconds.isPresent()) {
				signalled.await(durationSeconds.get(), TimeUnit.SECONDS);
			} else {
				signalled.await();
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			// An interrupted wait is a stop request too: end the take as a signal would.
			phase.compareAndSet(Phase.RECORDING, Phase.STOPPING_ON_SIGNAL);
		}
		return phase.compareAndSet(Phase.RECORDING, Phase.STOPPING_ON_DURATION)
				? Cause.DURATION
				: Cause.SIGNAL;
	}

	/** The current phase. */
	Phase phase() {
		return phase.get();
	}

	/**
	 * The take is finalised (or failed): releases a waiting signal and removes the hook. Called
	 * last, once the result has been reported, so a signal cannot halt the JVM before then.
	 */
	@Override
	public void close() {
		phase.set(Phase.FINISHED);
		finished.countDown();
		hook.ifPresent(thread -> {
			try {
				Runtime.getRuntime().removeShutdownHook(thread);
			} catch (IllegalStateException shuttingDown) {
				// The JVM is already exiting: the hook is running and has just been released.
			}
		});
	}
}
