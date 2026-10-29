package dev.gravaai.services.captions;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

import dev.gravaai.core.editor.WhisperModel;
import dev.gravaai.core.storage.StorageFiles;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.services.meeting.ports.FileDownloader;

/**
 * Port of {@code electron/ipc/captions/whisper.ts}: the downloadable Whisper models for
 * auto-captions, their status, download (to {@code <path>.download}, renamed when complete) and
 * delete. Files live in the user-data {@code whisper/} directory shared with the Electron app.
 */
public final class WhisperModels {

	/** {@code whisper-small-model-download-progress} payload. */
	public enum DownloadState {
		IDLE, DOWNLOADING, DOWNLOADED, ERROR
	}

	/** One download progress update. */
	public record Progress(DownloadState state, int percent, @Nullable Path path, @Nullable String error) {
	}

	private static final String LARGE_TURBO_URL =
			"https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin";

	private final UserData userData;
	private final FileDownloader downloader;

	public WhisperModels(UserData userData, FileDownloader downloader) {
		this.userData = userData;
		this.downloader = downloader;
	}

	/** The model file path ({@code WHISPER_MODELS[id].path}). */
	public Path pathFor(WhisperModel model) {
		return switch (model) {
			case SMALL -> userData.whisperSmallModelPath();
			case LARGE_V3_TURBO_Q5_0 -> userData.whisperDir().resolve("ggml-large-v3-turbo-q5_0.bin");
		};
	}

	static URI urlFor(WhisperModel model) {
		return URI.create(switch (model) {
			case SMALL -> StorageFiles.WHISPER_MODEL_DOWNLOAD_URL;
			case LARGE_V3_TURBO_Q5_0 -> LARGE_TURBO_URL;
		});
	}

	/** {@code getWhisperModelStatus}: the path when the model file is readable, else null. */
	public @Nullable Path existingPath(WhisperModel model) {
		Path path = pathFor(model);
		return Files.isReadable(path) ? path : null;
	}

	/**
	 * {@code downloadWhisperModel}: an existing model is reported as downloaded; otherwise the
	 * file downloads to {@code <path>.download} and is renamed into place.
	 */
	public Path download(WhisperModel model, Consumer<Progress> progress) throws IOException,
			InterruptedException {
		Consumer<Progress> sink = progress == null ? update -> { } : progress;
		Path existing = existingPath(model);
		if (existing != null) {
			sink.accept(new Progress(DownloadState.DOWNLOADED, 100, existing, null));
			return existing;
		}
		Path target = pathFor(model);
		Files.createDirectories(target.getParent());
		Path temp = target.resolveSibling(target.getFileName() + ".download");
		sink.accept(new Progress(DownloadState.DOWNLOADING, 0, null, null));
		try {
			Files.deleteIfExists(temp);
			downloader.download(urlFor(model), temp,
					percent -> sink.accept(new Progress(DownloadState.DOWNLOADING,
							Math.max(0, Math.min(100, percent)), null, null)));
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			sink.accept(new Progress(DownloadState.DOWNLOADED, 100, target, null));
			return target;
		} catch (IOException | InterruptedException | RuntimeException error) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
				// Best effort, like fs.rm(..., { force: true }).catch(() => undefined).
			}
			sink.accept(new Progress(DownloadState.ERROR, 0, null, String.valueOf(error)));
			throw error;
		}
	}

	/** {@code deleteWhisperModel}; a file that is already gone counts as deleted. */
	public void delete(WhisperModel model) throws IOException {
		Files.deleteIfExists(pathFor(model));
	}
}
