package dev.gravaai.services.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import dev.gravaai.core.json.Json;

/**
 * Shared registry for long-running agent work (port of {@code electron/agent/jobs.ts},
 * plan §5.16). The renderer/worker reports progress into it; {@code inspect jobs}, {@code job
 * status} and {@code job wait} read from it.
 */
public final class AgentJobRegistry {

	/** {@code AgentJobKind}. */
	public enum Kind {
		TRANSCRIBE("transcribe"),
		EXPORT("export"),
		MEETING("meeting");

		private final String wire;

		Kind(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		public static Kind fromWire(String value) {
			for (Kind kind : values()) {
				if (kind.wire.equals(value)) {
					return kind;
				}
			}
			return null;
		}
	}

	/** {@code AgentJobStatus}. */
	public enum Status {
		QUEUED("queued"),
		RUNNING("running"),
		DONE("done"),
		FAILED("failed"),
		CANCELLED("cancelled");

		private final String wire;

		Status(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		public static Status fromWire(String value) {
			for (Status status : values()) {
				if (status.wire.equals(value)) {
					return status;
				}
			}
			return null;
		}
	}

	/** {@code AgentJob}: mutable like the TS object; the registry hands out the shared instance. */
	public static final class Job {
		private final String id;
		private final Kind kind;
		private final String label;
		private final long createdAt;
		private Status status = Status.QUEUED;
		private Double progress;
		private String message;
		private String error;
		private Object result;
		private Long finishedAt;
		private Runnable cancel;

		private Job(String id, Kind kind, String label, Runnable cancel) {
			this.id = id;
			this.kind = kind;
			this.label = label;
			this.cancel = cancel;
			this.createdAt = System.currentTimeMillis();
		}

		public String id() {
			return id;
		}

		public Kind kind() {
			return kind;
		}

		public String label() {
			return label;
		}

		public Status status() {
			return status;
		}

		/** 0–1, or null while the work has not reported anything yet. */
		public Double progress() {
			return progress;
		}

		public String message() {
			return message;
		}

		public String error() {
			return error;
		}

		public Object result() {
			return result;
		}

		public long createdAt() {
			return createdAt;
		}

		public Long finishedAt() {
			return finishedAt;
		}
	}

	/** Fields of an update; null means "not provided" (like the TS optional patch fields). */
	public record Patch(Status status, Double progress, String message, String error, Object result) {
		public static Patch of(Status status) {
			return new Patch(status, null, null, null, null);
		}

		public static Patch progress(double progress, String message) {
			return new Patch(null, progress, message, null, null);
		}
	}

	private static final int MAX_FINISHED = 20;

	private final Map<String, Job> jobs = new LinkedHashMap<>();
	private long counter;

	/** {@code create}. */
	public synchronized Job create(Kind kind, String label, Runnable cancel) {
		counter += 1;
		Job job = new Job("job-" + counter, kind, label, cancel);
		jobs.put(job.id, job);
		trim();
		return job;
	}

	public synchronized Job create(Kind kind, String label) {
		return create(kind, label, null);
	}

	/** {@code update}: out-of-order reports never move a finished job back to running. */
	public synchronized Job update(String id, Patch patch) {
		Job job = jobs.get(id);
		if (job == null) {
			return null;
		}
		if (isTerminalJobStatus(job.status)) {
			if (patch.status() == null || !isTerminalJobStatus(patch.status())) {
				return job;
			}
		}
		if (patch.progress() != null) {
			job.progress = Math.max(0, Math.min(1, patch.progress()));
		}
		if (patch.status() != null) {
			job.status = patch.status();
		}
		if (patch.message() != null) {
			job.message = patch.message();
		}
		if (patch.error() != null) {
			job.error = patch.error();
		}
		if (patch.result() != null) {
			job.result = patch.result();
		}
		if (isTerminalJobStatus(job.status)) {
			if (job.finishedAt == null) {
				job.finishedAt = System.currentTimeMillis();
			}
			job.cancel = null;
		}
		return job;
	}

	public synchronized Job get(String id) {
		return jobs.get(id);
	}

	/** Newest job, optionally of one kind, preferring work that is still running. */
	public synchronized Optional<Job> latest(Kind kind) {
		List<Job> matching = list();
		if (kind != null) {
			matching = matching.stream().filter(job -> job.kind == kind).toList();
		}
		if (matching.isEmpty()) {
			return Optional.empty();
		}
		for (int index = matching.size() - 1; index >= 0; index--) {
			if (!isTerminalJobStatus(matching.get(index).status)) {
				return Optional.of(matching.get(index));
			}
		}
		return Optional.of(matching.get(matching.size() - 1));
	}

	public Optional<Job> latest() {
		return latest(null);
	}

	/** {@code cancel}: runs the job's own abort handle and marks it cancelled. */
	public synchronized boolean cancel(String id) {
		Job job = jobs.get(id);
		if (job == null || isTerminalJobStatus(job.status)) {
			return false;
		}
		if (job.cancel != null) {
			try {
				job.cancel.run();
			} catch (RuntimeException error) {
				// The work is gone already; cancelling is still the right answer.
			}
		}
		job.status = Status.CANCELLED;
		job.message = "Cancelled";
		job.finishedAt = System.currentTimeMillis();
		job.cancel = null;
		return true;
	}

	/** Jobs in creation order. */
	public synchronized List<Job> list() {
		return List.copyOf(jobs.values());
	}

	/** Fail unfinished jobs of the given kinds (editor reload, app quit). */
	public synchronized void failRunning(List<Kind> kinds, String error) {
		for (Job job : jobs.values()) {
			if (isTerminalJobStatus(job.status) || !kinds.contains(job.kind)) {
				continue;
			}
			job.status = Status.FAILED;
			job.error = error;
			job.finishedAt = System.currentTimeMillis();
			job.cancel = null;
		}
	}

	/** One line per job for {@code inspect jobs} and {@code job status}. */
	public static String formatJobLine(Job job) {
		List<String> parts = new ArrayList<>();
		parts.add(job.id);
		parts.add(job.kind.wire());
		parts.add(job.status.wire());
		if (job.progress != null) {
			parts.add(Math.round(job.progress * 100) + "%");
		}
		if (job.message != null && !job.message.isEmpty()) {
			parts.add(job.message);
		}
		if (job.error != null && !job.error.isEmpty()) {
			parts.add("(" + job.error + ")");
		}
		if (job.result != null) {
			parts.add(Json.MAPPER.writeValueAsString(job.result));
		}
		return String.join(" ", parts);
	}

	public static boolean isTerminalJobStatus(Status status) {
		return status == Status.DONE || status == Status.FAILED || status == Status.CANCELLED;
	}

	/** Removes the oldest finished jobs beyond the bound so memory stays flat. */
	private void trim() {
		List<Job> finished = jobs.values().stream()
				.filter(job -> isTerminalJobStatus(job.status)).toList();
		int excess = finished.size() - MAX_FINISHED;
		for (int index = 0; index < excess; index++) {
			jobs.remove(finished.get(index).id);
		}
	}

	/** Lower-case formatting hook for diagnostics (Locale safe). */
	static String lower(String value) {
		return value.toLowerCase(Locale.ROOT);
	}
}
