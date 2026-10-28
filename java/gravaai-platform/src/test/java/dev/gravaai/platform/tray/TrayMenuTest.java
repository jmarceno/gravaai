package dev.gravaai.platform.tray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrayMenuTest {

	@Test
	@DisplayName("idle menu is Show Overlay, Open Gravaai, separator, Quit")
	void idleMenuMatchesElectronMenu() {
		TrayMenu menu = new TrayMenu(false);

		List<TrayMenu.Item> items = menu.items();
		assertEquals(4, items.size());
		assertEquals("Show Overlay", items.get(0).label());
		assertEquals(TrayMenu.SHOW_OVERLAY_ID, items.get(0).id());
		assertEquals("Open Gravaai", items.get(1).label());
		assertEquals(TrayMenu.OPEN_GRAVAAI_ID, items.get(1).id());
		assertTrue(items.get(2).separator());
		assertEquals(TrayMenu.SEPARATOR_ID, items.get(2).id());
		assertEquals("Quit", items.get(3).label());
		assertEquals(TrayMenu.QUIT_ID, items.get(3).id());
		assertFalse(menu.isRecording());
	}

	@Test
	@DisplayName("recording menu adds Stop Recording before the separator")
	void recordingMenuAddsStopRecording() {
		TrayMenu menu = new TrayMenu(true);

		List<TrayMenu.Item> items = menu.items();
		assertEquals(5, items.size());
		assertEquals("Show Overlay", items.get(0).label());
		assertEquals("Open Gravaai", items.get(1).label());
		assertEquals("Stop Recording", items.get(2).label());
		assertEquals(TrayMenu.STOP_RECORDING_ID, items.get(2).id());
		assertTrue(items.get(3).separator());
		assertEquals("Quit", items.get(4).label());
		assertTrue(menu.isRecording());
	}

	@Test
	@DisplayName("dispatches clicks to the wired actions")
	void dispatchesActions() {
		AtomicInteger showOverlay = new AtomicInteger();
		AtomicInteger openGravaai = new AtomicInteger();
		AtomicInteger stopRecording = new AtomicInteger();
		AtomicInteger quit = new AtomicInteger();
		TrayMenu.Actions actions = new TrayMenu.Actions(showOverlay::incrementAndGet,
				openGravaai::incrementAndGet, stopRecording::incrementAndGet, quit::incrementAndGet);
		TrayMenu recording = new TrayMenu(true);

		assertTrue(recording.dispatch(TrayMenu.SHOW_OVERLAY_ID, actions));
		assertTrue(recording.dispatch(TrayMenu.OPEN_GRAVAAI_ID, actions));
		assertTrue(recording.dispatch(TrayMenu.STOP_RECORDING_ID, actions));
		assertTrue(recording.dispatch(TrayMenu.QUIT_ID, actions));
		assertFalse(recording.dispatch(TrayMenu.SEPARATOR_ID, actions));
		assertFalse(recording.dispatch(999, actions));
		assertFalse(recording.dispatch(TrayMenu.ROOT_ID, actions));

		assertEquals(1, showOverlay.get());
		assertEquals(1, openGravaai.get());
		assertEquals(1, stopRecording.get());
		assertEquals(1, quit.get());
	}

	@Test
	@DisplayName("item lookup ignores the root")
	void itemLookup() {
		TrayMenu idle = new TrayMenu(false);

		assertEquals("Quit", idle.item(TrayMenu.QUIT_ID).label());
		assertNull(idle.item(TrayMenu.ROOT_ID));
		assertNull(idle.item(TrayMenu.STOP_RECORDING_ID));
	}

	@Test
	@DisplayName("actions require every callback")
	void actionsRequireCallbacks() {
		Runnable noop = () -> {};
		assertThrows(NullPointerException.class, () -> new TrayMenu.Actions(null, noop, noop, noop));
		assertThrows(NullPointerException.class, () -> new TrayMenu.Actions(noop, null, noop, noop));
		assertThrows(NullPointerException.class, () -> new TrayMenu.Actions(noop, noop, null, noop));
		assertThrows(NullPointerException.class, () -> new TrayMenu.Actions(noop, noop, noop, null));
	}
}
