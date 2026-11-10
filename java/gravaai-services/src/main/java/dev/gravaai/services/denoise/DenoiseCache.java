package dev.gravaai.services.denoise;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.media.denoise.DenoisedAudio;
import dev.gravaai.media.export.FfmpegCli;

/**
 * The denoised-track cache: renders the {@link DenoisedAudio} copy of a source audio file in the
 * background, once, and tells the editor where each source stands (rendering with a percentage,
 * ready, failed) so the preview, the export and the timeline all read the same state.
 *
 * <p>Renders run one at a time on a single worker: a render already uses several cores inside the
 * network, and two at once would only starve the editor. A source is rendered at most once per
 * file version; asking again while it renders joins the running render.
 *
 * <p>Threading: every method is safe from any thread. Listeners run on the worker thread (progress,
 * completion) or the caller's (a request that changes nothing does not notify); the UI marshals.
 */
public final class DenoiseCache implements AutoCloseable {

	/** Where a source stands. */
	public enum Phase {
		/** Nothing rendered and nothing requested. */
		IDLE,
		/** Being rendered (or waiting for the render in front of it). */
		RENDERING,
		/** The denoised copy is in the cache. */
		READY,
		/** The render failed; the fallback chain is used and {@link Status#error} says why. */
		FAILED
	}

	/**
	 * @param progress 0..1 while rendering, 1 when ready
	 * @param error why the render failed, null otherwise
	 */
	public record Status(Phase phase, double progress, @Nullable String error) {

		public static final Status IDLE = new Status(Phase.IDLE, 0, null);
		public static final Status READY = new Status(Phase.READY, 1, null);
	}

	/** Cache entries not read for this long are removed when the cache is pruned. */
	private static final long MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000;
	/** The cache is trimmed, oldest first, when it grows past this (about 15 h of stereo audio). */
	private static final long MAX_BYTES = 10L * 1024 * 1024 * 1024;

	private static final System.Logger LOG = System.getLogger("gravaai.denoise");

	private static @Nullable DenoiseCache shared;

	private final Path cacheDir;
	private final Supplier<Path> ffmpeg;
	private final ExecutorService worker = Threads.daemon("gravaai-denoise");
	private final Map<Path, CompletableFuture<Path>> renders = new ConcurrentHashMap<>();
	private final Map<Path, Status> statuses = new ConcurrentHashMap<>();
	private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile boolean closed;

	/**
	 * @param cacheDir where the rendered copies live (created on first render)
	 * @param ffmpeg the ffmpeg binary that decodes sources, resolved per render
	 */
	public DenoiseCache(Path cacheDir, Supplier<Path> ffmpeg) {
		this.cacheDir = cacheDir;
		this.ffmpeg = ffmpeg;
	}

	/**
	 * The process-wide cache under the user's profile ({@code <userData>/cache/denoise}), created
	 * on first use. The editor, the exporters and the queue share it so a track is rendered once
	 * whoever asks first.
	 */
	public static synchronized DenoiseCache shared() {
		DenoiseCache current = shared;
		if (current == null) {
			current = new DenoiseCache(
					UserData.resolve().dir().resolve("cache").resolve("denoise"), FfmpegCli::binary);
			shared = current;
			DenoiseCache created = current;
			Threads.startVirtual("gravaai-denoise-prune", created::prune);
		}
		return current;
	}

	/** True when this build can denoise at all (the model is in the payload). */
	public boolean isAvailable() {
		return DenoisedAudio.isAvailable();
	}

	/** The rendered copy of {@code source} when it is in the cache; never starts a render. */
	public Optional<Path> ready(Path source) {
		Optional<Path> cached = DenoisedAudio.cached(cacheDir, source);
		cached.ifPresent(DenoiseCache::touch);
		return cached;
	}

	/** Where {@code source} stands. */
	public Status status(Path source) {
		Path key = key(source);
		Status status = statuses.get(key);
		if (status != null && status.phase() != Phase.READY) {
			return status;
		}
		return DenoisedAudio.cached(cacheDir, source).isPresent() ? Status.READY : Status.IDLE;
	}

