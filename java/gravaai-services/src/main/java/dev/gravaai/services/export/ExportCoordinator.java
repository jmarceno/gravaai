package dev.gravaai.services.export;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.media.export.CaptionSidecars;
import dev.gravaai.media.export.ExportAudioPlan;
import dev.gravaai.media.export.ExportCancellation;
import dev.gravaai.media.export.ExportCancelledException;
import dev.gravaai.media.export.ExportException;
import dev.gravaai.media.export.ExportFrameProvider;
import dev.gravaai.media.export.ExportProgress;
import dev.gravaai.media.export.ExportProgressListener;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.media.export.ExportRunner;
import dev.gravaai.services.automations.AutomationTriggers;

/**
 * Runs one export at a time on its own thread and fires the automations
 * "export finished" trigger after a successful publish (plan §5.10).
 *
 * <p>The AppContext wires this: the UI calls {@link #start}, receives progress through the
 * listener (marshalled to the FX thread by the caller) and can {@link #cancel()} the running job.
 */
public final class ExportCoordinator implements AutoCloseable {

	/** All the settings of one export request. */
	public record Request(ExportFormat format, int width, int height, int frameRate, int bitrate,
			ExportEncodingMode encodingMode, double durationSeconds, boolean gifLoop,
			Path destination, boolean reveal, boolean overwrite,
			CaptionSidecars.Payload captionSidecars, ExportAudioPlan audioPlan) {

		public Request {
			format = format == null ? ExportFormat.MP4 : format;
			audioPlan = audioPlan == null ? ExportAudioPlan.NONE : audioPlan;
			if (destination == null) {
				throw new IllegalArgumentException("export destination is required");
			}
		}

		ExportRunner.Spec toSpec() {
			return new ExportRunner.Spec(format, width, height, frameRate, bitrate, encodingMode,
					durationSeconds, gifLoop, destination, reveal, overwrite, captionSidecars,
					audioPlan);
		}
	}

	/** Export lifecycle callbacks. Implementations must be thread-safe (progress is off the FX thread). */
	public interface Listener {
		default void onProgress(ExportProgress progress) {}

		default void onCompleted(ExportRunner.Outcome outcome) {}

		default void onCancelled() {}

		default void onFailed(Throwable error) {}
	}

	private static final System.Logger LOG = System.getLogger("gravaai.export.coordinator");

	private final ExportPublisher publisher;
	private final AutomationTriggers triggers;
	private final ExecutorService executor;
	private final AtomicBoolean busy = new AtomicBoolean();
	private final AtomicReference<Cancellation> active = new AtomicReference<>();

	/** @param publisher save/reveal policy; @param triggers automations hooks (may be null in tests) */
	public ExportCoordinator(ExportPublisher publisher, AutomationTriggers triggers) {
		this.publisher = Objects.requireNonNull(publisher, "publisher");
		this.triggers = triggers;
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "export-job");
			thread.setDaemon(true);
			return thread;
		});
	}

	/** {@code true} while an export is running. */
	public boolean isBusy() {
		return busy.get();
	}

	/**
	 * Starts an export.
	 *
	 * @return {@code false} when another export is already running
	 */
	public boolean start(Request request, ExportFrameProvider frames, Listener listener) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(frames, "frames");
		Listener resolvedListener = listener == null ? new Listener() {} : listener;
		if (!busy.compareAndSet(false, true)) {
			return false;
		}
		Cancellation cancellation = new Cancellation();
		active.set(cancellation);
		try {
			executor.execute(() -> run(request, frames, resolvedListener, cancellation));
		} catch (RuntimeException error) {
			busy.set(false);
			active.set(null);
			throw error;
		}
		return true;
	}

	/** Requests cancellation of the running export (no-op when idle). */
	public void cancel() {
		Cancellation cancellation = active.get();
		if (cancellation != null) {
			cancellation.cancel();
		}
	}

	private void run(Request request, ExportFrameProvider frames, Listener listener,
			Cancellation cancellation) {
		try {
			ExportRunner.Outcome outcome = ExportRunner.run(request.toSpec(), frames,
					progressSafely(listener), cancellation, publisher);
			if (triggers != null) {
				triggers.fireExportFinished(outcome.path().toString());
			}
			notifySafely(() -> listener.onCompleted(outcome));
		} catch (ExportCancelledException cancelled) {
			notifySafely(listener::onCancelled);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "export failed: " + error);
			notifySafely(() -> listener.onFailed(error));
		} finally {
			try {
				frames.close();
			} catch (Exception closeError) {
				LOG.log(System.Logger.Level.DEBUG, "closing frame provider: " + closeError);
			}
			active.set(null);
			busy.set(false);
		}
	}

	private static ExportProgressListener progressSafely(Listener listener) {
		return progress -> notifySafely(() -> listener.onProgress(progress));
	}

	private static void notifySafely(Runnable callback) {
		try {
			callback.run();
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "export listener failed: " + error);
		}
	}

	@Override
	public void close() {
		cancel();
		executor.shutdownNow();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		}
	}

	/** The cancellation token of the running export. */
	private static final class Cancellation implements ExportCancellation {
		private volatile boolean cancelled;

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		void cancel() {
			cancelled = true;
		}
	}
}
