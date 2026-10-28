package dev.gravaai.services.projects;

/**
 * Media files a project references (port of the success branch of
 * {@code resolveProjectMediaSources} in {@code electron/ipc/project/manager.ts}).
 *
 * @param videoPath the normalized source video path
 * @param webcamPath the normalized webcam path, or {@code null} when absent/missing
 */
public record ProjectMediaSources(String videoPath, String webcamPath) {
}
