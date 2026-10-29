package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Meeting jobs run one at a time (whisper and the local model must never share the machine) and
 * keep going when windows close. Each job's state is mirrored into the recording's
 * .meeting.json so a crash shows up as "interrupted" with a retry. Ported from
 * electron/ipc/meeting/jobs.ts.
 */
public final class MeetingJobs {

	/** Finished jobs kept around for the UI. */
	public static final int MAX_FINISHED_JOBS = 20;

	private static final System.Logger LOG = System.getLogger("gravaai.meeting");

	/** Runs one job; the queue supplies settings and a cancellation signal. */
	public interface Pipeline {

		/**
		 * @param onStatus the stage callback, or null when the caller does not track stages
		 */
		void run(Path mediaPath, MeetingJobMode mode, MeetingSettings settings,
				CancelSignal signal, @Nullable StageListener onStatus);
	}

	/** Maps a pipeline status callback onto the job's stage field. */
	public interface StageListener {

		/**
		 * @param progress the percentage, or null when the stage cannot estimate progress
		 */
		void onStatus(MeetingJob.Stage stage, String message, @Nullable Integer progress);
	}

	private static final class JobState {
		private final String id;
		private final Path mediaPath;
		private final MeetingJobMode mode;
		private MeetingJob.Status status;
		private MeetingJob.@Nullable Stage stage;
		private String message;
		private @Nullable Integer progress;
		private @Nullable String error;
		private final String createdAt;
		private @Nullable String finishedAt;

		private JobState(String id, Path mediaPath, MeetingJobMode mode, String createdAt) {
			this.id = id;
			this.mediaPath = mediaPath;
			this.mode = mode;
			this.createdAt = createdAt;
			this.status = MeetingJob.Status.QUEUED;
			this.message = "Waiting…";
		}

		private boolean isActive() {
			return status == MeetingJob.Status.QUEUED || status == MeetingJob.Status.RUNNING;
		}

		private MeetingJob snapshot() {
			return new MeetingJob(id, mediaPath, mode, status, stage, message, progress, error,
					createdAt, finishedAt);
		}
	}

	private final Object lock = new Object();
	private final Object drainLock = new Object();
	private final List<JobState> jobs = new ArrayList<>();
	private final Map<String, CancelSignal.Flag> controllers = new HashMap<>();
	private final List<Consumer<MeetingJob>> listeners = new CopyOnWriteArrayList<>();
	private final Pipeline pipeline;
	private final Supplier<MeetingSettings> settings;
	private final MeetingMetaStore metaStore;
	private final Clock clock;
	private final Supplier<String> ids;
	private final Executor executor;
	private final Consumer<Path> onNotesReady;
	private boolean draining;

	/** @param onNotesReady the notes-ready hook, or null when automations are off */
	public MeetingJobs(Pipeline pipeline, Supplier<MeetingSettings> settings,
			MeetingMetaStore metaStore, Clock clock, Supplier<String> ids, Executor executor,
			@Nullable Consumer<Path> onNotesReady) {
		this.pipeline = pipeline;
		this.settings = settings;
		this.metaStore = metaStore;
		this.clock = clock;
		this.ids = ids;
		this.executor = executor;
		this.onNotesReady = onNotesReady == null ? path -> {
		} : onNotesReady;
	}

	public List<MeetingJob> list() {
		synchronized (lock) {
			List<MeetingJob> snapshots = new ArrayList<>(jobs.size());
			for (JobState job : jobs) {
				snapshots.add(job.snapshot());
			}
			return List.copyOf(snapshots);
		}
	}

	public Optional<MeetingJob> findActive(Path mediaPath) {
		synchronized (lock) {
			return jobs.stream()
					.filter(job -> job.mediaPath.equals(mediaPath) && job.isActive())
					.map(JobState::snapshot)
					.findFirst();
		}
	}

	public MeetingJob enqueue(Path mediaPath, MeetingJobMode mode) {
		MeetingJob queued;
		synchronized (lock) {
			Optional<JobState> existing = jobs.stream()
					.filter(job -> job.mediaPath.equals(mediaPath) && job.isActive())
					.findFirst();
			if (existing.isPresent()) {
				return existing.get().snapshot();
			}
			JobState job = new JobState(ids.get(), mediaPath, mode, Times.isoNow(clock));
			jobs.add(job);
			List<JobState> finished = new ArrayList<>();
			for (JobState entry : jobs) {
				if (!entry.isActive()) {
					finished.add(entry);
				}
			}
			int excess = finished.size() - MAX_FINISHED_JOBS;
			for (int index = 0; index < excess; index++) {
				jobs.remove(finished.get(index));
			}
			queued = job.snapshot();
			broadcast(queued);
		}
		executor.execute(this::drain);
		return queued;
	}

