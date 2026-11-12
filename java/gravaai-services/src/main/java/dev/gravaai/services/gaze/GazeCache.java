package dev.gravaai.services.gaze;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
import java.util.stream.Stream;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.media.gaze.GazeAnalyzer;
import dev.gravaai.media.gaze.GazeTrack;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;

/**
 * The eye-contact analysis cache: runs {@link GazeAnalyzer} over a webcam recording in the
 * background, once per file version, keeps the result on disk ({@code <userData>/cache/gaze}) and
 * the recently used tracks in memory, and reports where each recording stands so the inspector,
 * the timeline strip, the preview and the export all read the same state.
 *
 * <p>One analysis at a time on a single worker (the face mesh holds the GPU). Every method is safe
 * from any thread; listeners run on the worker (progress, completion), the UI marshals.</p>
 */
public final class GazeCache implements AutoCloseable {

	/** Where a recording stands. */
	public enum Phase {
		IDLE,
		ANALYSING,
		READY,
		FAILED
	}

	/**
	 * @param progress 0..1 while analysing, 1 when ready
	 * @param error    why the analysis failed; empty otherwise
	 */
	public record Status(Phase phase, double progress, Optional<String> error) {

		public static final Status IDLE = new Status(Phase.IDLE, 0, Optional.empty());
		public static final Status READY = new Status(Phase.READY, 1, Optional.empty());
	}

	/** Bumped when the analysis or its file format changes: old entries are re-analysed. */
	static final int ENGINE_VERSION = 1;
	private static final long MAX_AGE_MILLIS = 60L * 24 * 60 * 60 * 1000;
	private static final int MEMORY_TRACKS = 3;
	private static final System.Logger LOG = System.getLogger("gravaai.gaze");

	private static Optional<GazeCache> shared = Optional.empty();

	private final Path cacheDir;
	private final ExecutorService worker = Threads.daemon("gravaai-gaze");
	private final Map<Path, CompletableFuture<GazeTrack>> running = new ConcurrentHashMap<>();
	private final Map<Path, Status> statuses = new ConcurrentHashMap<>();
	private final Map<Path, GazeTrack> loaded = new LinkedHashMap<>(4, 0.75f, true);
	private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile boolean closed;

	public GazeCache(Path cacheDir) {
		this.cacheDir = cacheDir;
	}

	/** The process-wide cache under the user's profile, created on first use. */
	public static synchronized GazeCache shared() {
		if (shared.isEmpty()) {
			GazeCache created = new GazeCache(
					UserData.resolve().dir().resolve("cache").resolve("gaze"));
			shared = Optional.of(created);
			Threads.startVirtual("gravaai-gaze-prune", created::prune);
		}
		return shared.get();
	}

	/** True when this build ships the eye-contact models. */
	public boolean isAvailable() {
		try {
			OnnxLibs.resolveGazeModelFile("face_landmarks.onnx");
			OnnxLibs.resolveGazeModelFile("glasses_frames.onnx");
			OnnxLibs.resolveGazeModelFile("eye_warp_L.onnx");
			OnnxLibs.resolveGazeModelFile("eye_warp_R.onnx");
			return true;
		} catch (IllegalStateException missing) {
			return false;
		}
	}

	/** The analysis of {@code webcam} when it is done (memory or disk); never starts one. */
	public Optional<GazeTrack> ready(Path webcam) {
		Path key = key(webcam);
		synchronized (loaded) {
			GazeTrack hit = loaded.get(key);
			if (hit != null) {
				return Optional.of(hit);
			}
		}
		Optional<Path> file = cacheFile(key).filter(Files::isRegularFile);
		if (file.isEmpty()) {
			return Optional.empty();
		}
		try {
			GazeTrack track = GazeTrack.read(file.get());
			remember(key, track);
			return Optional.of(track);
		} catch (IOException | RuntimeException corrupt) {
			LOG.log(System.Logger.Level.WARNING, "dropping unreadable gaze track "
					+ file.get().getFileName() + ": " + corrupt);
			try {
				Files.deleteIfExists(file.get());
			} catch (IOException ignored) {
				// The next analysis overwrites it.
			}
			return Optional.empty();
		}
	}

	/** Where {@code webcam} stands. */
	public Status status(Path webcam) {
		Path key = key(webcam);
		Status status = statuses.get(key);
		if (status != null && status.phase() != Phase.READY) {
			return status;
		}
		synchronized (loaded) {
			if (loaded.containsKey(key)) {
				return Status.READY;
			}
		}
		return cacheFile(key).filter(Files::isRegularFile).isPresent() ? Status.READY
				: Status.IDLE;
	}

