package dev.gravaai.services.elements;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import dev.gravaai.core.project.ElementComponent;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.SceneElement;

/**
 * The element errors of one editor session, shared by every render path (preview, agent
 * {@code frames}, exports). A record is tied to the exact element and component source that
 * failed, so an edit that changes either makes the old error disappear without a re-render.
 * Thread-safe: render threads report, the UI and the agent read.
 */
public final class ElementDiagnostics {

	/** One failing element. */
	public record ElementError(String elementId, double timeMs, String message, int line,
			String fingerprint) {}

	private final Map<String, ElementError> errors = new ConcurrentHashMap<>();
	private final List<Consumer<ElementDiagnostics>> listeners = new CopyOnWriteArrayList<>();

	/** Records a failure; listeners hear about new or changed errors. */
	public void report(ElementError error) {
		ElementError previous = errors.put(error.elementId(), error);
		if (previous == null || !previous.message().equals(error.message())
				|| !previous.fingerprint().equals(error.fingerprint())) {
			publish();
		}
	}

	/** Clears an element's error after a frame of it rendered fine. */
	public void clear(String elementId, String fingerprint) {
		ElementError previous = errors.get(elementId);
		if (previous != null && previous.fingerprint().equals(fingerprint)) {
			errors.remove(elementId);
			publish();
		}
	}

	/** The errors that still apply to {@code state}, in timeline order. */
	public List<ElementError> current(ProjectEditorState state) {
		List<ElementError> result = new ArrayList<>();
		for (SceneElement element : state.elements()) {
			ElementError error = errors.get(element.id());
			if (error != null && error.fingerprint().equals(
					fingerprint(element, state.elementComponents()))) {
				result.add(error);
			}
		}
		result.sort(Comparator.comparingDouble(ElementError::timeMs));
		return result;
	}

	public Optional<ElementError> current(ProjectEditorState state, String elementId) {
		return current(state).stream().filter(error -> error.elementId().equals(elementId))
				.findFirst();
	}

	public Runnable subscribe(Consumer<ElementDiagnostics> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	private void publish() {
		for (Consumer<ElementDiagnostics> listener : listeners) {
			listener.accept(this);
		}
	}

	/** Identifies what was rendered: the element record plus its component's source. */
	public static String fingerprint(SceneElement element, List<ElementComponent> project) {
		String source = BuiltinComponents.resolve(element.component(), project)
				.map(ElementComponent::source).orElse("<missing>");
		return Integer.toHexString(element.hashCode()) + ":" + Integer.toHexString(source.hashCode());
	}
}
