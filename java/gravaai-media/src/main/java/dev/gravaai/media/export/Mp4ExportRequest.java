package dev.gravaai.media.export;

import java.nio.file.Path;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;

/** One MP4 encode request (plan §5.10). */
public record Mp4ExportRequest(int width, int height, int frameRate, int bitrate,
		ExportEncodingMode encodingMode, double durationSeconds, Path outputPath,
		ExportAudioPlan audioPlan) {

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
	}

	/** Number of frames the encoder renders ({@code ceil(duration * fps)}, like the TS export). */
	public long totalFrames() {
		return (long) Math.ceil(durationSeconds * frameRate);
	}
}
