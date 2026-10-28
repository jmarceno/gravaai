package dev.gravaai.services.meeting;

/** Replaces Electron's AbortSignal for a running meeting job. */
public interface CancelSignal {

	CancelSignal NONE = () -> false;

	boolean isCancelled();

	default void throwIfCancelled() {
		if (isCancelled()) {
			throw new CancelledException();
		}
	}

	/** Thrown when a job is cancelled; the queue reports it as "cancelled". */
	final class CancelledException extends RuntimeException {
		public CancelledException() {
			super("Cancelled");
		}
	}

	/** A signal that can be cancelled by the job queue. */
	final class Flag implements CancelSignal {

		private volatile boolean cancelled;

		public void cancel() {
			cancelled = true;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}
	}
}
