package dev.gravaai.spike;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * FFM bridge to the Xlib connection GTK already holds for JavaFX (JavaFX runs on XWayland under
 * COSMIC). COSMIC's X11 window manager ignores keep-above and position requests made before map
 * and reverts client moves, so these go through EWMH client messages instead, as GTK itself does.
 * Every call must run on the FX thread, which is GTK's thread.
 */
final class X11Bridge {
	private static final int CLIENT_MESSAGE = 33;
	private static final long SUBSTRUCTURE_MASK = (1L << 19) | (1L << 20);
	private static final int NET_WM_STATE_ADD = 1;
	private static final int NET_WM_MOVERESIZE_MOVE = 8;
	private static final int SOURCE_APPLICATION = 1;

	private final Linker linker = Linker.nativeLinker();
	private final SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", Arena.global());
	private final MemorySegment display;
	private final long root;
	private final MethodHandle internAtom;
	private final MethodHandle queryTree;
	private final MethodHandle fetchName;
	private final MethodHandle free;
	private final MethodHandle sendEvent;
	private final MethodHandle ungrabPointer;
	private final MethodHandle flush;

	X11Bridge() throws Throwable {
		SymbolLookup gdk = SymbolLookup.libraryLookup("libgdk-3.so.0", Arena.global());
		MethodHandle gdkDisplay = linker.downcallHandle(gdk.findOrThrow("gdk_x11_get_default_xdisplay"), FunctionDescriptor.of(ADDRESS));
		display = (MemorySegment) gdkDisplay.invokeExact();
		if (display.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("GTK has no X11 display (not running on X11/XWayland)");
		}
		root = (long) x11("XDefaultRootWindow", FunctionDescriptor.of(JAVA_LONG, ADDRESS)).invokeExact(display);
		internAtom = x11("XInternAtom", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
		queryTree = x11("XQueryTree", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		fetchName = x11("XFetchName", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
		free = x11("XFree", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		sendEvent = x11("XSendEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, ADDRESS));
		ungrabPointer = x11("XUngrabPointer", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
		flush = x11("XFlush", FunctionDescriptor.of(JAVA_INT, ADDRESS));
	}

	private MethodHandle x11(String name, FunctionDescriptor descriptor) {
		return linker.downcallHandle(x11.findOrThrow(name), descriptor);
	}

	/** Client window id whose WM_NAME equals {@code title}, or 0. COSMIC reparents clients into frames, so this descends. */
	long findWindow(String title) throws Throwable {
		return findWindow(root, title, 3);
	}

	private long findWindow(long parent, String title, int depth) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment nameOut = arena.allocate(ADDRESS);
			if ((int) fetchName.invokeExact(display, parent, nameOut) != 0) {
				boolean match = title.equals(nameOut.get(ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0));
				int ignored = (int) free.invokeExact(nameOut.get(ADDRESS, 0));
				if (match) {
					return parent;
				}
			}
			if (depth == 0) {
				return 0;
			}
			MemorySegment rootOut = arena.allocate(JAVA_LONG);
			MemorySegment parentOut = arena.allocate(JAVA_LONG);
			MemorySegment childrenOut = arena.allocate(ADDRESS);
			MemorySegment countOut = arena.allocate(JAVA_INT);
			if ((int) queryTree.invokeExact(display, parent, rootOut, parentOut, childrenOut, countOut) == 0) {
				return 0;
			}
			int count = countOut.get(JAVA_INT, 0);
			if (count == 0) {
				return 0;
			}
			MemorySegment children = childrenOut.get(ADDRESS, 0).reinterpret((long) count * Long.BYTES);
			long found = 0;
			for (int i = 0; i < count && found == 0; i++) {
				found = findWindow(children.getAtIndex(JAVA_LONG, i), title, depth - 1);
			}
			int ignored = (int) free.invokeExact(childrenOut.get(ADDRESS, 0));
			return found;
		}
	}

	void keepAbove(long window) throws Throwable {
		send(window, "_NET_WM_STATE", NET_WM_STATE_ADD, atom("_NET_WM_STATE_ABOVE"), 0, SOURCE_APPLICATION, 0);
	}

	/** Hands an in-progress button-1 drag to the window manager, which then moves the window itself. */
	void beginMove(long window, int screenX, int screenY) throws Throwable {
		int ignored = (int) ungrabPointer.invokeExact(display, 0L);
		send(window, "_NET_WM_MOVERESIZE", screenX, screenY, NET_WM_MOVERESIZE_MOVE, 1, SOURCE_APPLICATION);
	}

	private long atom(String name) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			return (long) internAtom.invokeExact(display, arena.allocateFrom(name), 0);
		}
	}

	/** XClientMessageEvent, format 32, laid out for LP64 inside a 192-byte XEvent. */
	private void send(long window, String type, long l0, long l1, long l2, long l3, long l4) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment event = arena.allocate(192, 8);
			event.set(JAVA_INT, 0, CLIENT_MESSAGE);
			event.set(JAVA_INT, 16, 1);
			event.set(ADDRESS, 24, display);
			event.set(JAVA_LONG, 32, window);
			event.set(JAVA_LONG, 40, atom(type));
			event.set(JAVA_INT, 48, 32);
			long[] data = {l0, l1, l2, l3, l4};
			for (int i = 0; i < data.length; i++) {
				event.set(JAVA_LONG, 56 + i * 8L, data[i]);
			}
			int sent = (int) sendEvent.invokeExact(display, root, 0, SUBSTRUCTURE_MASK, event);
			int flushed = (int) flush.invokeExact(display);
			Log.info("x11 " + type + " -> 0x" + Long.toHexString(window) + " sent=" + sent);
		}
	}
}
