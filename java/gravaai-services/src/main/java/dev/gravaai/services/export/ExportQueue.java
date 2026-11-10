package dev.gravaai.services.export;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.media.export.ExportProgress;

/**
 * The batch export queue (card "Batch export queue"): holds the exports requested from the
 * dashboard and the editor, runs them one after another without user action and keeps their state
 * for the queue panel (progress, cancel, reorder, Copy file / Show in folder per finished item).
 *
 * <p>The queue owns ordering and state only. The render half is a {@link Renderer} supplied by the
 * app: it loads the project, builds the frame pipeline and drives the
 * {@link ExportCoordinator}. A renderer that finds the export pipeline busy returns
 * {@link Optional#empty()}; the job then stays {@link State#QUEUED} and the app calls
 * {@link #pump()} again when the pipeline goes idle (the AppContext wires that through
 * {@link ExportCoordinator#addIdleListener}).
 *
 * <p>Threading: every method is safe to call from any thread. Change listeners run on whichever
 * thread made the change (the export thread for progress/completion, the FX thread for user
 * actions), so the UI marshals them to the FX thread. A cancelled job's late callbacks are
 * dropped by generation, never replayed.
 */
public final class ExportQueue implements AutoCloseable {

	/** Where one queued export stands. */
	public enum State {
		/** Waiting for the pipeline. */
		QUEUED,
		/** Being rendered right now. */
		RUNNING,
		/** Rendered and published to its destination. */
		COMPLETED,
		/** The render failed; see the entry error. */
		FAILED,
		/** Cancelled by the user (or by shutdown) before or during the render. */
		CANCELLED
	}

	/**
	 * One queued export: the display fields, the destination, and everything the renderer needs to
	 * reproduce it (the source project and the export settings). The queue treats the render input
	 * as opaque.
	 */
	public record Job(long id, String label, String detail, Path source, Path destination,
			ExportPreferences preferences) {

		public Job {
			if (label == null || label.isBlank()) {
				throw new IllegalArgumentException("an export job needs a label");
			}
			detail = detail == null ? "" : detail;
			Objects.requireNonNull(source, "export job source");
			Objects.requireNonNull(destination, "export job destination");
			Objects.requireNonNull(preferences, "export job preferences");
		}
	}

	/**
	 * A queue entry as the queue panel sees it.
	 *
	 * @param progress the running job's last progress, null while queued
	 * @param result the published file of a completed job, null otherwise
	 * @param error why a failed job failed, null otherwise
	 */
	public record Entry(long id, String label, String detail, Path destination, State state,
			@Nullable ExportProgress progress, @Nullable Path result, @Nullable String error) {

		/** {@code true} while the entry can still be cancelled or moved. */
		public boolean isPending() {
			return state == State.QUEUED || state == State.RUNNING;
		}

		/** {@code true} when the entry is over (completed, failed or cancelled). */
		public boolean isFinished() {
			return !isPending();
		}
	}

	/** Renders one job; implemented by the app (project loading + frame pipeline + coordinator). */
	public interface Renderer {

		/**
		 * Starts the job.
		 *
		 * @return the running handle, or empty when the export pipeline is busy (the job stays
		 *         queued and the queue retries on the next {@link #pump()})
		 * @throws RuntimeException when the job cannot start at all; the entry becomes failed
		 */
		Optional<Handle> start(Job job, Callbacks callbacks);

		/** Stops the running render (a no-op once it already finished). */
		interface Handle {
			void cancel();
		}

		/** Render callbacks; implementations may be called from any thread. */
		interface Callbacks {
			void onProgress(ExportProgress progress);

			void onCompleted(Path file);

			void onCancelled();

			void onFailed(String message);
		}
	}

	/** One entry plus its bookkeeping. Only touched by the queue. */
	private static final class Slot {
		private final Job job;
		private State state = State.QUEUED;
		private @Nullable ExportProgress progress;
		private @Nullable Path result;
		private @Nullable String error;
		/** Bumped on every start and cancel, so a stale render callback cannot touch this slot. */
		private long generation;

		Slot(Job job) {
			this.job = job;
		}

		boolean isQueued() {
			return state == State.QUEUED;
		}

		Entry toEntry() {
			return new Entry(job.id(), job.label(), job.detail(), job.destination(), state, progress,
					result, error);
		}
	}

	private final Renderer renderer;
	private final List<Slot> slots = new ArrayList<>();
	private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
	private long nextId = 1;
	private long generation;
	private @Nullable Slot running;
	private Renderer.@Nullable Handle runningHandle;
	private boolean pumping;
	/** A {@link #pump()} that arrived while another one was starting a job; replayed afterwards. */
	private boolean pumpRequested;
	private boolean closed;

	/** @param renderer the app-side render half; the queue never renders anything itself */
	public ExportQueue(Renderer renderer) {
		this.renderer = Objects.requireNonNull(renderer, "renderer");
	}

