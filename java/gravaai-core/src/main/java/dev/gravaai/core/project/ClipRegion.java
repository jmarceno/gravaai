package dev.gravaai.core.project;

/**
 * A kept clip on the timeline (port of {@code ClipRegion} in types.ts).
 *
 * <p>{@code sourceStartMs} defaults to {@code startMs} while everything before it plays at 1x;
 * splitting or left-trimming a sped-up clip moves the source in without moving the clip.
 */
public record ClipRegion(
		String id,
		long startMs,
		long endMs,
		Long sourceStartMs,
		Long sourceMinMs,
		Long sourceMaxMs,
		double speed,
		Boolean muted,
		Boolean showSourceAudio,
		ClipCameraLayout cameraLayout,
		ClipTransition transitionOut) {}
