package dev.gravaai.services.captions;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import dev.gravaai.services.meeting.CancelSignal;

/**
 * JavaFX-free controller state machine for auto-captions (port of
 * src/components/video-editor/captions/useAutoCaptionController.ts): idle -> extracting ->
 * transcribing -> parsing -> done/error/cancelled. The editor/AppContext subscribes and drives it.
 */
public final class AutoCaptionService implements AutoCloseable {

	public enum State {
		IDLE,
		EXTRACTING,
		TRANSCRIBING,
		PARSING,
		DONE,
		ERROR,
		CANCELLED
	}

	/** One immutable status snapshot pushed to listeners. */
	public record Status(
			State state,
			int progress,
			String message,
			@Nullable List<CaptionCue> cues,
			@Nullable String error) {

		public Status {
			cues = cues == null ? List.of() : List.copyOf(cues);
		}

		public boolean generating() {
			return state == State.EXTRACTING || state == State.TRANSCRIBING || state == State.PARSING;
		}
	}

	/** The generation step, injectable so the state machine is testable without processes. */
	@FunctionalInterface
	public interface Job {
		CaptionGenerator.Result run(
				CaptionGenerator.Options options,
				java.util.function.IntConsumer progress,
				CancelSignal signal);
	}

	private final Job job;
	private final ExecutorService executor;
	private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
	private volatile Status status = new Status(State.IDLE, 0, "", List.of(), null);
	private volatile CancelSignal.@Nullable Flag cancelSignal;

	public AutoCaptionService(CaptionGenerator generator, ExecutorService executor) {
		this(generator::generateFromVideo, executor);
	}

	public AutoCaptionService(Job job, ExecutorService executor) {
		this.job = job;
		this.executor = executor;
	}

	public static AutoCaptionService withDaemonThread(CaptionGenerator generator) {
		return new AutoCaptionService(generator,
				Executors.newSingleThreadExecutor(runnable -> {
					Thread thread = Thread.ofPlatform().daemon().name("gravaai-captions").unstarted(runnable);
					return thread;
				}));
	}

	public Status status() {
		return status;
	}

	public AutoCloseable addListener(Consumer<Status> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Starts generation; replaces any in-flight run by cancelling it first. */
	public void start(CaptionGenerator.Options options) {
		cancel();
		CancelSignal.Flag flag = new CancelSignal.Flag();
		cancelSignal = flag;
		executor.execute(() -> runGeneration(options, flag));
	}

	public void cancel() {
		CancelSignal.Flag flag = cancelSignal;
		if (flag != null) {
			flag.cancel();
		}
	}

	private void runGeneration(CaptionGenerator.Options options, CancelSignal.Flag flag) {
		publish(new Status(State.EXTRACTING, 0, "Extracting audio…", List.of(), null));
		try {
			flag.throwIfCancelled();
			publish(new Status(State.TRANSCRIBING, 0, "Transcribing…", List.of(), null));
			CaptionGenerator.Result result = job.run(options, percent -> {
				if (!flag.isCancelled()) {
					publish(new Status(State.TRANSCRIBING, percent, "Transcribing…",
							List.of(), null));
				}
			}, flag);
			publish(new Status(State.PARSING, 100, "Saving captions…", List.of(), null));
			flag.throwIfCancelled();
			publish(new Status(State.DONE, 100, doneMessage(result), result.cues(), null));
		} catch (CancelSignal.CancelledException cancelled) {
			publish(new Status(State.CANCELLED, 0, "Cancelled.", List.of(), null));
		} catch (RuntimeException error) {
			String message = error.getMessage() == null || error.getMessage().isBlank()
					? "Failed to generate auto captions"
					: error.getMessage();
			publish(new Status(State.ERROR, 0, message, List.of(), message));
		}
	}

	/** The success toast of the {@code generate-auto-captions} handler. */
	static String doneMessage(CaptionGenerator.Result result) {
		int count = result.cues().size();
		return "recording".equals(result.audioSourceLabel())
				? "Generated " + count + " caption cues."
				: "Generated " + count + " caption cues from the " + result.audioSourceLabel() + ".";
	}

	private void publish(Status next) {
		status = next;
		for (Consumer<Status> listener : listeners) {
			listener.accept(next);
		}
	}

	@Override
	public void close() {
		cancel();
		executor.shutdownNow();
	}
}