	/**
	 * Adds one export at the end of the queue and starts it when the pipeline is free.
	 *
	 * @return the new job's id
	 */
	public long enqueue(String label, String detail, Path source, Path destination,
			ExportPreferences preferences) {
		long id;
		synchronized (this) {
			if (closed) {
				throw new IllegalStateException("the export queue is shutting down");
			}
			id = nextId++;
			slots.add(new Slot(new Job(id, label, detail, source, destination, preferences)));
		}
		notifyChanged();
		pump();
		return id;
	}

	/** The entries in display order (running/queued keep their order, finished ones stay in place). */
	public List<Entry> entries() {
		synchronized (this) {
			List<Entry> snapshot = new ArrayList<>(slots.size());
			for (Slot slot : slots) {
				snapshot.add(slot.toEntry());
			}
			return List.copyOf(snapshot);
		}
	}

	/** {@code true} while at least one export is queued or running. */
	public boolean hasActive() {
		synchronized (this) {
			return !closed && (running != null || firstQueued() != null);
		}
	}

	/** How many exports are queued or running. */
	public int activeCount() {
		synchronized (this) {
			int count = 0;
			for (Slot slot : slots) {
				if (slot.state == State.QUEUED || slot.state == State.RUNNING) {
					count++;
				}
			}
			return count;
		}
	}

	/** {@code true} while an export is rendering. */
	public boolean isRunning() {
		synchronized (this) {
			return running != null;
		}
	}

	/**
	 * Cancels one entry: a queued entry is dropped immediately, a running one is asked to stop and
	 * the next entry starts.
	 *
	 * @return {@code false} when the entry does not exist or already finished
	 */
	public boolean cancel(long id) {
		Renderer.@Nullable Handle handle = null;
		boolean cancelled = false;
		synchronized (this) {
			Slot slot = find(id);
			if (slot != null) {
				switch (slot.state) {
					case QUEUED -> {
						slot.state = State.CANCELLED;
						slot.generation = ++generation;
						cancelled = true;
					}
					case RUNNING -> {
						slot.state = State.CANCELLED;
						slot.progress = null;
						slot.generation = ++generation;
						handle = runningHandle;
						runningHandle = null;
						running = null;
						cancelled = true;
					}
					default -> {
						// Finished entries are history; nothing to cancel.
					}
				}
			}
		}
		if (!cancelled) {
			return false;
		}
		notifyChanged();
		Renderer.Handle cancelledHandle = handle;
		if (cancelledHandle != null) {
			cancelQuietly(cancelledHandle);
		}
		// The cancelled render still holds the coordinator for a moment; the renderer answers
		// "busy" and the idle hook retries, so the next job starts as soon as the pipeline frees.
		pump();
		return true;
	}

	/**
	 * Moves one queued entry by {@code offset} places (negative moves it earlier).
	 *
	 * @return {@code false} when the entry is not queued, has no queued neighbour or does not exist
	 */
	public boolean move(long id, int offset) {
		if (offset == 0) {
			return false;
		}
		int step = offset < 0 ? -1 : 1;
		int remaining = Math.abs(offset);
		boolean moved = false;
		synchronized (this) {
			Slot slot = find(id);
			if (slot == null || !slot.isQueued()) {
				return false;
			}
			int index = slots.indexOf(slot);
			while (remaining-- > 0) {
				int neighbour = neighbourIndex(index, step);
				if (neighbour < 0) {
					break;
				}
				slots.set(index, slots.get(neighbour));
				slots.set(neighbour, slot);
				index = neighbour;
				moved = true;
			}
		}
		if (moved) {
			notifyChanged();
		}
		return moved;
	}

	/** Removes the finished (completed, failed and cancelled) entries. */
	public void clearFinished() {
		boolean changed;
		synchronized (this) {
			changed = slots.removeIf(slot -> !slot.isQueued() && slot != running);
		}
		if (changed) {
			notifyChanged();
		}
	}

	/**
	 * Starts the first queued export when the pipeline is free. Idempotent and safe to call from
	 * the coordinator's idle hook, an enqueue or a cancel.
	 */
	public void pump() {
		while (true) {
			Slot slot;
			long token;
			synchronized (this) {
				if (pumping) {
					// Another thread is mid-start. If it is about to hear "busy", this call (the
					// coordinator's idle hook, typically) is the retry it would otherwise miss.
					pumpRequested = true;
					return;
				}
				if (closed || running != null) {
					return;
				}
				pumpRequested = false;
				slot = firstQueued();
				if (slot == null) {
					return;
				}
				pumping = true;
				slot.state = State.RUNNING;
				slot.progress = null;
				slot.error = null;
				slot.result = null;
				token = ++generation;
				slot.generation = token;
				running = slot;
			}
			notifyChanged();
			Optional<Renderer.Handle> startedResult;
			try {
				startedResult = renderer.start(slot.job, callbacksFor(slot, token));
			} catch (RuntimeException error) {
				synchronized (this) {
					pumping = false;
					if (running == slot) {
						slot.state = State.FAILED;
						slot.error = messageOf(error);
						slot.progress = null;
						slot.generation = ++generation;
						running = null;
					}
				}
				notifyChanged();
				// A synchronous failure must not stall the queue: try the next entry.
				continue;
			}
			boolean started = startedResult.isPresent();
			boolean retry;
			boolean pumpAgain;
			synchronized (this) {
				pumping = false;
				pumpAgain = pumpRequested;
				pumpRequested = false;
				if (started) {
					if (running == slot) {
						runningHandle = startedResult.orElseThrow();
						return;
					}
					retry = false;
				} else if (running == slot) {
					// The pipeline is busy; keep the job at its place and wait for the idle hook.
					slot.state = State.QUEUED;
					slot.generation = ++generation;
					running = null;
					retry = true;
				} else {
					retry = false;
				}
			}
			if (started) {
				// The job was cancelled or finished while the renderer was starting: stop it and
				// keep the queue moving with the next entry.
				cancelQuietly(startedResult.orElseThrow());
				continue;
			}
			if (retry) {
				notifyChanged();
			}
			if (pumpAgain) {
				// A pump arrived while the renderer was answering (the pipeline went idle in
				// between): nobody else will retry, so do it now.
				continue;
			}
			return;
		}
	}