	/**
	 * Analyses {@code webcam} unless it is done or running, completing with its track. A failed
	 * analysis completes exceptionally and can be requested again.
	 */
	public CompletableFuture<GazeTrack> ensure(Path webcam) {
		Optional<GazeTrack> done = ready(webcam);
		if (done.isPresent()) {
			return CompletableFuture.completedFuture(done.get());
		}
		if (closed || !isAvailable()) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					"eye contact is not available in this build"));
		}
		Path key = key(webcam);
		CompletableFuture<GazeTrack> pending = new CompletableFuture<>();
		CompletableFuture<GazeTrack> already = running.putIfAbsent(key, pending);
		if (already != null) {
			return already;
		}
		statuses.put(key, new Status(Phase.ANALYSING, 0, Optional.empty()));
		notifyChanged();
		try {
			worker.execute(() -> analyse(key, pending));
		} catch (RejectedExecutionException stopped) {
			running.remove(key, pending);
			statuses.remove(key);
			pending.completeExceptionally(stopped);
		}
		return pending;
	}

	/**
	 * Analyses if needed and waits, for callers already on a background thread (exports).
	 *
	 * @return the track, or empty when it cannot be produced (the export then leaves the eyes
	 *         as recorded)
	 */
	public Optional<GazeTrack> ensureBlocking(Path webcam) {
		try {
			return Optional.of(ensure(webcam).join());
		} catch (CompletionException | CancellationException error) {
			LOG.log(System.Logger.Level.WARNING, "no eye-contact analysis of "
					+ webcam.getFileName() + ": " + error.getMessage());
			return Optional.empty();
		}
	}

	/** Subscribes to status changes (progress included). */
	public AutoCloseable addListener(Runnable listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	private void analyse(Path webcam, CompletableFuture<GazeTrack> pending) {
		int[] lastPercent = {-1};
		try {
			GazeTrack track = GazeAnalyzer.analyze(webcam, OnnxWebGpuPlugin.probe(), fraction -> {
				int percent = (int) Math.floor(fraction * 100);
				if (percent != lastPercent[0]) {
					lastPercent[0] = percent;
					statuses.put(webcam, new Status(Phase.ANALYSING, fraction, Optional.empty()));
					notifyChanged();
				}
			}, () -> closed);
			Optional<Path> file = cacheFile(webcam);
			if (file.isPresent()) {
				track.write(file.get());
			}
			remember(webcam, track);
			statuses.put(webcam, Status.READY);
			running.remove(webcam, pending);
			pending.complete(track);
		} catch (IOException | RuntimeException error) {
			String message = error.getMessage() == null ? error.toString() : error.getMessage();
			LOG.log(System.Logger.Level.WARNING,
					"could not analyse " + webcam.getFileName() + " for eye contact: " + message);
			statuses.put(webcam, new Status(Phase.FAILED, 0, Optional.of(message)));
			running.remove(webcam, pending);
			pending.completeExceptionally(error);
		}
		notifyChanged();
	}

	private void remember(Path key, GazeTrack track) {
		synchronized (loaded) {
			loaded.put(key, track);
			while (loaded.size() > MEMORY_TRACKS) {
				Path eldest = loaded.keySet().iterator().next();
				loaded.remove(eldest);
			}
		}
	}

	/** The cache file of a recording version; empty when the recording cannot be read. */
	Optional<Path> cacheFile(Path webcam) {
		try {
			String key = webcam + "|" + Files.size(webcam) + "|"
					+ Files.getLastModifiedTime(webcam).toMillis() + "|" + ENGINE_VERSION;
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(key.getBytes(StandardCharsets.UTF_8));
			return Optional.of(cacheDir.resolve(HexFormat.of().formatHex(digest, 0, 16) + ".gzt"));
		} catch (IOException unreadable) {
			return Optional.empty();
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
	}

	/** Removes analyses nobody has written for two months (an analysis is small: ~150 B a frame). */
	public void prune() {
		if (!Files.isDirectory(cacheDir)) {
			return;
		}
		List<Path> stale = new ArrayList<>();
		long now = System.currentTimeMillis();
		try (Stream<Path> files = Files.list(cacheDir)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				String name = file.getFileName().toString();
				boolean part = name.endsWith(".part") && running.isEmpty();
				if (part || (name.endsWith(".gzt")
						&& now - Files.getLastModifiedTime(file).toMillis() > MAX_AGE_MILLIS)) {
					stale.add(file);
				}
			}
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.DEBUG, "gaze cache prune: " + error);
			return;
		}
		stale.sort(Comparator.naturalOrder());
		for (Path file : stale) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException error) {
				LOG.log(System.Logger.Level.DEBUG, "could not prune " + file);
			}
		}
	}

	private static Path key(Path webcam) {
		return webcam.toAbsolutePath().normalize();
	}

	private void notifyChanged() {
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException ignored) {
				// A broken listener must never break an analysis.
			}
		}
	}

	@Override
	public void close() {
		closed = true;
		worker.shutdownNow();
	}
}
