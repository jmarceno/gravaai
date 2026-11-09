package dev.gravaai.ui.editor.timeline;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * {@link WaveformProvider} that keeps the full-resolution peaks of every requested audio file in
 * memory and resamples the visible range per paint (plan: timeline waveforms).
 *
 * <p>The first request for a file schedules a background load through the injected
 * {@link PeaksLoader} (the media layer's decode + persistent cache) and paints nothing until the
 * load lands; the ready listener makes the view repaint. Later paints — scrolling, zooming,
 * playhead moves — resample straight from memory, so an hour-long recording never re-decodes while
 * the user scrubs. A file whose load failed is not retried for the rest of the session: one
 * unreadable file must not turn every repaint into a decode attempt.
 *
 * <p>{@link #peaks} is called on the FX thread and never blocks; the map lookups and the
 * resampling are the only work it does. {@link #close} cancels pending loads and drops the memory.
 */
public final class CachingWaveformProvider implements WaveformProvider, AutoCloseable {

	/**
	 * The heavy work for one audio file: full-resolution peaks (decode, cache read/write). Runs on
	 * the provider's background thread; throwing means "no waveform for this file".
	 */
	@FunctionalInterface
	public interface PeaksLoader {
		float[] load(Path audioFile, Path cacheFile) throws Exception;
	}

	private static final System.Logger LOG = System.getLogger("gravaai.timeline");

	private final PeaksLoader loader;
	private final java.util.function.Function<String, Path> cachePaths;
	/** Peaks per second the loader's arrays are sampled at (the media layer's bucket rate). */
	private final int loaderPeaksPerSecond;
	private final Executor executor;
	private final Map<String, CompletableFuture<float[]>> loaded = new ConcurrentHashMap<>();
	private final Set<String> failed = ConcurrentHashMap.newKeySet();
	private volatile @Nullable Runnable onPeaksReady;
	private volatile boolean closed;

	/**
	 * @param cachePaths where the persistent cache for an audio path lives (beside recordings,
	 *     user cache for imports)
	 * @param loader the media-layer peaks loader (decode + persistent cache)
	 * @param loaderPeaksPerSecond the resolution the loader returns peaks at
	 * @param executor serializes the loads; owned externally, never shut down here
	 */
	public CachingWaveformProvider(java.util.function.Function<String, Path> cachePaths,
			PeaksLoader loader, int loaderPeaksPerSecond, Executor executor) {
		this.cachePaths = Objects.requireNonNull(cachePaths, "cachePaths");
		this.loader = Objects.requireNonNull(loader, "loader");
		this.loaderPeaksPerSecond = loaderPeaksPerSecond > 0 ? loaderPeaksPerSecond : 200;
		this.executor = Objects.requireNonNull(executor, "executor");
	}

	@Override
	public void setOnPeaksReady(@Nullable Runnable listener) {
		onPeaksReady = listener;
	}

	@Override
	public float @Nullable [] peaks(String audioPath, double sourceStartMs, double sourceEndMs,
			int peakCount) {
		if (closed || audioPath == null || audioPath.isBlank() || peakCount <= 0
				|| !(sourceEndMs > sourceStartMs)) {
			return null;
		}
		Future<float[]> future = loaded.get(audioPath);
		if (future == null) {
			if (failed.contains(audioPath)) {
				return null;
			}
			scheduleLoad(audioPath);
			return null;
		}
		if (!future.isDone()) {
			return null;
		}
		try {
			return resample(future.get(), sourceStartMs, sourceEndMs, peakCount);
		} catch (Exception error) {
			return null;
		}
	}

	/** Starts one background load for {@code audioPath} (deduplicated by the map). */
	private void scheduleLoad(String audioPath) {
		CompletableFuture<float[]> pending = new CompletableFuture<>();
		CompletableFuture<float[]> existing = loaded.putIfAbsent(audioPath, pending);
		if (existing != null) {
			return;
		}
		try {
			executor.execute(() -> {
				try {
					float[] peaks = loader.load(Path.of(audioPath), cachePaths.apply(audioPath));
					pending.complete(peaks != null ? peaks : new float[0]);
				} catch (Throwable error) {
					LOG.log(System.Logger.Level.WARNING,
							"no waveform for " + audioPath + ": " + error);
					failed.add(audioPath);
					loaded.remove(audioPath, pending);
					pending.cancel(false);
				} finally {
					Runnable ready = onPeaksReady;
					if (ready != null) {
						try {
							ready.run();
						} catch (RuntimeException ignored) {
							// A repaint callback must never break the loader thread.
						}
					}
				}
			});
		} catch (RuntimeException rejected) {
			// The executor is shut down (session closing): peaks stay absent.
			loaded.remove(audioPath, pending);
		}
	}

	/**
	 * Evenly samples {@code peakCount} bars over {@code [sourceStartMs, sourceEndMs)}; each bar
	 * takes the maximum of the buckets it covers, and time outside the decoded audio is silence.
	 */
	float @Nullable [] resample(float @Nullable [] peaks, double sourceStartMs, double sourceEndMs,
			int peakCount) {
		if (peaks == null || peaks.length == 0 || peakCount <= 0) {
			return new float[Math.max(0, peakCount)];
		}
		double bucketMs = 1000.0 / loaderPeaksPerSecond;
		double barSpanMs = (sourceEndMs - sourceStartMs) / peakCount;
		float[] bars = new float[peakCount];
		for (int bar = 0; bar < peakCount; bar++) {
			double barStartMs = sourceStartMs + bar * barSpanMs;
			double barEndMs = barStartMs + barSpanMs;
			int firstBucket = Math.max(0, (int) Math.floor(barStartMs / bucketMs));
			int lastBucket = Math.min(peaks.length - 1, (int) Math.ceil(barEndMs / bucketMs) - 1);
			float maximum = 0;
			for (int bucket = firstBucket; bucket <= lastBucket; bucket++) {
				maximum = Math.max(maximum, peaks[bucket]);
			}
			bars[bar] = maximum;
		}
		return bars;
	}

	@Override
	public void close() {
		closed = true;
		for (CompletableFuture<float[]> pending : loaded.values()) {
			pending.cancel(false);
		}
		loaded.clear();
		failed.clear();
	}
}
