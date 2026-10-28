package dev.gravaai.core.event;

/**
 * A listener registration (plan §5.3): services return one of these from every {@code subscribe}
 * method so the UI can unsubscribe on view teardown.
 *
 * <p>{@link #close()} must be idempotent and safe to call from any thread; it may be called while
 * an event is being published.
 */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

	/** Removes the listener. Idempotent. */
	@Override
	void close();
}