	/** Subscribes a change callback (state, order, progress). */
	public AutoCloseable addChangeListener(Runnable listener) {
		Objects.requireNonNull(listener, "listener");
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Cancels everything and refuses new jobs (app shutdown). */
	@Override
	public void close() {
		Renderer.@Nullable Handle handle;
		synchronized (this) {
			if (closed) {
				return;
			}
			closed = true;
			for (Slot slot : slots) {
				if (slot.isQueued()) {
					slot.state = State.CANCELLED;
					slot.generation = ++generation;
				}
			}
			if (running != null) {
				running.state = State.CANCELLED;
				running.generation = ++generation;
				running = null;
			}
			handle = runningHandle;
			runningHandle = null;
		}
		notifyChanged();
		if (handle != null) {
			cancelQuietly(handle);
		}
	}

	// ---- internals ----

	private Renderer.Callbacks callbacksFor(Slot slot, long token) {
		return new Renderer.Callbacks() {
			@Override
			public void onProgress(ExportProgress progress) {
				boolean changed;
				synchronized (ExportQueue.this) {
					changed = isCurrent(slot, token) && slot.state == State.RUNNING;
					if (changed) {
						slot.progress = progress;
					}
				}
				if (changed) {
					notifyChanged();
				}
			}

			@Override
			public void onCompleted(Path file) {
				boolean changed;
				synchronized (ExportQueue.this) {
					changed = isCurrent(slot, token) && running == slot;
					if (changed) {
						slot.state = State.COMPLETED;
						slot.result = file;
						slot.progress = null;
						slot.generation = ++generation;
						running = null;
						runningHandle = null;
					}
				}
				if (changed) {
					notifyChanged();
					pump();
				}
			}

			@Override
			public void onCancelled() {
				boolean changed;
				synchronized (ExportQueue.this) {
					changed = isCurrent(slot, token) && running == slot;
					if (changed) {
						slot.state = State.CANCELLED;
						slot.progress = null;
						slot.generation = ++generation;
						running = null;
						runningHandle = null;
					}
				}
				if (changed) {
					notifyChanged();
					pump();
				}
			}

			@Override
			public void onFailed(String message) {
				boolean changed;
				synchronized (ExportQueue.this) {
					changed = isCurrent(slot, token) && running == slot;
					if (changed) {
						slot.state = State.FAILED;
						slot.error = message == null || message.isBlank() ? "export failed" : message;
						slot.progress = null;
						slot.generation = ++generation;
						running = null;
						runningHandle = null;
					}
				}
				if (changed) {
					notifyChanged();
					pump();
				}
			}
		};
	}

	/** {@code true} when {@code slot} is still the entry the callback generation belongs to. */
	private boolean isCurrent(Slot slot, long token) {
		return !closed && slot.generation == token;
	}

	private @Nullable Slot firstQueued() {
		for (Slot slot : slots) {
			if (slot.isQueued()) {
				return slot;
			}
		}
		return null;
	}

	private @Nullable Slot find(long id) {
		for (Slot slot : slots) {
			if (slot.job.id() == id) {
				return slot;
			}
		}
		return null;
	}

	/** The index of the nearest queued neighbour of {@code index} in direction {@code step}. */
	private int neighbourIndex(int index, int step) {
		for (int candidate = index + step; candidate >= 0 && candidate < slots.size();
				candidate += step) {
			if (slots.get(candidate).isQueued()) {
				return candidate;
			}
		}
		return -1;
	}

	private void notifyChanged() {
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException ignored) {
				// A broken listener must never break the queue.
			}
		}
	}

	private static void cancelQuietly(Renderer.Handle handle) {
		try {
			handle.cancel();
		} catch (RuntimeException ignored) {
			// Best effort: the render is over either way.
		}
	}

	private static String messageOf(RuntimeException error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.toString() : message;
	}
}
