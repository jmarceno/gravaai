package dev.gravaai.core.editor;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.WebcamCorner;
import dev.gravaai.core.project.WebcamOverlaySettings;
import dev.gravaai.core.project.WebcamPositionPreset;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reusable part of the webcam settings stored in an editor preset: the imported media
 * attachment ({@code sourcePath}, {@code visibleRanges}) is deliberately left out.
 *
 * <p>A preset stores a full snapshot of the reusable overlay controls, so every component is
 * present (the only genuinely absent value is {@code cornerRadius}, which is unset by default);
 * {@code ProjectPersistence} supplies the same defaults when a project is loaded.
 */
public record WebcamPresetSettings(
		boolean enabled,
		long timeOffsetMs,
		boolean mirror,
		CropRegion cropRegion,
		WebcamCorner corner,
		WebcamPositionPreset positionPreset,
		double positionX,
		double positionY,
		double size,
		double width,
		double height,
		boolean reactToZoom,
		double roundness,
		@Nullable Double cornerRadius,
		double shadow,
		double margin) {

	public static WebcamPresetSettings from(WebcamOverlaySettings webcam) {
		return new WebcamPresetSettings(webcam.enabled(), webcam.timeOffsetMs(), webcam.mirror(),
				webcam.cropRegion(), webcam.corner(), webcam.positionPreset(), webcam.positionX(),
				webcam.positionY(), webcam.size(), webcam.width(), webcam.height(),
				webcam.reactToZoom(), webcam.roundness(), webcam.cornerRadius(), webcam.shadow(),
				webcam.margin());
	}

	public WebcamOverlaySettings toOverlaySettings() {
		return new WebcamOverlaySettings(null, enabled, null, timeOffsetMs, mirror, cropRegion,
				corner, positionPreset, positionX, positionY, size, width, height, reactToZoom,
				roundness, cornerRadius, shadow, margin);
	}

	public ObjectNode toJson() {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("enabled", enabled);
		node.put("timeOffsetMs", timeOffsetMs);
		node.put("mirror", mirror);
		ObjectNode crop = Json.nodeFactory().objectNode();
		crop.put("x", cropRegion.x());
		crop.put("y", cropRegion.y());
		crop.put("width", cropRegion.width());
		crop.put("height", cropRegion.height());
		node.set("cropRegion", crop);
		node.put("positionPreset", positionPreset.wire());
		node.put("positionX", positionX);
		node.put("positionY", positionY);
		node.put("corner", corner.wire());
		node.put("size", size);
		node.put("width", width);
		node.put("height", height);
		node.put("reactToZoom", reactToZoom);
		node.put("roundness", roundness);
		node.put("shadow", shadow);
		node.put("margin", margin);
		return node;
	}
}
