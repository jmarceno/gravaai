package dev.gravaai.services.meeting;

/** A listener registration on a meeting service event; closing it unsubscribes (plan §5.3). */
public interface Subscription extends AutoCloseable {

	@Override
	void close();
}
