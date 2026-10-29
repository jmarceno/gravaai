package dev.gravaai.services.projects;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * A listed project read for its hover preview without switching the active project (port of
 * {@code ProjectPreviewData} in {@code src/types/projectPreview.ts}; the media URLs become file
 * paths because the Java app decodes files directly, plan §2.2).
 *
 * @param project the parsed project JSON (after legacy profile path remapping)
 * @param videoPath the recording the preview plays
 * @param webcamPath the webcam recording, or null when the project has none
 */
public record ProjectPreview(JsonNode project, String videoPath, @Nullable String webcamPath) {
}
