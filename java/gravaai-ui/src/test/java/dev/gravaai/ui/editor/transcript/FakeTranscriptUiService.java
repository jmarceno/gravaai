package dev.gravaai.ui.editor.transcript;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Scriptable {@link TranscriptUiService} for the panel/gallery tests. */
final class FakeTranscriptUiService implements TranscriptUiService {

	private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
	final List<Status> generationSteps = new ArrayList<>();
	final Map<String, String> speakers = new HashMap<>();
	int generateCalls;
	int cancelCalls;
	private Status status = new Status(State.IDLE, 0, "", List.of(), null);

	@Override
	public Status status() {
		return status;
	}

	@Override
	public AutoCloseable addListener(Consumer<Status> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	@Override
	public void generateCaptions() {
		generateCalls++;
		for (Status step : generationSteps) {
			publish(step);
		}
	}

	@Override
	public void cancel() {
		cancelCalls++;
	}

	@Override
	public String speakerForCue(String cueId) {
		return speakers.get(cueId);
	}

	void setStatus(Status next) {
		publish(next);
	}

	private void publish(Status next) {
		status = next;
		for (Consumer<Status> listener : listeners) {
			listener.accept(next);
		}
	}
}