	public boolean cancel(String id) {
		synchronized (lock) {
			JobState job = jobs.stream()
					.filter(entry -> entry.id.equals(id))
					.findFirst()
					.orElse(null);
			if (job == null || !job.isActive()) {
				return false;
			}
			if (job.status == MeetingJob.Status.QUEUED) {
				job.status = MeetingJob.Status.CANCELLED;
				job.message = "Cancelled";
				job.finishedAt = Times.isoNow(clock);
				broadcast(job.snapshot());
				return true;
			}
			CancelSignal.Flag controller = controllers.get(id);
			if (controller != null) {
				controller.cancel();
			}
			return true;
		}
	}

	/** A job the last session left "running" (the app quit or crashed). */
	public boolean isInterrupted(Path mediaPath) {
		MeetingMeta meta = metaStore.read(mediaPath);
		boolean lastRunning = meta.lastJob() != null
				&& "running".equals(meta.lastJob().status());
		return lastRunning && findActive(mediaPath).isEmpty();
	}

	public Subscription onUpdate(Consumer<MeetingJob> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	private void drain() {
		synchronized (drainLock) {
			if (draining) {
				return;
			}
			draining = true;
		}
		try {
			while (true) {
				JobState next;
				synchronized (lock) {
					next = jobs.stream()
							.filter(job -> job.status == MeetingJob.Status.QUEUED)
							.findFirst()
							.orElse(null);
				}
				if (next == null) {
					return;
				}
				runJob(next);
			}
		} finally {
			synchronized (drainLock) {
				draining = false;
			}
		}
	}

	private void runJob(JobState job) {
		CancelSignal.Flag controller = new CancelSignal.Flag();
		synchronized (lock) {
			controllers.put(job.id, controller);
			job.status = MeetingJob.Status.RUNNING;
			job.message = "Starting…";
			broadcast(job.snapshot());
		}
		try {
			metaStore.update(job.mediaPath, lastJob(job.mode, "running", null, Times.isoNow(clock)));
			pipeline.run(job.mediaPath, job.mode, settings.get(), controller,
					(stage, message, progress) -> update(job, stage, message, progress));
			// Meta first, so anyone reacting to "done" reads the finished state.
			metaStore.update(job.mediaPath, lastJob(job.mode, "done", null, Times.isoNow(clock)));
			synchronized (lock) {
				job.status = MeetingJob.Status.DONE;
				job.message = "Done";
				job.progress = 100;
				job.finishedAt = Times.isoNow(clock);
				broadcast(job.snapshot());
			}
			// Transcript-only jobs never write notes; everything else does.
			if (job.mode != MeetingJobMode.TRANSCRIBE) {
				onNotesReady.accept(job.mediaPath);
			}
		} catch (RuntimeException error) {
			boolean cancelled = controller.isCancelled()
					|| error instanceof CancelSignal.CancelledException;
			String message = error.getMessage() == null ? error.toString() : error.getMessage();
			LOG.log(System.Logger.Level.ERROR,
					"Meeting job " + job.mode.wire() + " for " + job.mediaPath + " failed", error);
			try {
				metaStore.update(job.mediaPath, lastJob(job.mode,
						cancelled ? "cancelled" : "failed", cancelled ? null : message,
						Times.isoNow(clock)));
			} catch (RuntimeException ignored) {
				// The metadata write must not hide the job failure.
			}
			synchronized (lock) {
				job.status = cancelled ? MeetingJob.Status.CANCELLED : MeetingJob.Status.FAILED;
				job.message = cancelled ? "Cancelled" : message;
				job.error = cancelled ? null : message;
				job.finishedAt = Times.isoNow(clock);
				broadcast(job.snapshot());
			}
		} finally {
			synchronized (lock) {
				controllers.remove(job.id);
			}
		}
	}

	private static MeetingMetaPatch lastJob(MeetingJobMode mode, String status, @Nullable String error,
			String at) {
		return new MeetingMetaPatch().lastJob(new MeetingMeta.LastJob(mode.wire(), status, error, at));
	}

	private void update(JobState job, MeetingJob.Stage stage, String message,
			@Nullable Integer progress) {
		synchronized (lock) {
			job.stage = stage;
			job.message = message;
			job.progress = progress;
			broadcast(job.snapshot());
		}
	}

	private void broadcast(MeetingJob job) {
		for (Consumer<MeetingJob> listener : listeners) {
			try {
				listener.accept(job);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "Meeting job listener failed", error);
			}
		}
	}
}
