package dev.gravaai.core.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link UiEvents} / {@link Subscription}, the FX-free event registry of plan §5.3. */
class UiEventsTest {

	@Test
	@DisplayName("publishes to every subscriber in registration order")
	void publishesToEverySubscriber() {
		UiEvents<String> events = new UiEvents<>();
		List<String> received = new ArrayList<>();
		events.subscribe(received::add);
		events.subscribe(received::add);

		events.publish("recording-state-changed");

		assertEquals(List.of("recording-state-changed", "recording-state-changed"), received);
		assertEquals(2, events.listenerCount());
		assertTrue(events.hasListeners());
	}

	@Test
	@DisplayName("a closed subscription stops receiving events and closes idempotently")
	void closingSubscriptionStopsEvents() {
		UiEvents<Integer> events = new UiEvents<>();
		List<Integer> first = new ArrayList<>();
		List<Integer> second = new ArrayList<>();
		Subscription subscription = events.subscribe(first::add);
		events.subscribe(second::add);

		subscription.close();
		subscription.close();

		events.publish(1);

		assertEquals(List.of(), first);
		assertEquals(List.of(1), second);
		assertEquals(1, events.listenerCount());
	}

	@Test
	@DisplayName("isolates a failing listener from the publisher and the other listeners")
	void isolatesFailingListener() {
		UiEvents<String> events = new UiEvents<>();
		List<String> received = new ArrayList<>();
		events.subscribe(event -> {
			throw new IllegalStateException("broken listener");
		});
		events.subscribe(received::add);

		assertDoesNotThrow(() -> events.publish("tick"));
		assertEquals(List.of("tick"), received);
	}

	@Test
	@DisplayName("can be published to and subscribed from several threads")
	void supportsConcurrentUse() throws Exception {
		UiEvents<Integer> events = new UiEvents<>();
		AtomicInteger received = new AtomicInteger();
		events.subscribe(value -> received.incrementAndGet());

		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
			for (int thread = 0; thread < 8; thread++) {
				futures.add(executor.submit(() -> {
					start.await();
					for (int iteration = 0; iteration < 100; iteration++) {
						events.publish(iteration);
					}
					return null;
				}));
			}
			start.countDown();
			for (var future : futures) {
				future.get(30, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}

		assertEquals(800, received.get());
	}

	@Test
	@DisplayName("has no listeners after the last subscription closes")
	void emptiesAfterLastSubscriptionCloses() {
		UiEvents<String> events = new UiEvents<>();
		Subscription first = events.subscribe(event -> {});
		Subscription second = events.subscribe(event -> {});

		first.close();
		assertTrue(events.hasListeners());
		assertEquals(1, events.listenerCount());
		second.close();
		assertFalse(events.hasListeners());
		assertEquals(0, events.listenerCount());
		assertDoesNotThrow(() -> events.publish("no listeners"));
	}
}
