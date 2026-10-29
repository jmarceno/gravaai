package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;

/**
 * One complete export: encode into an app-managed temp file, publish it with the save policy, then
 * write the optional caption sidecars (plan §5.10). The services/export coordinator and the
 * AppContext wiring call this from a single export thread.
 */
public final class ExportRunner {

	/**
	 * Everything one export needs.
	 *
	 * @param captionSidecars the caption sidecar payload, or null when the export has no captions
	 */
	public record Spec(ExportFormat format, int width, int height, int frameRate, int bitrate,
			ExportEncodingMode encodingMode, double durationSeconds, boolean gifLoop,
			Path destination, boolean reveal, boolean overwrite,
			CaptionSidecars.@Nullable Payload captionSidecars, ExportAudioPlan audioPlan) {}

	/** Result of a published export. */
	public record Outcome(Path path, long bytes, int frames, double durationSeconds,
			CaptionSidecars.WriteResult captionSidecars) {}

	private ExportRunner() {}

	/** Runs one export to completion on the calling thread. */
	public static Outcome run(Spec spec, ExportFrameProvider frames, ExportProgressListener listener,
			ExportCancellation cancellation, ExportPublisher publisher) {
		Objects.requireNonNull(spec, "spec");
		Objects.requireNonNull(frames, "frames");
		Objects.requireNonNull(listener, "listener");
		Objects.requireNonNull(cancellation, "cancellation");
		Objects.requireNonNull(publisher, "publisher");
		ExportFormat format = spec.format() == null ? ExportFormat.MP4 : spec.format();
		Path target = spec.destination().toAbsolutePath().normalize();
		if (target.getParent() == null) {
			throw new ExportException("export destination has no parent directory: " + target);
		}
		String extension = format == ExportFormat.GIF ? ".gif" : ".mp4";
		Path tempFile;
		try {
			Files.createDirectories(target.getParent());
			tempFile = Files.createTempFile(target.getParent(), ".gravaai-export-", extension);
			Files.deleteIfExists(tempFile);
		} catch (IOException error) {
			throw new ExportException("could not create the export temp file", error);
		}
		AtomicReference<ExportProgress> lastProgress = new AtomicReference<>();
		ExportProgressListener capturing = progress -> {
			lastProgress.set(progress);
			listener.onProgress(progress);
		};
		try {
			capturing.onProgress(ExportProgress.of(0, (int) Math.ceil(spec.durationSeconds()
					* spec.frameRate()), 0, 0).withPhase(ExportProgress.Phase.PREPARING));
			ExportResult encoded = format == ExportFormat.GIF
					? GifEncoder.encode(new GifExportRequest(spec.width(), spec.height(),
							spec.frameRate(), spec.durationSeconds(), spec.gifLoop(), tempFile),
							frames, capturing, cancellation)
					: Mp4Encoder.encode(new Mp4ExportRequest(spec.width(), spec.height(),
							spec.frameRate(), spec.bitrate(), spec.encodingMode(),
							spec.durationSeconds(), tempFile, spec.audioPlan()), frames, capturing,
							cancellation);
			ExportProgress savingProgress = ExportProgressState
					.resolveSavingExportProgress(lastProgress.get());
			capturing.onProgress(savingProgress);
			ExportPublisher.PublishResult published = publisher.publish(encoded.path(),
					new ExportPublisher.Destination(target, spec.reveal()), spec.overwrite());
			CaptionSidecars.WriteResult sidecars = CaptionSidecars.writeBestEffort(published.path(),
					spec.captionSidecars());
			return new Outcome(published.path(), published.bytes(), encoded.frames(),
					encoded.durationSeconds(), sidecars);
		} catch (RuntimeException error) {
			try {
				Files.deleteIfExists(tempFile);
			} catch (IOException ignored) {
				// Best effort.
			}
			throw error;
		}
	}
}
