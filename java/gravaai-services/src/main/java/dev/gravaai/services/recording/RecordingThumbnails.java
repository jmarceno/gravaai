package dev.gravaai.services.recording;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * One small decoded still per recording for the dashboard grid, ported from
 * {@code electron/ipc/recording/thumbnail.ts}. Never starts a video player per library row.
 */
public final class RecordingThumbnails {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	private static final List<String> SUPPORTED = List.of(".mp4", ".mov", ".webm", ".mkv", ".m4v");

	private final Supplier<Path> recordingsDir;
	private final Supplier<Path> userDataDir;
	private final RecordingProcesses processes;
	private final RecordingEnvironment environment;
	private final ExecutorService queue = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "gravaai-thumbnails");
		thread.setDaemon(true);
		return thread;
	});
	private final ConcurrentHashMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

	public RecordingThumbnails(
			Supplier<Path> recordingsDir,
			Supplier<Path> userDataDir,
			RecordingProcesses processes,
			RecordingEnvironment environment) {
		this.recordingsDir = recordingsDir;
		this.userDataDir = userDataDir;
		this.processes = processes;
		this.environment = environment;
	}

	/** Decodes (or serves from cache) a 160x90 JPEG data URL for one recording. */
	public CompletableFuture<String> getRecordingThumbnail(String candidate) {
		try {
			Path root = recordingsDir.get().toRealPath();
			Path file = Path.of(candidate).toRealPath();
			Path parent = file.getParent();
			if (!file.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
					.matches(".*\\.(mp4|mov|webm|mkv|m4v)$")
					|| parent == null
					|| !parent.equals(root)) {
				return CompletableFuture.failedFuture(
						new IOException("Recording is outside the Videos library"));
			}
			if (!Files.isRegularFile(file)) {
				return CompletableFuture.failedFuture(new IOException("Recording is not a file"));
			}
			String key = sha256(file + ":" + Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file));
			return pending.computeIfAbsent(key, cacheKey -> {
				CompletableFuture<String> task = CompletableFuture.supplyAsync(
						() -> decodeOrReadCache(file, cacheKey), queue);
				task.whenComplete((value, error) -> pending.remove(cacheKey));
				return task;
			});
		} catch (IOException error) {
			return CompletableFuture.failedFuture(new IOException("Recording is outside the Videos library"));
		}
	}

	private String decodeOrReadCache(Path file, String key) {
		Path cache = userDataDir.get().resolve("recording-thumbnails").resolve(key + ".jpg");
		try {
			byte[] jpeg = Files.readAllBytes(cache);
			return dataUrl(jpeg);
		} catch (IOException missing) {
			if (Files.exists(cache)) {
				throw new IllegalStateException("Could not read the thumbnail cache", missing);
			}
		}
		byte[] jpeg = decode(file);
		if (jpeg.length == 0) {
			throw new IllegalStateException("Recording has no preview frame");
		}
		try {
			Files.createDirectories(cache.getParent());
			Files.write(cache, jpeg);
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not cache the recording thumbnail: " + error);
		}
		return dataUrl(jpeg);
	}

	private byte[] decode(Path file) {
		SpawnedProcess process;
		try {
			process = processes.spawn(List.of(
					environment.ffmpeg().toString(),
					"-hide_banner",
					"-loglevel", "error",
					"-nostdin",
					"-threads", "1",
					"-i", file.toString(),
					"-frames:v", "1",
					"-an",
					"-vf", "scale=160:90:force_original_aspect_ratio=increase,crop=160:90",
					"-q:v", "5",
					"-f", "image2pipe",
					"-c:v", "mjpeg",
					"pipe:1"),
					new SpawnOptions(false, true, true));
		} catch (IOException error) {
			throw new IllegalStateException("Could not start ffmpeg for a thumbnail", error);
		}
		try (InputStream stdout = process.stdout(); InputStream stderr = process.stderr()) {
			CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(stdout));
			CompletableFuture<byte[]> errors = CompletableFuture.supplyAsync(() -> readAll(stderr));
			int code = process.waitFor();
			byte[] bytes = output.join();
			String errorText = new String(errors.join(), StandardCharsets.UTF_8).trim();
			if (code != 0) {
				throw new IllegalStateException(
						errorText.isEmpty() ? "ffmpeg exited with code " + code : errorText);
			}
			return bytes;
		} catch (IOException error) {
			throw new IllegalStateException("Could not decode a thumbnail", error);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Thumbnail decoding was interrupted", error);
		}
	}

	private static byte[] readAll(InputStream stream) {
		try (stream) {
			return stream.readAllBytes();
		} catch (IOException error) {
			return new byte[0];
		}
	}

	private static String dataUrl(byte[] jpeg) {
		return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is required", error);
		}
	}

	/** Stops the serialized decode queue (app shutdown). */
	public void close() {
		queue.shutdownNow();
		try {
			queue.awaitTermination(1, TimeUnit.SECONDS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		}
	}
}
