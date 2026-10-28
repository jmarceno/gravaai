package dev.gravaai.media.export;

import java.nio.file.Path;

/** Result of one successful encoder run (before publishing). */
public record ExportResult(Path path, long bytes, int frames, double durationSeconds) {
}