	/**
	 * Renders {@code source} unless it is already rendered or rendering, and completes with the
	 * rendered file. A failed render completes exceptionally and can be requested again.
	 */
	public CompletableFuture<Path> ensure(Path source) {
		Optional<Path> cached = ready(source);
		if (cached.isPresent()) {
			return CompletableFuture.completedFuture(cached.get());
		}
		if (closed || !isAvailable()) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					"the speech denoiser is not available in this build"));
		}
		Path key = key(source);
		CompletableFuture<Path> pending = new CompletableFuture<>();
		CompletableFuture<Path> running = renders.putIfAbsent(key, pending);
		if (running != null) {
			return running;
		}
		statuses.put(key, new Status(Phase.RENDERING, 0, null));
		notifyChanged();
		try {
			worker.execute(() -> render(key, pending));
		} catch (RejectedExecutionException stopped) {
			renders.remove(key, pending);
			statuses.remove(key);
			pending.completeExceptionally(stopped);
		}
		return pending;
	}

	/**
	 * Renders {@code source} if needed and waits for it. For callers that are already on a
	 * background thread (the batch export queue, the agent's export).
	 *
	 * @return the rendered copy, or null when it cannot be produced (the fallback chain is used)
	 */
	public @Nullable Path ensureBlocking(Path source) {
		try {
			return ensure(source).join();
		} catch (CompletionException | CancellationException error) {
			LOG.log(System.Logger.Level.WARNING,
					"no denoised copy of " + source.getFileName() + ": " + error.getMessage());
			return null;
		}
	}

	/** Subscribes to status changes (progress included). */
	public AutoCloseable addListener(Runnable listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	private void render(Path source, CompletableFuture<Path> pending) {
		int[] lastPercent = {-1};
		try {
			Path rendered = DenoisedAudio.render(ffmpeg.get(), source, cacheDir, fraction -> {
				int percent = (int) Math.floor(fraction * 100);
				if (percent != lastPercent[0]) {
					lastPercent[0] = percent;
					statuses.put(source, new Status(Phase.RENDERING, fraction, null));
					notifyChanged();
				}
			}, () -> closed);
			statuses.put(source, Status.READY);
			renders.remove(source, pending);
			pending.complete(rendered);
		} catch (IOException | RuntimeException error) {
			String message = error.getMessage() == null ? error.toString() : error.getMessage();
			LOG.log(System.Logger.Level.WARNING,
					"could not denoise " + source.getFileName() + ": " + message);
			statuses.put(source, new Status(Phase.FAILED, 0, message));
			renders.remove(source, pending);
			pending.completeExceptionally(error);
		}
		notifyChanged();
	}

	/**
	 * Removes copies nobody has read for a month and, past the size cap, the least recently used
	 * ones. A removed copy only costs a new render the next time its track is cleaned.
	 */
	public void prune() {
		if (!Files.isDirectory(cacheDir)) {
			return;
		}
		record Entry(Path file, long modified, long size) {}
		List<Entry> entries = new ArrayList<>();
		try (Stream<Path> files = Files.list(cacheDir)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				String name = file.getFileName().toString();
				if (name.endsWith(".part") && !renders.isEmpty()) {
					continue;
				}
				if (!name.endsWith(".wav") && !name.endsWith(".part")) {
					continue;
				}
				entries.add(new Entry(file, Files.getLastModifiedTime(file).toMillis(),
						Files.size(file)));
			}
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.DEBUG, "denoise cache prune: " + error);
			return;
		}
		entries.sort(Comparator.comparingLong(Entry::modified).reversed());
		long now = System.currentTimeMillis();
		long kept = 0;
		for (Entry entry : entries) {
			boolean stale = now - entry.modified() > MAX_AGE_MILLIS
					|| entry.file().getFileName().toString().endsWith(".part");
			if (stale || kept + entry.size() > MAX_BYTES) {
				try {
					Files.deleteIfExists(entry.file());
				} catch (IOException error) {
					LOG.log(System.Logger.Level.DEBUG, "could not prune " + entry.file());
				}
			} else {
				kept += entry.size();
			}
		}
	}

	/** Marks a copy as used now, so pruning keeps what the user is working with. */
	private static void touch(Path file) {
		try {
			long now = System.currentTimeMillis();
			if (now - Files.getLastModifiedTime(file).toMillis() < 24L * 60 * 60 * 1000) {
				return;
			}
			Files.setLastModifiedTime(file,
					java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
		} catch (IOException ignored) {
			// A read-only cache still serves the file.
		}
	}

	private static Path key(Path source) {
		return source.toAbsolutePath().normalize();
	}

	private void notifyChanged() {
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException ignored) {
				// A broken listener must never break a render.
			}
		}
	}

	/** Stops the running render and refuses new ones. */
	@Override
	public void close() {
		closed = true;
		worker.shutdownNow();
	}
}
