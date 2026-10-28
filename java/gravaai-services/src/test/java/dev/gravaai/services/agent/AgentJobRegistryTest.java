package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.agent.AgentJobRegistry.Job;
import dev.gravaai.services.agent.AgentJobRegistry.Kind;
import dev.gravaai.services.agent.AgentJobRegistry.Patch;
import dev.gravaai.services.agent.AgentJobRegistry.Status;

/** Port of {@code electron/agent/jobs.test.ts} (plan §5.16). */
class AgentJobRegistryTest {

	@Test
	@DisplayName("creates queued jobs with sequential ids")
	void createsQueuedJobs() {
		AgentJobRegistry jobs = new AgentJobRegistry();
		Job first = jobs.create(Kind.EXPORT, "Export 1080p");
		Job second = jobs.create(Kind.MEETING, "Standup");
		assertEquals("job-1", first.id());
		assertEquals("job-2", second.id());
		assertEquals(Status.QUEUED, first.status());
		assertFalse(AgentJobRegistry.isTerminalJobStatus(first.status()));
	}

	@Test
	@DisplayName("clamps progress and stamps the finish time once")
	void clampsProgress() {
		AgentJobRegistry jobs = new AgentJobRegistry();
		Job job = jobs.create(Kind.TRANSCRIBE, "Whisper");
		jobs.update(job.id(), new Patch(Status.RUNNING, 3.0, null, null, null));
		assertEquals(1.0, job.progress());
		jobs.update(job.id(), new Patch(Status.DONE, -2.0, null, null, null));
		assertEquals(0.0, job.progress());
		assertTrue(job.finishedAt() != null);
		assertTrue(AgentJobRegistry.isTerminalJobStatus(job.status()));
	}

	@Test
	@DisplayName("prefers the newest running job of a kind")
	void prefersNewestRunning() {
		AgentJobRegistry jobs = new AgentJobRegistry();
		Job done = jobs.create(Kind.EXPORT, "old export");
		jobs.update(done.id(), Patch.of(Status.DONE));
		Job running = jobs.create(Kind.EXPORT, "new export");
		jobs.update(running.id(), Patch.of(Status.RUNNING));
		assertEquals(running.id(), jobs.latest(Kind.EXPORT).orElseThrow().id());
		assertTrue(jobs.latest(Kind.MEETING).isEmpty());
	}

	@Test
	@DisplayName("cancels through the job's own cancel handle")
	void cancelsThroughHandle() {
		AgentJobRegistry jobs = new AgentJobRegistry();
		AtomicInteger cancelled = new AtomicInteger();
		Job job = jobs.create(Kind.EXPORT, "Export", cancelled::incrementAndGet);
		jobs.update(job.id(), Patch.of(Status.RUNNING));
		assertTrue(jobs.cancel(job.id()));
		assertEquals(1, cancelled.get());
		assertEquals(Status.CANCELLED, job.status());
		assertFalse(jobs.cancel(job.id()));
	}

	@Test
	@DisplayName("never moves a finished job back to running on a late report")
	void neverMovesFinishedBack() {
		AgentJobRegistry jobs = new AgentJobRegistry();
		Job job = jobs.create(Kind.EXPORT, "Export");
		jobs.update(job.id(), new Patch(Status.RUNNING, 0.1, null, null, null));
		jobs.cancel(job.id());
		jobs.update(job.id(), new Patch(Status.RUNNING, 0.12, "12%", null, null));
		assertEquals(Status.CANCELLED, job.status());
		assertEquals(0.1, job.progress());
		jobs.update(job.id(), new Patch(null, 0.5, "stale", null, null));
		assertEquals("Cancelled", job.message());
		jobs.update(job.id(), new Patch(Status.CANCELLED, null, "Cancelled", null, null));
		assertEquals(Status.CANCELLED, job.status());
	}

	@Test
	@DisplayName("fails only the unfinished jobs of the asked kinds")
	void failsOnlyUnfinishedKinds() {
		AgentJobRegistry jobs = new AgentJobRegistry();
		Job render = jobs.create(Kind.EXPORT, "render");
		jobs.update(render.id(), Patch.of(Status.RUNNING));
		Job transcript = jobs.create(Kind.TRANSCRIBE, "transcribe");
		jobs.update(transcript.id(), Patch.of(Status.DONE));
		Job meeting = jobs.create(Kind.MEETING, "meeting");
		jobs.update(meeting.id(), Patch.of(Status.RUNNING));

		jobs.failRunning(List.of(Kind.EXPORT), "the editor reloaded");

		assertEquals(Status.FAILED, jobs.get(render.id()).status());
		assertTrue(jobs.get(render.id()).error().contains("reloaded"));
		assertEquals(Status.DONE, jobs.get(transcript.id()).status());
		assertEquals(Status.RUNNING, jobs.get(meeting.id()).status());
	}

	@Test
	@DisplayName("keeps finished jobs bounded")
	void keepsFinishedBounded() {
		AgentJobRegistry jobs = new AgentJobRegistry();
		for (int index = 0; index < 30; index++) {
			Job job = jobs.create(Kind.EXPORT, "export " + index);
			jobs.update(job.id(), Patch.of(Status.DONE));
		}
		assertTrue(jobs.list().size() <= 21);
		assertEquals(Status.DONE, jobs.get("job-30").status());
	}

	@Test
	@DisplayName("formats one line per job for inspect jobs")
	void formatsJobLine() {
		AgentJobRegistry jobs = new AgentJobRegistry();
		Job job = jobs.create(Kind.EXPORT, "Export 1080p");
		jobs.update(job.id(), new Patch(Status.RUNNING, 0.42, "encoding", null, null));
		String line = AgentJobRegistry.formatJobLine(job);
		assertTrue(line.contains("job-1"));
		assertTrue(line.contains("export running"));
		assertTrue(line.contains("42%"));
		assertTrue(line.contains("encoding"));
		assertNull(jobs.get("nope"));
	}
}
