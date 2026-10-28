package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from the picker behaviour in {@code electron/ipc/register/sources.test.ts}. */
class SourceListPickerTest {

	@Test
	@DisplayName("keeps the source list pending until a source is selected")
	void keepsTheSourceListPendingUntilSelected() {
		AtomicInteger created = new AtomicInteger();
		SourceListPicker picker = new SourceListPicker(created::incrementAndGet);
		CompletableFuture<SourceListPicker.SourceListPickResult> first = picker.open();
		CompletableFuture<SourceListPicker.SourceListPickResult> second = picker.open();
		assertTrue(first == second);
		assertEquals(1, created.get());

		SelectedSource source = SelectedSource.screen("screen:1:0", "Screen 1", "1");
		picker.complete(source);
		assertEquals(SourceListPicker.SourceListPickResult.picked(source), first.join());
		assertFalse(picker.isOpen());
	}

	@Test
	@DisplayName("settles cancellation when the source list window is dismissed")
	void settlesCancellationWhenDismissed() {
		SourceListPicker picker = new SourceListPicker(() -> {
		});
		CompletableFuture<SourceListPicker.SourceListPickResult> pending = picker.open();
		picker.cancel();
		assertEquals(SourceListPicker.SourceListPickResult.cancelledResult(), pending.join());
		assertFalse(picker.isOpen());
		// A late completion after dismissal has no request to settle.
		picker.complete(SelectedSource.screen("screen:1:0", "Screen", null));
	}
}
