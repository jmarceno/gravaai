package dev.gravaai.media.export;

import java.nio.file.Path;
import java.util.List;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.project.Chapters;

/** One MP4 encode request (plan §5.10). */
public record Mp4ExportRequest(int width, int height, int frameRate, int bitrate,
		ExportEncodingMode encodingMode, double durationSeconds, Path outputPath,
		ExportAudioPlan audioPlan, List<Chapters.Chapter> chapters) {

	/** An MP4 encode with no chapter metadata. */
	public Mp4ExportRequest(int width, int height, int frameRate, int bitrate,
			ExportEncodingMode encodingMode, double durationSeconds, Path outputPath,
			ExportAudioPlan audioPlan) {
		this(width, height, frameRate, bitrate, encodingMode, durationSeconds, outputPath, audioPlan,
				List.of());
	}

	public Mp4ExportRequest {
		if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
			throw new IllegalArgumentException("MP4 export needs even positive dimensions");
		}
		if (frameRate <= 0 || !Double.isFinite(durationSeconds) || durationSeconds <= 0) {
			throw new IllegalArgumentException("MP4 export needs a positive frame rate and duration");
		}
		if (outputPath == null) {
			throw new IllegalArgumentException("MP4 export needs an output path");
		}
		if (encodingMode == null) {
			encodingMode = ExportEncodingMode.BALANCED;
		}
		audioPlan = audioPlan == null ? ExportAudioPlan.NONE : audioPlan;
		chapters = chapters == null ? List.of() : List.copyOf(chapters);
	}

	/** Number of frames the encoder renders ({@code ceil(duration * fps)}, like the TS export). */
	public long totalFrames() {
		return (long) Math.ceil(durationSeconds * frameRate);
	}

	/** The chapter list as the {@code ffmetadata} payload, or empty when there are none. */
	public boolean hasChapters() {
		return !chapters.isEmpty();
	}

	/** The chapters written as {@code ffmetadata}, or null when there is nothing to embed. */
	public ChapterSidecars.@org.jspecify.annotations.Nullable Payload chapterPayload() {
		return hasChapters() ? new ChapterSidecars.Payload(chapters) : null;
	}
}

