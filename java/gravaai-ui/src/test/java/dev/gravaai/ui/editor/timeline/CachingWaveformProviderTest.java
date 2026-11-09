package dev.gravaai.ui.editor.timeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link CachingWaveformProvider}: background loading, resampling and failure handling. */
class CachingWaveformProviderTest {

	@TempDir
	Path temp;

	/** An executor that runs the task on the calling thread (deterministic tests). */
	private static final Executor DIRECT = Runnable::run;

	/** The loader the tests inject: peaks-per-second of 200, like the media layer. */
	private static final int PEAKS_PER_SECOND = 200;

	@Test
	@DisplayName("first paint gets nothing, the load lands in the background, the next paint draws")
	void loadsInBackground() {
		AtomicInteger loads = new AtomicInteger();
		CachingWaveformProvider provider = new CachingWaveformProvider(
				path -> temp.resolve("cache.peaks"),
				(audio, cache) -> {
					loads.incrementAndGet();
					assertEquals(temp.resolve("cache.peaks"), cache, "the cache path policy is used");
					return new float[] {0.5f};
				},
				PEAKS_PER_SECOND, DIRECT);
		assertNull(provider.peaks("/audio/a.wav", 0, 1000, 10), "not ready on the first paint");
		float[] peaks = provider.peaks("/audio/a.wav", 0, 1000, 10);
		assertNotNull(peaks);
		assertEquals(10, peaks.length);
		assertEquals(0.5f, peaks[0], "every bar reads the loaded maximum");
		assertEquals(1, loads.get(), "the file loads once");
		provider.close();
	}

	@Test
	@DisplayName("resampling takes the maximum of the buckets each bar covers")
	void resamplesByMaximum() {
		// 2 s of audio: buckets 0..19 (0..100 ms) loud, the rest silent.
		float[] full = new float[2 * PEAKS_PER_SECOND];
		for (int bucket = 0; bucket < 20; bucket++) {
			full[bucket] = 1;
		}
		CachingWaveformProvider provider = new CachingWaveformProvider(
				path -> temp.resolve("cache.peaks"), (audio, cache) -> full,
				PEAKS_PER_SECOND, DIRECT);
		provider.peaks("/audio/a.wav", 0, 1, 1); // warm the cache
		assertArrayEquals(new float[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
				provider.peaks("/audio/a.wav", 0, 1000, 10), "loud only in the first 100 ms");
		assertArrayEquals(new float[] {0, 0},
				provider.peaks("/audio/a.wav", 5000, 6000, 2), "past the audio end is silence");
		provider.close();
	}

	@Test
	@DisplayName("a failed load never retries and never blocks later paints")
	void failedLoadsAreNotRetried() {
		AtomicInteger attempts = new AtomicInteger();
		CachingWaveformProvider provider = new CachingWaveformProvider(
				path -> temp.resolve("cache.peaks"),
				(audio, cache) -> {
					attempts.incrementAndGet();
					throw new IllegalStateException("no audio stream");
				},
				PEAKS_PER_SECOND, DIRECT);
		for (int paint = 0; paint < 5; paint++) {
			assertNull(provider.peaks("/audio/bad.wav", 0, 1000, 10));
		}
		assertEquals(1, attempts.get(), "one attempt, then the failure is remembered");
		provider.close();
	}

	@Test
	@DisplayName("the ready listener fires on the loader thread once data lands")
	void notifiesOnPeaksReady() throws Exception {
		CompletableFuture<float[]> pending = new CompletableFuture<>();
		List<Runnable> queued = new ArrayList<>();
		CachingWaveformProvider provider = new CachingWaveformProvider(
				path -> temp.resolve("cache.peaks"), (audio, cache) -> pending.join(),
				PEAKS_PER_SECOND, queued::add);
		CountDownLatch repaint = new CountDownLatch(1);
		provider.setOnPeaksReady(repaint::countDown);
		assertNull(provider.peaks("/audio/a.wav", 0, 1000, 4), "queued, not loaded yet");
		assertEquals(1, queued.size());
		pending.complete(new float[PEAKS_PER_SECOND]); // the decode finishes first...
		queued.get(0).run(); // ...then the loader thread hands it over and notifies the view
		assertTrue(repaint.await(1, TimeUnit.SECONDS), "the view was told to repaint");
		assertNotNull(provider.peaks("/audio/a.wav", 0, 1000, 4));
		provider.close();
	}

	@Test
	@DisplayName("a closed provider paints nothing")
	void closedProviderPaintsNothing() {
		CachingWaveformProvider provider = new CachingWaveformProvider(
				path -> temp.resolve("cache.peaks"), (audio, cache) -> new float[] {1},
				PEAKS_PER_SECOND, DIRECT);
		provider.peaks("/audio/a.wav", 0, 1000, 10);
		provider.close();
		assertNull(provider.peaks("/audio/a.wav", 0, 1000, 10), "closed means no peaks");
	}

	@Test
	@DisplayName("degenerate ranges request nothing")
	void degenerateRangesReturnNothing() {
		CachingWaveformProvider provider = new CachingWaveformProvider(
				path -> temp.resolve("cache.peaks"), (audio, cache) -> new float[] {1},
				PEAKS_PER_SECOND, DIRECT);
		assertNull(provider.peaks("/audio/a.wav", 1000, 1000, 10), "empty range");
		assertNull(provider.peaks("/audio/a.wav", 2000, 1000, 10), "inverted range");
		assertNull(provider.peaks("/audio/a.wav", 0, 1000, 0), "zero bars");
		assertNull(provider.peaks("", 0, 1000, 10), "no file");
		provider.close();
	}
}
