package dev.gravaai.ui.editor.timeline;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.gravaai.media.compose.CompositorFrameAtTime;
import dev.gravaai.media.compose.FrameAtTime;
import dev.gravaai.media.compose.RgbaFrame;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * Filmstrip adapter over recording's public media API: one {@link CompositorFrameAtTime} renders
 * composited frames on its own offscreen GL thread and this provider runs those synchronous calls on
 * a single background thread (the API allows one caller at a time), converts each {@link RgbaFrame}
 * to a JavaFX thumbnail, caches it per timeline time and calls {@link #setOnFrameReady} on the FX
 * thread. The renderer is disposed in {@link #close()}.
 *
 * <p>The app builds the {@link FrameAtTime.FrameInputFactory} from the same resolved project state
 * the preview/export use (source/webcam frames, layout/zoom/style/cursor/overlays/captions/fades),
 * so filmstrip, preview and export cannot disagree.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class CompositorFilmstripProvider implements FilmstripProvider, AutoCloseable {

	private record Key(String clipId, double timelineTimeMs, int width, int height) {
	}

	private final FrameAtTime renderer;
	private final ExecutorService executor;
	private final Map<Key, Image> cache = new ConcurrentHashMap<>();
	private final Set<Key> pending = ConcurrentHashMap.newKeySet();
	private final Set<Key> failed = ConcurrentHashMap.newKeySet();
	private volatile Runnable onFrameReady = () -> { };
	private volatile boolean closed;
	private volatile @Nullable String lastFailure;

	/** Creates one compositor renderer at the requested thumbnail size. */
	@Nullable public CompositorFilmstripProvider(int width, int height,
			FrameAtTime.FrameInputFactory inputFactory) {
		this(new CompositorFrameAtTime(width, height, inputFactory));
	}

	/** Test seam: supply an already-built {@link FrameAtTime} (e.g. a deterministic fake). */
	CompositorFilmstripProvider(FrameAtTime renderer) {
		this.renderer = renderer;
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "gravaai-filmstrip-renderer");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public @Nullable Image frameAt(String clipId, double timelineTimeMs, int thumbWidth, int thumbHeight) {
		if (closed) {
			return null;
		}
		return cache.get(new Key(clipId, timelineTimeMs, thumbWidth, thumbHeight));
	}

	@Override
	public void requestFrame(String clipId, double timelineTimeMs, int thumbWidth,
			int thumbHeight) {
		if (closed) {
			return;
		}
		Key key = new Key(clipId, timelineTimeMs, thumbWidth, thumbHeight);
		if (cache.containsKey(key) || failed.contains(key) || !pending.add(key)) {
			return;
		}
		executor.execute(() -> {
			try {
				RgbaFrame frame = renderer.frameAtTime(timelineTimeMs / 1000.0);
				cache.put(key, toFxImage(frame));
			} catch (RuntimeException error) {
				failed.add(key);
				lastFailure = error.getMessage() == null ? error.toString() : error.getMessage();
			} finally {
				pending.remove(key);
				Runnable listener = onFrameReady;
				Platform.runLater(listener);
			}
		});
	}

	@Override
	public void setOnFrameReady(Runnable listener) {
		this.onFrameReady = listener == null ? () -> { } : listener;
	}

	/** Number of cached thumbnails (tests). */
	public int cachedFrameCount() {
		return cache.size();
	}

	/** Last rendering failure, or {@code null} (GL unavailable, bad input factory, ...). */
	public @Nullable String lastFailure() {
		return lastFailure;
	}

	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		executor.shutdownNow();
		cache.clear();
		pending.clear();
		failed.clear();
		renderer.close();
	}

	private static Image toFxImage(RgbaFrame frame) {
		int width = frame.width();
		int height = frame.height();
		int[] argb = new int[width * height];
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		for (int index = 0; index < argb.length; index++) {
			int offset = index * 4;
			int red = Byte.toUnsignedInt(pixels.get(offset));
			int green = Byte.toUnsignedInt(pixels.get(offset + 1));
			int blue = Byte.toUnsignedInt(pixels.get(offset + 2));
			int alpha = Byte.toUnsignedInt(pixels.get(offset + 3));
			argb[index] = (alpha << 24) | (red << 16) | (green << 8) | blue;
		}
		WritableImage image = new WritableImage(width, height);
		image.getPixelWriter().setPixels(0, 0, width, height,
				PixelFormat.getIntArgbInstance(), argb, 0, width);
		return image;
	}
}
