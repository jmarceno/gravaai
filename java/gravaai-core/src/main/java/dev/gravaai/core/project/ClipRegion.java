package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

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
		@Nullable Long sourceStartMs,
		@Nullable Long sourceMinMs,
		@Nullable Long sourceMaxMs,
		double speed,
		@Nullable Boolean muted,
		@Nullable Boolean showSourceAudio,
		@Nullable ClipCameraLayout cameraLayout,
		@Nullable ClipTransition transitionOut) {}
