package dev.gravaai.services.shortcuts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Port of the behaviour in {@code electron/globalShortcuts/dispatch.ts} (the TS file has no test).
 */
class GlobalShortcutDispatcherTest {

	private static final class FakeHud implements GlobalShortcutHud {
		private boolean alive = true;
		private boolean ready;
		private final List<GlobalShortcutAction> delivered = new ArrayList<>();

		@Override
		public boolean isAlive() {
			return alive;
		}

		@Override
		public boolean isReady() {
			return ready;
		}

		@Override
		public void deliver(GlobalShortcutAction action) {
			delivered.add(action);
		}
	}

	private static final class Clock {
		private long now;

		Clock(long now) {
			this.now = now;
		}

		long get() {
			return now;
		}
	}

	@Test
	@DisplayName("dispatches immediately when the HUD is ready")
	void dispatchesWhenReady() {
		FakeHud hud = new FakeHud();
		hud.ready = true;
		GlobalShortcutDispatcher dispatcher = new GlobalShortcutDispatcher(() -> hud, new Clock(1_000)::get, message -> {});
		dispatcher.dispatch(GlobalShortcutAction.TOGGLE_RECORDING);
		assertEquals(List.of(GlobalShortcutAction.TOGGLE_RECORDING), hud.delivered);
		assertFalse(dispatcher.hasPendingAction());
	}

	@Test
	@DisplayName("queues until the HUD reports ready and then delivers")
	void queuesUntilHudReady() {
		FakeHud hud = new FakeHud();
		List<String> logs = new ArrayList<>();
		GlobalShortcutDispatcher dispatcher = new GlobalShortcutDispatcher(() -> hud, new Clock(1_000)::get, logs::add);
		dispatcher.dispatch(GlobalShortcutAction.TOGGLE_MEETING);
		assertTrue(hud.delivered.isEmpty());
		assertTrue(dispatcher.hasPendingAction());
		assertTrue(logs.get(0).contains("[global-shortcuts] Queued toggle-meeting until the HUD is ready"), logs.get(0));

		hud.ready = true;
		dispatcher.hudReady();
		assertEquals(List.of(GlobalShortcutAction.TOGGLE_MEETING), hud.delivered);
		assertFalse(dispatcher.hasPendingAction());
	}

	@Test
	@DisplayName("drops a queued action older than 30 seconds")
	void dropsStalePendingAction() {
		FakeHud hud = new FakeHud();
		Clock clock = new Clock(1_000);
		GlobalShortcutDispatcher dispatcher = new GlobalShortcutDispatcher(() -> hud, clock::get, message -> {});
		dispatcher.dispatch(GlobalShortcutAction.TOGGLE_PAUSE);
		clock.now = 1_000 + GlobalShortcutDispatcher.PENDING_MAX_AGE_MS;
		hud.ready = true;
		dispatcher.hudReady();
		assertTrue(hud.delivered.isEmpty());

		FakeHud second = new FakeHud();
		Clock secondClock = new Clock(1_000);
		GlobalShortcutDispatcher other = new GlobalShortcutDispatcher(() -> second, secondClock::get, message -> {});
		other.dispatch(GlobalShortcutAction.TOGGLE_PAUSE);
		secondClock.now = 1_000 + GlobalShortcutDispatcher.PENDING_MAX_AGE_MS - 1;
		second.ready = true;
		other.hudReady();
		assertEquals(List.of(GlobalShortcutAction.TOGGLE_PAUSE), second.delivered);
	}

	@Test
	@DisplayName("debounces the same action inside 400 ms")
	void debouncesSameAction() {
		FakeHud hud = new FakeHud();
		hud.ready = true;
		Clock clock = new Clock(1_000);
		GlobalShortcutDispatcher dispatcher = new GlobalShortcutDispatcher(() -> hud, clock::get, message -> {});
		dispatcher.dispatch(GlobalShortcutAction.TOGGLE_PAUSE);
		clock.now = 1_399;
		dispatcher.dispatch(GlobalShortcutAction.TOGGLE_PAUSE);
		assertEquals(1, hud.delivered.size());
		clock.now = 1_400;
		dispatcher.dispatch(GlobalShortcutAction.TOGGLE_PAUSE);
		assertEquals(2, hud.delivered.size());
		clock.now = 1_500;
		dispatcher.dispatch(GlobalShortcutAction.TOGGLE_RECORDING);
		assertEquals(3, hud.delivered.size());
	}

	@Test
	@DisplayName("recreates the HUD through the provider when a shortcut arrives without one")
	void providerRecreatesTheHud() {
		FakeHud[] current = {null};
		int[] calls = {0};
		Supplier<GlobalShortcutHud> provider = () -> {
			calls[0]++;
			return current[0];
		};
		GlobalShortcutDispatcher dispatcher = new GlobalShortcutDispatcher(provider, new Clock(1_000)::get, message -> {});
		dispatcher.dispatch(GlobalShortcutAction.TOGGLE_RECORDING);
		assertEquals(1, calls[0]);
		assertTrue(dispatcher.hasPendingAction());

		FakeHud hud = new FakeHud();
		hud.ready = true;
		current[0] = hud;
		dispatcher.hudReady();
		assertEquals(List.of(GlobalShortcutAction.TOGGLE_RECORDING), hud.delivered);
		assertEquals(2, calls[0]);
	}

	@Test
	@DisplayName("hudReady without a pending action does nothing")
	void hudReadyWithoutPending() {
		FakeHud hud = new FakeHud();
		hud.ready = true;
		GlobalShortcutDispatcher dispatcher = new GlobalShortcutDispatcher(() -> hud, new Clock(1_000)::get, message -> {});
		dispatcher.hudReady();
		assertTrue(hud.delivered.isEmpty());
	}
}
