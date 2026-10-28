package dev.gravaai.services.projects;

/**
 * Emitted when a project preview thumbnail was generated in the background (port of
 * {@code ProjectThumbnailReady} in {@code electron/ipc/project/manager.ts}).
 *
 * @param path the project file path
 * @param thumbnailPath the written {@code *.preview.png} path
 * @param updatedAt the source project revision ({@code mtimeMs}) the thumbnail belongs to
 */
public record ProjectThumbnailReady(String path, String thumbnailPath, long updatedAt) {
}
