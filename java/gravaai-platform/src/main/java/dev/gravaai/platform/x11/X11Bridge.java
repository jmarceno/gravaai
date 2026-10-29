package dev.gravaai.platform.x11;

import org.jspecify.annotations.Nullable;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * FFM bridge to the Xlib connection GTK already holds for JavaFX (plan §4.4, §5.4, §5.5).
 *
 * <p>JavaFX runs on XWayland under COSMIC (plan §3). COSMIC's X11 window manager ignores
 * keep-above and client position requests and reverts client moves, so these go through EWMH client
 * messages as GTK itself does. The class is deliberately JavaFX-free (plan §4.2: platform must not
 * depend on the UI toolkit): callers pass X11 window ids from {@link #findWindow(String)}.
 *
 * <p><b>Threading:</b> every call must run on the JavaFX application thread, which is GTK's thread
 * and therefore the only thread that may use this {@code Display*}. The click-through input shape
 * is applied with {@code XShapeCombineRectangles} from {@code libXext.so.6} using
 * {@code ShapeInput} and zero rectangles.
 *
 * <p>The display comes from {@code gdk_x11_get_default_xdisplay()} when GTK is initialised (the
 * normal case inside the app). Outside the app — for tests and the platform smoke — the bridge
 * falls back to {@code XOpenDisplay(NULL)}, so the same code can find windows of a running app.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class X11Bridge implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.platform.x11");

	static final int CLIENT_MESSAGE = 33;
	static final long SUBSTRUCTURE_MASK = (1L << 19) | (1L << 20);
	static final int NET_WM_STATE_ADD = 1;
	static final int NET_WM_MOVERESIZE_MOVE = 8;
	static final int SOURCE_APPLICATION = 1;

	/** {@code ShapeInput} from X11/extensions/shape.h. */
	static final int SHAPE_INPUT = 2;
	/** {@code ShapeSet} from X11/extensions/shape.h. */
	static final int SHAPE_SET = 0;
	/** {@code Unsorted} from X11/extensions/shape.h. */
	static final int SHAPE_UNSORTED = 0;

	static final int EVENT_SIZE = 192;
	static final int EVENT_TYPE_OFFSET = 0;
	static final int EVENT_FORMAT_OFFSET = 16;
	static final int EVENT_DISPLAY_OFFSET = 24;
	static final int EVENT_WINDOW_OFFSET = 32;
	static final int EVENT_MESSAGE_TYPE_OFFSET = 40;
	static final int EVENT_LONG_FORMAT_OFFSET = 48;
	static final int EVENT_DATA_OFFSET = 56;
	static final int EVENT_DATA_LONG_COUNT = 5;

	private static final int SEARCH_DEPTH = 3;

	private final MemorySegment display;
	private final boolean ownsDisplay;
	private final long root;
	private final @Nullable MethodHandle internAtom;
	private final @Nullable MethodHandle queryTree;
	private final @Nullable MethodHandle fetchName;
	private final @Nullable MethodHandle free;
	private final @Nullable MethodHandle sendEvent;
	private final @Nullable MethodHandle ungrabPointer;
	private final @Nullable MethodHandle flush;
	private final @Nullable MethodHandle closeDisplay;
	private final @Nullable MethodHandle shapeCombineRectangles;

	private X11Bridge(Linker linker, MemorySegment display, boolean ownsDisplay) throws Throwable {
		this.display = display;
		this.ownsDisplay = ownsDisplay;
		SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", Arena.global());
		this.root = (long) downcall(linker, x11, "XDefaultRootWindow", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
				.invokeExact(display);
		this.internAtom = downcall(linker, x11, "XInternAtom",
				FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
		this.queryTree = downcall(linker, x11, "XQueryTree",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		this.fetchName = downcall(linker, x11, "XFetchName", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
		this.free = downcall(linker, x11, "XFree", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		this.sendEvent = downcall(linker, x11, "XSendEvent",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, ADDRESS));
		this.ungrabPointer = downcall(linker, x11, "XUngrabPointer", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
		this.flush = downcall(linker, x11, "XFlush", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		this.closeDisplay = downcall(linker, x11, "XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		SymbolLookup xext = SymbolLookup.libraryLookup("libXext.so.6", Arena.global());
		this.shapeCombineRectangles = downcall(linker, xext, "XShapeCombineRectangles",
				FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT,
						JAVA_INT, JAVA_INT));
	}

	/**
	 * Opens the bridge on the GTK display, falling back to a private {@code XOpenDisplay}.
	 *
	 * @throws IllegalStateException when no X11 display is reachable
	 */
	public static X11Bridge open() {
		Linker linker = Linker.nativeLinker();
		MemorySegment display = gdkDisplay(linker);
		boolean ownsDisplay = false;
		if (display == null || display.equals(MemorySegment.NULL)) {
			display = openDisplay(linker);
			ownsDisplay = true;
		}
		if (display == null || display.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("cannot open an X11 display (DISPLAY=" + System.getenv("DISPLAY") + ")");
		}
		try {
			return new X11Bridge(linker, display, ownsDisplay);
		} catch (Throwable error) {
			if (ownsDisplay) {
				closeQuietly(linker, display);
			}
			throw new IllegalStateException("could not initialise the X11 bridge: " + error, error);
		}
	}

	/** Opens the bridge when an X11 display is available. */
	public static Optional<X11Bridge> tryOpen() {
		try {
			return Optional.of(open());
		} catch (RuntimeException | LinkageError error) {
			LOG.log(System.Logger.Level.DEBUG, "no X11 bridge: " + error);
			return Optional.empty();
		}
	}

	public long rootWindow() {
		return root;
	}

	/** Client window id whose {@code WM_NAME} equals {@code title}, or 0 when not found. */
	public long findWindow(String title) {
		if (title == null || title.isEmpty()) {
			throw new IllegalArgumentException("title must not be empty");
		}
		try {
			return findWindow(root, title, SEARCH_DEPTH);
		} catch (Throwable error) {
			throw new IllegalStateException("could not search the X11 window tree for '" + title + "': " + error, error);
		}
	}

	private long findWindow(long parent, String title, int depth) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment nameOut = arena.allocate(ADDRESS);
			if ((int) fetchName.invokeExact(display, parent, nameOut) != 0) {
				MemorySegment name = nameOut.get(ADDRESS, 0);
				boolean match = title.equals(name.reinterpret(Long.MAX_VALUE).getString(0));
				int ignored = (int) free.invokeExact(name);
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
			for (int index = 0; index < count && found == 0; index++) {
				found = findWindow(children.getAtIndex(JAVA_LONG, index), title, depth - 1);
			}
			int ignored = (int) free.invokeExact(childrenOut.get(ADDRESS, 0));
			return found;
		}
	}

	/** Adds {@code _NET_WM_STATE_ABOVE} through EWMH (COSMIC ignores the Xlib call). */
	public void keepAbove(long window) {
		send(window, "_NET_WM_STATE", NET_WM_STATE_ADD, atom("_NET_WM_STATE_ABOVE"), 0, SOURCE_APPLICATION, 0);
	}

	/** Hands an in-progress button-1 drag to the window manager, which then moves the window itself. */
	public void beginMove(long window, int screenX, int screenY) {
		try {
			int ignored = (int) ungrabPointer.invokeExact(display, 0L);
		} catch (Throwable error) {
			throw new IllegalStateException("XUngrabPointer failed: " + error, error);
		}
		send(window, "_NET_WM_MOVERESIZE", screenX, screenY, NET_WM_MOVERESIZE_MOVE, 1, SOURCE_APPLICATION);
	}

	/**
	 * Makes the window click-through by replacing its input shape with zero rectangles
	 * ({@code XShapeCombineRectangles(display, window, ShapeInput, 0, 0, NULL, 0, Unsorted, ShapeSet)}).
	 *
	 * <p>Used by the area recording border (plan §5.4). Call on the FX thread after the window is
	 * shown; the shape is a server-side property and survives until changed again.
	 */
	public void setInputShapeEmpty(long window) {
		try {
			shapeCombineRectangles.invokeExact(display, window, SHAPE_INPUT, 0, 0, MemorySegment.NULL, 0,
					SHAPE_UNSORTED, SHAPE_SET);
		} catch (Throwable error) {
			throw new IllegalStateException("XShapeCombineRectangles failed: " + error, error);
		}
	}

	private long atom(String name) {
		try (Arena arena = Arena.ofConfined()) {
			return (long) internAtom.invokeExact(display, arena.allocateFrom(name), 0);
		} catch (Throwable error) {
			throw new IllegalStateException("XInternAtom failed for " + name + ": " + error, error);
		}
	}

	private void send(long window, String type, long l0, long l1, long l2, long l3, long l4) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment event = buildClientMessage(arena, display, window, atom(type),
					new long[] {l0, l1, l2, l3, l4});
			int sent = (int) sendEvent.invokeExact(display, root, 0, SUBSTRUCTURE_MASK, event);
			int flushed = (int) flush.invokeExact(display);
			LOG.log(System.Logger.Level.DEBUG, "x11 " + type + " -> 0x" + Long.toHexString(window)
					+ " sent=" + sent + " flushed=" + flushed);
		} catch (Throwable error) {
			throw new IllegalStateException("XSendEvent failed for " + type + ": " + error, error);
		}
	}

	/** Lays out an {@code XClientMessageEvent} (format 32) inside the 192-byte {@code XEvent} union. */
	static MemorySegment buildClientMessage(Arena arena, MemorySegment display, long window, long messageType,
			long[] data) {
		MemorySegment event = arena.allocate(EVENT_SIZE, 8);
		event.set(JAVA_INT, EVENT_TYPE_OFFSET, CLIENT_MESSAGE);
		event.set(JAVA_INT, EVENT_FORMAT_OFFSET, 1);
		event.set(ADDRESS, EVENT_DISPLAY_OFFSET, display);
		event.set(JAVA_LONG, EVENT_WINDOW_OFFSET, window);
		event.set(JAVA_LONG, EVENT_MESSAGE_TYPE_OFFSET, messageType);
		event.set(JAVA_INT, EVENT_LONG_FORMAT_OFFSET, 32);
		for (int index = 0; index < EVENT_DATA_LONG_COUNT; index++) {
			long value = index < data.length ? data[index] : 0;
			event.set(JAVA_LONG, EVENT_DATA_OFFSET + index * 8L, value);
		}
		return event;
	}

	@Override
	public void close() {
		if (ownsDisplay) {
			try {
				int ignored = (int) closeDisplay.invokeExact(display);
			} catch (Throwable error) {
				LOG.log(System.Logger.Level.DEBUG, "XCloseDisplay failed: " + error);
			}
		}
	}

	private static @Nullable MethodHandle downcall(Linker linker, SymbolLookup lookup, String name,
			FunctionDescriptor descriptor) {
		return linker.downcallHandle(lookup.findOrThrow(name), descriptor);
	}

	private static @Nullable MemorySegment gdkDisplay(Linker linker) {
		try {
			SymbolLookup gdk = SymbolLookup.libraryLookup("libgdk-3.so.0", Arena.global());
			MethodHandle defaultDisplay = linker.downcallHandle(gdk.findOrThrow("gdk_display_get_default"),
					FunctionDescriptor.of(ADDRESS));
			// gdk_x11_get_default_xdisplay() calls g_error() (abort) when GDK has no display, so
			// probe the default display first: only the app's GTK-initialised process has one.
			MemorySegment display = (MemorySegment) defaultDisplay.invokeExact();
			if (display == null || display.equals(MemorySegment.NULL)) {
				return null;
			}
			MethodHandle handle = linker.downcallHandle(gdk.findOrThrow("gdk_x11_get_default_xdisplay"),
					FunctionDescriptor.of(ADDRESS));
			return (MemorySegment) handle.invokeExact();
		} catch (Throwable error) {
			LOG.log(System.Logger.Level.DEBUG, "no GDK X11 display: " + error);
			return null;
		}
	}

	private static @Nullable MemorySegment openDisplay(Linker linker) {
		try {
			SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", Arena.global());
			MethodHandle handle = linker.downcallHandle(x11.findOrThrow("XOpenDisplay"), FunctionDescriptor.of(ADDRESS, ADDRESS));
			return (MemorySegment) handle.invokeExact(MemorySegment.NULL);
		} catch (Throwable error) {
			LOG.log(System.Logger.Level.DEBUG, "XOpenDisplay failed: " + error);
			return null;
		}
	}

	private static void closeQuietly(Linker linker, MemorySegment display) {
		try {
			SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", Arena.global());
			MethodHandle handle = linker.downcallHandle(x11.findOrThrow("XCloseDisplay"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
			int ignored = (int) handle.invokeExact(display);
		} catch (Throwable error) {
			LOG.log(System.Logger.Level.DEBUG, "XCloseDisplay failed: " + error);
		}
	}
}
