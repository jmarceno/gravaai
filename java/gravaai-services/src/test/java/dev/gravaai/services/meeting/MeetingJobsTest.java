package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behaviour of the meeting job queue (electron/ipc/meeting/jobs.ts). The Electron side has no
 * unit test file for jobs; these cases pin the queue contract the IPC handlers and the UI report
 * rely on: one job at a time, duplicates collapse, cancellation, failure messages, the
 * interrupted-after-crash flag and the finished-job cap.
 */
class MeetingJobsTest {

	private Path recordings;
	private MeetingMetaStore metaStore;
	private MeetingJobs jobs;
	private FakePipeline pipeline;
	private final List<Path> notesReady = new ArrayList<>();

	@BeforeEach
	void setUp(@TempDir Path directory) throws Exception {
		recordings = directory.resolve("recordings");
		Files.createDirectories(recordings);
		metaStore = new MeetingMetaStore();
		pipeline = new FakePipeline();
		jobs = new MeetingJobs(pipeline, () -> MeetingSettings.DEFAULT, metaStore,
				MeetingTestSupport.CLOCK, counterIds(), MeetingTestSupport.DIRECT_EXECUTOR,
				notesReady::add);
	}

	private Path media(String name) throws Exception {
		Path path = recordings.resolve(name);
		Files.writeString(path, "media");
		return path;
	}

	private static java.util.function.Supplier<String> counterIds() {
		AtomicInteger counter = new AtomicInteger();
		return () -> "job-" + counter.incrementAndGet();
	}

	@Test
	@DisplayName("runs a queued job and publishes its stage updates")
	void runsQueuedJobAndPublishesStageUpdates() throws Exception {
		Path media = media("one.mp4");
		List<MeetingJob> updates = new ArrayList<>();
		jobs.onUpdate(updates::add);
		MeetingJob queued = jobs.enqueue(media, MeetingJobMode.FULL);
		MeetingJob finished = jobs.list().get(0);
		assertEquals(MeetingJob.Status.DONE, finished.status());
		assertEquals(MeetingJob.Status.QUEUED, queued.status());
		assertEquals(Integer.valueOf(100), finished.progress());
		assertEquals("job-1", finished.id());
		assertTrue(finished.finishedAt() != null);
		assertTrue(updates.stream().anyMatch(job -> job.status() == MeetingJob.Status.RUNNING));
		assertTrue(updates.stream().anyMatch(job -> job.stage() == MeetingJob.Stage.TRANSCRIBE
				&& Integer.valueOf(50).equals(job.progress())));
		assertEquals(List.of(media), notesReady);
	}

	@Test
	@DisplayName("transcribe-only jobs never announce notes")
	void transcribeOnlyJobsDoNotAnnounceNotes() throws Exception {
		jobs.enqueue(media("two.mp4"), MeetingJobMode.TRANSCRIBE);
		assertTrue(notesReady.isEmpty());
	}

	@Test
	@DisplayName("keeps one active job per recording")
	void keepsOneActiveJobPerRecording() throws Exception {
		MeetingTestSupport.ManualExecutor manual = new MeetingTestSupport.ManualExecutor();
		MeetingJobs queuedOnly = new MeetingJobs(pipeline, () -> MeetingSettings.DEFAULT, metaStore,
				MeetingTestSupport.CLOCK, counterIds(), manual, path -> {
				});
		Path media = media("three.mp4");
		MeetingJob first = queuedOnly.enqueue(media, MeetingJobMode.FULL);
		MeetingJob second = queuedOnly.enqueue(media, MeetingJobMode.SUMMARIZE);
		assertEquals(first.id(), second.id());
		assertEquals(1, queuedOnly.list().size());
		assertEquals(MeetingJob.Status.QUEUED, queuedOnly.list().get(0).status());
	}

