package dev.gravaai.media.compose;

import java.util.List;

/**
 * Port of {@code src/components/video-editor/videoPlayback/sceneMotion.ts} (plan §5.8 stage 3):
 * resolve the camera target for a media timestamp independent of the renderer, plus the
 * preview transport motion-mode policy.
 */
public final class SceneMotion {

	private SceneMotion() {}

	/** The resolved scene camera target (TS {@code SceneZoomTarget}). */
	public record SceneZoomTarget(double scale, ZoomFocus focus, double progress) {

		/** The neutral camera: no zoom. */
		public static final SceneZoomTarget NEUTRAL =
				new SceneZoomTarget(1, ZoomConstants.DEFAULT_FOCUS, 0);
	}

	/** Preview camera reaction to the transport state (TS {@code PreviewMotionMode}). */
	public enum PreviewMotionMode {
		SPRING,
		SNAP,
		PRESERVE
	}

	/**
	 * Request for {@link #resolveSceneZoomTarget}: all the editor state the resolver needs.
	 *
	 * @param zoomRegions the zoom regions
	 * @param timeMs the timeline time in milliseconds
	 * @param cursorTimeMs the cursor time (defaults to {@code timeMs} when null)
	 * @param connectZooms whether chained zooms connect
	 * @param zoomInDurationMs the zoom-in duration override
	 * @param zoomOutDurationMs the zoom-out duration override
	 * @param zoomClassicMode classic mode disables cursor-follow
	 * @param cursorTelemetry the cursor telemetry samples
	 * @param cursorFollowCamera the persistent cursor-follow state
	 */
	public record SceneZoomRequest(List<ZoomRegionSpec> zoomRegions, double timeMs,
			Double cursorTimeMs, Boolean connectZooms, Double zoomInDurationMs,
			Double zoomOutDurationMs, boolean zoomClassicMode, List<CursorSample> cursorTelemetry,
			CursorFollowCamera.CursorFollowCameraState cursorFollowCamera) {

		/** A request with default options and no cursor telemetry. */
		public static SceneZoomRequest of(List<ZoomRegionSpec> zoomRegions, double timeMs,
				CursorFollowCamera.CursorFollowCameraState cursorFollowCamera) {
			return new SceneZoomRequest(zoomRegions, timeMs, null, null, null, null, false,
					List.of(), cursorFollowCamera);
		}
	}

	/** {@code resolvePreviewMotionMode}. */
	public static PreviewMotionMode resolvePreviewMotionMode(boolean isPlaying,
			boolean isSeeking, boolean shouldSnapPausedFrame, boolean zoomClassicMode) {
		if (isSeeking || shouldSnapPausedFrame || (isPlaying && zoomClassicMode)) {
			return PreviewMotionMode.SNAP;
		}
		return isPlaying ? PreviewMotionMode.SPRING : PreviewMotionMode.PRESERVE;
	}

	/** {@code shouldComposePreviewFrame}. */
	public static boolean shouldComposePreviewFrame(PreviewMotionMode motionMode,
			boolean isSeeking, boolean contentTimeChanged, boolean shouldSnapPausedFrame) {
		if (isSeeking || motionMode == PreviewMotionMode.PRESERVE) {
			return false;
		}
		return contentTimeChanged || shouldSnapPausedFrame;
	}

	/**
	 * {@code resolveSceneZoomTarget}: the camera target for a media timestamp.
	 *
	 * @throws IllegalArgumentException when the request is missing the regions or the camera
	 *         state
	 */
	public static SceneZoomTarget resolveSceneZoomTarget(SceneZoomRequest request) {
		if (request == null || request.zoomRegions() == null
				|| request.cursorFollowCamera() == null) {
			throw new IllegalArgumentException(
					"scene zoom request needs zoomRegions and cursorFollowCamera");
		}
		List<CursorSample> telemetry = request.cursorTelemetry() == null
				? List.of()
				: request.cursorTelemetry();
		ZoomRegionUtils.DominantRegionResult dominant = ZoomRegionUtils.findDominantRegion(
				request.zoomRegions(), request.timeMs(),
				new ZoomRegionUtils.DominantRegionOptions(request.connectZooms(),
						request.zoomInDurationMs(), request.zoomOutDurationMs()));
		if (dominant.region() == null || dominant.strength() <= 0) {
			return SceneZoomTarget.NEUTRAL;
		}
		double scale = dominant.blendedScale() != null
				? dominant.blendedScale()
				: dominant.region().scale();
		ZoomFocus focus = dominant.region().focus();
		if (!request.zoomClassicMode()
				&& dominant.region().mode() != ZoomMode.MANUAL
				&& !telemetry.isEmpty()) {
			double cursorTimeMs = request.cursorTimeMs() == null
					? request.timeMs()
					: request.cursorTimeMs();
			focus = CursorFollowCamera.computeCursorFollowFocus(request.cursorFollowCamera(),
					telemetry, cursorTimeMs, scale, dominant.strength(), dominant.region().focus(),
					new CursorFollowCamera.CursorFollowConfig(
							CursorFollowCamera.SNAP_TO_EDGES_RATIO_AUTO));
		}
		return new SceneZoomTarget(scale, focus, dominant.strength());
	}
}
