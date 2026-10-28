package dev.gravaai.services.projects;

/**
 * One project file shown in the dashboard library (port of {@code ProjectLibraryEntry} in
 * {@code src/components/video-editor/ProjectBrowserDialog.tsx}).
 *
 * <p>Read-only subset of the P2 project model: the editor model itself lives in
 * {@code dev.gravaai.core.project} (a separate task); the dashboard only needs the file name,
 * thumbnail and dates.
 *
 * @param path the absolute normalized project file path
 * @param name the file name without the {@code .gravaai} / {@code .openscreen} extension
 * @param createdAt the file creation time ({@code birthtimeMs || ctimeMs} in TS)
 * @param updatedAt the file modification time in milliseconds
 * @param thumbnailPath the fresh {@code *.preview.png} path, or {@code null} when absent/stale
 * @param isCurrent {@code true} for the project currently open in the editor
 * @param isInProjectsDirectory {@code true} when the file is directly inside the Projects folder
 */
public record ProjectLibraryEntry(
		String path,
		String name,
		long createdAt,
		long updatedAt,
		String thumbnailPath,
		boolean isCurrent,
		boolean isInProjectsDirectory) {
}