	@Test
	@DisplayName("cancelling a queued job keeps it from running")
	void cancellingQueuedJobKeepsItFromRunning() throws Exception {
		MeetingTestSupport.ManualExecutor manual = new MeetingTestSupport.ManualExecutor();
		MeetingJobs queuedOnly = new MeetingJobs(pipeline, () -> MeetingSettings.DEFAULT, metaStore,
				MeetingTestSupport.CLOCK, counterIds(), manual, path -> {
				});
		MeetingJob job = queuedOnly.enqueue(media("four.mp4"), MeetingJobMode.FULL);
		assertTrue(queuedOnly.cancel(job.id()));
		assertEquals(MeetingJob.Status.CANCELLED, queuedOnly.list().get(0).status());
		manual.runNext();
		assertTrue(pipeline.calls.isEmpty());
	}

	@Test
	@DisplayName("cancelling a running job reports a cancellation")
	void cancellingRunningJobReportsCancellation() throws Exception {
		MeetingTestSupport.ManualExecutor manual = new MeetingTestSupport.ManualExecutor();
		CountDownLatch started = new CountDownLatch(1);
		FakePipeline blocking = new FakePipeline() {
			@Override
			public void run(Path mediaPath, MeetingJobMode mode, MeetingSettings settings,
					CancelSignal signal, MeetingJobs.StageListener onStatus) {
				started.countDown();
				while (!signal.isCancelled()) {
					Thread.onSpinWait();
				}
				throw new CancelSignal.CancelledException();
			}
		};
		MeetingJobs queue = new MeetingJobs(blocking, () -> MeetingSettings.DEFAULT, metaStore,
				MeetingTestSupport.CLOCK, counterIds(), manual, path -> {
				});
		Path media = media("five.mp4");
		MeetingJob job = queue.enqueue(media, MeetingJobMode.FULL);
		Thread worker = Thread.ofPlatform().start(manual::runNext);
		assertTrue(started.await(5, TimeUnit.SECONDS));
		assertTrue(queue.cancel(job.id()));
		worker.join(5_000);
		assertEquals(MeetingJob.Status.CANCELLED, queue.list().get(0).status());
		assertFalse(worker.isAlive());
	}

	@Test
	@DisplayName("a failed job keeps its error message and metadata")
	void failedJobKeepsErrorAndMetadata() throws Exception {
		pipeline.failure = new IllegalStateException("whisper exploded");
		Path media = media("six.mp4");
		jobs.enqueue(media, MeetingJobMode.FULL);
		MeetingJob job = jobs.list().get(0);
		assertEquals(MeetingJob.Status.FAILED, job.status());
		assertEquals("whisper exploded", job.error());
		assertEquals("whisper exploded", job.message());
		MeetingMeta meta = metaStore.read(media);
		assertEquals("failed", meta.lastJob().status());
		assertEquals("whisper exploded", meta.lastJob().error());
		assertTrue(notesReady.isEmpty());
	}

	@Test
	@DisplayName("detects a job the last session left running")
	void detectsInterruptedJobFromMetadata() throws Exception {
		Path media = media("seven.mp4");
		metaStore.update(media, Map.of("lastJob", Map.of(
				"mode", "full", "status", "running", "at", Times.isoNow(MeetingTestSupport.CLOCK))));
		assertTrue(jobs.isInterrupted(media));
		jobs.enqueue(media, MeetingJobMode.FULL);
		assertFalse(jobs.isInterrupted(media));
	}

	@Test
	@DisplayName("trims finished jobs when a new one is enqueued")
	void trimsFinishedJobsWhenEnqueuing() throws Exception {
		// Electron trims at enqueue time, before the new job runs, so the list momentarily holds
		// MAX_FINISHED_JOBS finished jobs plus the one that just completed (jobs.ts behaviour).
		for (int index = 0; index < 25; index++) {
			jobs.enqueue(media("many-" + index + ".mp4"), MeetingJobMode.FULL);
		}
		assertEquals(MeetingJobs.MAX_FINISHED_JOBS + 1, jobs.list().size());
	}

	private static class FakePipeline implements MeetingJobs.Pipeline {

		final List<Path> calls = new ArrayList<>();
		RuntimeException failure;

		@Override
		public void run(Path mediaPath, MeetingJobMode mode, MeetingSettings settings,
				CancelSignal signal, MeetingJobs.StageListener onStatus) {
			calls.add(mediaPath);
			if (failure != null) {
				throw failure;
			}
			onStatus.onStatus(MeetingJob.Stage.TRANSCRIBE, "Transcribing…", 50);
		}
	}
}
