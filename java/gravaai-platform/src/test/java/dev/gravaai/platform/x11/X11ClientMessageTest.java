package dev.gravaai.platform.x11;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class X11ClientMessageTest {

	@Test
	@DisplayName("lays out an XClientMessageEvent inside the XEvent union")
	void clientMessageLayout() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment display = arena.allocate(8, 8);

			MemorySegment event = X11Bridge.buildClientMessage(arena, display, 0x1234567L, 0x89abL,
					new long[] {1, 2, 3, 4, 5});

			assertEquals(192, X11Bridge.EVENT_SIZE);
			assertEquals(33, event.get(JAVA_INT, X11Bridge.EVENT_TYPE_OFFSET));
			assertEquals(1, event.get(JAVA_INT, X11Bridge.EVENT_FORMAT_OFFSET));
			assertEquals(display.address(), event.get(ADDRESS, X11Bridge.EVENT_DISPLAY_OFFSET).address());
			assertEquals(0x1234567L, event.get(JAVA_LONG, X11Bridge.EVENT_WINDOW_OFFSET));
			assertEquals(0x89abL, event.get(JAVA_LONG, X11Bridge.EVENT_MESSAGE_TYPE_OFFSET));
			assertEquals(32, event.get(JAVA_INT, X11Bridge.EVENT_LONG_FORMAT_OFFSET));
			for (int index = 0; index < 5; index++) {
				assertEquals(index + 1, event.get(JAVA_LONG, X11Bridge.EVENT_DATA_OFFSET + index * 8L));
			}
			assertEquals(96, X11Bridge.EVENT_DATA_OFFSET + 5 * 8);
		}
	}

	@Test
	@DisplayName("zero-fills the unused client message data slots")
	void zeroFillsMissingData() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment display = arena.allocate(8, 8);

			MemorySegment event = X11Bridge.buildClientMessage(arena, display, 1L, 2L, new long[] {7});

			assertEquals(7, event.get(JAVA_LONG, X11Bridge.EVENT_DATA_OFFSET));
			for (int index = 1; index < X11Bridge.EVENT_DATA_LONG_COUNT; index++) {
				assertEquals(0, event.get(JAVA_LONG, X11Bridge.EVENT_DATA_OFFSET + index * 8L));
			}
		}
	}

	@Test
	@DisplayName("uses the EWMH and XShape constants from the X11 headers")
	void usesHeaderConstants() {
		assertEquals(33, X11Bridge.CLIENT_MESSAGE);
		assertEquals((1L << 19) | (1L << 20), X11Bridge.SUBSTRUCTURE_MASK);
		assertEquals(1, X11Bridge.NET_WM_STATE_ADD);
		assertEquals(8, X11Bridge.NET_WM_MOVERESIZE_MOVE);
		assertEquals(1, X11Bridge.SOURCE_APPLICATION);
		assertEquals(2, X11Bridge.SHAPE_INPUT);
		assertEquals(0, X11Bridge.SHAPE_SET);
		assertEquals(0, X11Bridge.SHAPE_UNSORTED);
	}

	@Test
	@DisplayName("tryOpen does not throw when no X11 display is reachable")
	void tryOpenNeverThrows() {
		Optional<X11Bridge> bridge = X11Bridge.tryOpen();

		bridge.ifPresent(X11Bridge::close);
		assertTrue(bridge.isEmpty() || bridge.isPresent());
	}
}
