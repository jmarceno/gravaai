package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

	private static final System.Logger LOG = System.getLogger(ExportRunner.class.getName());

	/**
	 * Everything one export needs.
	 *
	 * @param captionSidecars the caption sidecar payload, or null when the export has no captions
	 * @param chapters the chapters to embed and write as a sidecar, or empty for none
	 */
	public record Spec(ExportFormat format, int width, int height, int frameRate, int bitrate,
			ExportEncodingMode encodingMode, double durationSeconds, boolean gifLoop,
			Path destination, boolean reveal, boolean overwrite,
			CaptionSidecars.@Nullable Payload captionSidecars, ExportAudioPlan audioPlan,
			List<dev.gravaai.core.project.Chapters.Chapter> chapters) {

		public Spec {
			chapters = chapters == null ? List.of() : List.copyOf(chapters);
		}

		/** An export with no chapters (the shape every caller but the editor used before markers). */
		public Spec(ExportFormat format, int width, int height, int frameRate, int bitrate,
				ExportEncodingMode encodingMode, double durationSeconds, boolean gifLoop,
				Path destination, boolean reveal, boolean overwrite,
				CaptionSidecars.@Nullable Payload captionSidecars, ExportAudioPlan audioPlan) {
			this(format, width, height, frameRate, bitrate, encodingMode, durationSeconds, gifLoop,
					destination, reveal, overwrite, captionSidecars, audioPlan, List.of());
		}
	}

	/** Result of a published export. */
	public record Outcome(Path path, long bytes, int frames, double durationSeconds,
			CaptionSidecars.WriteResult captionSidecars,
			ChapterSidecars.WriteResult chapterSidecars) {}

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
							spec.durationSeconds(), tempFile, spec.audioPlan(), spec.chapters()),
							frames, capturing, cancellation);
			ExportProgress savingProgress = ExportProgressState
					.resolveSavingExportProgress(lastProgress.get());
			capturing.onProgress(savingProgress);
			ExportPublisher.PublishResult published = publisher.publish(encoded.path(),
					new ExportPublisher.Destination(target, spec.reveal()), spec.overwrite());
			CaptionSidecars.WriteResult sidecars = CaptionSidecars.writeBestEffort(published.path(),
					spec.captionSidecars());
			// The chapters are already inside the MP4; the sidecar is the YouTube-ready copy.
			ChapterSidecars.WriteResult chapterSidecars =
					format == ExportFormat.MP4
							? ChapterSidecars.writeBestEffort(published.path(),
									new ChapterSidecars.Payload(spec.chapters()))
							: ChapterSidecars.WriteResult.SKIPPED;
			// The video is fine without it, but a silent failure would leave the user without the file.
			chapterSidecars.error().ifPresent(error -> LOG.log(System.Logger.Level.WARNING,
					"chapters.txt was not written next to " + published.path() + ": " + error));
			return new Outcome(published.path(), published.bytes(), encoded.frames(),
					encoded.durationSeconds(), sidecars, chapterSidecars);
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
