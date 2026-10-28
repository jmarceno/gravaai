package dev.gravaai.core.event;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A JavaFX-free listener registry for service-to-UI events (plan §5.3, §4.1).
 *
 * <p>Services hold one {@code UiEvents<T>} per event and call {@link #publish(Object)}; the UI
 * subscribes with {@link #subscribe(Consumer)}. Listeners run on the publishing thread — the UI
 * layer wraps them with its {@code Platform.runLater} adapter (gravaai-ui, not this module; core
 * never imports JavaFX, plan §4.2). A listener that throws cannot break the publisher or the other
 * listeners: the failure is logged and publishing continues.
 */
public final class UiEvents<T> {

	private static final System.Logger LOG = System.getLogger("gravaai.events");

	private final CopyOnWriteArrayList<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

	/** Registers a listener; close the returned {@link Subscription} to remove it. */
	public Subscription subscribe(Consumer<T> listener) {
		Objects.requireNonNull(listener, "listener");
		listeners.add(listener);
		return new Subscription() {
			private final AtomicBoolean closed = new AtomicBoolean();

			@Override
			public void close() {
				if (closed.compareAndSet(false, true)) {
					listeners.remove(listener);
				}
			}
		};
	}

	/** Publishes to every current listener, on this thread; never throws because of a listener. */
	public void publish(T event) {
		for (Consumer<T> listener : listeners) {
			try {
				listener.accept(event);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "event listener failed", error);
			}
		}
	}

	public int listenerCount() {
		return listeners.size();
	}

	public boolean hasListeners() {
		return !listeners.isEmpty();
	}
}
