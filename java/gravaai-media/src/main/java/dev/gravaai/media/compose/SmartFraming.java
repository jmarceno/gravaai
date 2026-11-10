package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.core.project.CameraLayoutSegment;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.ProjectEditorState;

import io.github.humbleui.types.Rect;

/**
 * Smart framing: the opt-in per-project behaviour that keeps the webcam bubble off the things the
 * viewer needs to see — the cursor and the active zoom focus. When one of those would sit under
 * the camera for longer than a short grace period, the camera eases to another corner or shrinks,
 * and eases back once the way is clear again.
 *
 * <p>The whole dodge schedule is planned up front as a pure function of the project state, the
 * cursor telemetry and the output size ({@link #plan}), so preview, export and the camera-track
 * indicators all see the same moves without render-order state: {@link #plan} walks the timeline
 * once, the resulting {@link Plan} answers "where is the camera" for any time in O(log n).
 * Decisions overlap-test against the unsmoothed camera target (the springs only lag by a fraction
 * of the grace period) and the moves themselves glide with the same smoothstep the Camera layout
 * track uses. Camera layout segments stand down — the user pinned the camera there on purpose —
 * and smart framing re-engages in the gaps.</p>
 */
public final class SmartFraming {

	/** How long a protected point may sit under the camera before it dodges. */
	public static final long GRACE_MS = 600;

	/** The glide of one dodge or return. */
	public static final long MOVE_MS = 450;

	/** How long the way must stay clear before the camera eases back to its layout. */
	public static final long RETURN_MS = 1200;

	/** Each shrink step scales the card's size percents by this. */
	public static final double SHRINK_FACTOR = 0.7;

	/** The smallest size factor a shrink chain reaches before giving up. */
	public static final double MIN_SHRINK_FACTOR = 0.35;

	/** Padding around a protected point and the card edge, at the 1920 reference width. */
	private static final double PROTECTION_PAD_PX = 16;

	/** Walk granularity for the zoom focus and for sparse cursor telemetry. */
	private static final long WALK_STEP_MS = 100;

	/** Cursor telemetry is decimated to about this cadence for the walk. */
	private static final long SAMPLE_STEP_MS = 50;

	/** Where the camera sits for one frame: the layout fraction pair plus a size scale. */
	public record Override(double positionX, double positionY, double sizeFactor, boolean active) {

		public static final Override NONE = new Override(0, 0, 1, false);

		private Override lerp(Override to, double t) {
			return new Override(positionX + (to.positionX - positionX) * t,
					positionY + (to.positionY - positionY) * t,
					sizeFactor + (to.sizeFactor - sizeFactor) * t, true);
		}

		private boolean isBase(Override base) {
			return !active || (Math.abs(positionX - base.positionX) < 1e-4
					&& Math.abs(positionY - base.positionY) < 1e-4
					&& Math.abs(sizeFactor - base.sizeFactor) < 1e-4);
		}
	}

	/** One dodge or return: the camera glides from {@code from} to {@code to} at {@code startMs}. */
	public record Move(double startMs, Override from, Override to, boolean returning) {
	}

	/**
	 * The planned camera behaviour over the whole timeline. {@link #at} resolves the eased
	 * override for a timeline time; {@link #moves} feeds the camera-track indicators.
	 */
	public record Plan(List<Move> moves, Override base) {

		public Plan {
			moves = moves == null ? List.of() : List.copyOf(moves);
		}

		public boolean isEmpty() {
			return moves.isEmpty();
		}

		/** The eased override at a timeline time, or {@link Override#NONE} when at the layout. */
		public Override at(double timelineMs) {
			Move active = null;
			int lo = 0;
			int hi = moves.size() - 1;
			while (lo <= hi) {
				int mid = (lo + hi) >>> 1;
				Move move = moves.get(mid);
				if (move.startMs() <= timelineMs) {
					active = move;
					lo = mid + 1;
				} else {
					hi = mid - 1;
				}
			}
			if (active == null) {
				return Override.NONE;
			}
			double progress = Math.max(0, Math.min(1,
					(timelineMs - active.startMs()) / MOVE_MS));
			double eased = progress * progress * (3 - 2 * progress);
			Override resolved = active.from().lerp(active.to(), eased);
			return resolved.isBase(base) ? Override.NONE : resolved;
		}
	}

	/** Substitutes an active override into the spec: the custom fraction pair and the shrunk size. */
	public static WebcamSpec apply(WebcamSpec spec, Override override) {
		if (!override.active()) {
			return spec;
		}
		double factor = Math.max(MIN_SHRINK_FACTOR, Math.min(1, override.sizeFactor()));
		return new WebcamSpec(spec.enabled(), "custom", spec.corner(),
				Math.max(0, Math.min(1, override.positionX())),
				Math.max(0, Math.min(1, override.positionY())), spec.size() * factor,
				spec.width() * factor, spec.height() * factor, spec.mirror(), spec.roundness(),
				spec.shadow(), spec.margin(), spec.reactToZoom(), spec.cropRegion(),
				spec.visibleRanges(), spec.timeOffsetMs(), spec.transition(),
				spec.backgroundEffect());
	}

	private SmartFraming() {
	}

	/**
	 * Plans the dodge schedule for a project, or null when smart framing is off or there is no
	 * webcam overlay to move. Deterministic: the same editor state, telemetry, media sizes and
	 * output size always yield the same plan.
	 *
	 * @param editor the resolved project editor state
	 * @param samples cursor telemetry in source-time seconds, in any order
	 * @param sourceDurationSeconds the source recording duration (0 when unknown)
	 * @param videoWidth/-Height the source recording pixel size (0 when unknown)
	 * @param webcamWidth/-Height the webcam recording pixel size for the crop-matched card height
	 *        (0 when unknown: the crop match is then skipped)
	 * @param stageWidth/-Height the compositor output size
	 */
	public static @Nullable Plan plan(ProjectEditorState editor, List<CursorSample> samples,
			double sourceDurationSeconds, int videoWidth, int videoHeight, int webcamWidth,
			int webcamHeight, int stageWidth, int stageHeight) {
		var settings = editor.webcam();
		if (settings == null || !settings.smartFraming() || !settings.enabled()
				|| stageWidth <= 0 || stageHeight <= 0) {
			return null;
		}
		WebcamSpec base = ProjectEditorInputs.webcamSpec(editor);
		if (SplitScreenLayout.isSplit(base.positionPreset())) {
			return null;
		}
		List<ClipRegion> clips = editor.clipRegions() == null ? List.of() : editor.clipRegions();
		List<CameraLayoutSegment> segments = editor.cameraLayoutSegments();
		List<CursorSample> telemetry = samples == null ? List.of() : samples;
		LayoutSpec layout = ProjectEditorInputs.layout(editor);
		ZoomSpec zoom = ProjectEditorInputs.zoomSpec(editor);

		Rect baseMask = baseMask(layout, videoWidth, videoHeight, stageWidth, stageHeight);
		Override layoutOverride = layoutOverride(base, stageWidth, stageHeight);
		List<CursorSample> sorted = telemetry.stream()
				.sorted(Comparator.comparingDouble(CursorSample::timeSeconds))
				.toList();
		CursorFollowCamera.CursorFollowCameraState followState =
				CursorFollowCamera.createCursorFollowCameraState();

		long timelineDurationMs = timelineDurationMs(clips, sourceDurationSeconds, sorted);
		List<Double> steps = walkSteps(sorted, clips, timelineDurationMs);

		List<Move> moves = new ArrayList<>();
		Override current = layoutOverride;
		double dwellSinceMs = Double.NaN;
		double clearSinceMs = Double.NaN;
		boolean zoomWasActive = false;
		for (double timeMs : steps) {
			SceneSpec scene = ProjectEditorInputs.sceneSpec(editor, timeMs);
			if (standsDown(base, clips, segments, timeMs, scene)) {
				if (!current.isBase(layoutOverride)) {
					// Ease back just before the pinned layout (segment cut or glide) takes over.
					double startMs = Math.max(lastMoveStart(moves), timeMs - MOVE_MS);
					moves.add(new Move(startMs, resolvedAt(moves, startMs, layoutOverride),
							layoutOverride, true));
					current = layoutOverride;
				}
				dwellSinceMs = Double.NaN;
				clearSinceMs = Double.NaN;
				zoomWasActive = false;
				CursorFollowCamera.resetCursorFollowCamera(followState);
				continue;
			}
			double sourceMs = Clips.mapTimelineTimeToSourceTime(timeMs, clips);
			SceneMotion.SceneZoomTarget target = zoomTarget(zoom, timeMs, sourceMs, sorted,
					followState, zoomWasActive);
			zoomWasActive = target.progress() > 0;
			ZoomTransform.AppliedTransform transform = ZoomTransform.computeZoomTransform(
					new RenderTargetSize(stageWidth, stageHeight), baseMask, target.scale(),
					target.progress(), target.focus().cx(), target.focus().cy());
			List<double[]> protectedPoints = protectedPoints(sorted, sourceMs, layout, baseMask,
					target, transform);
			double zoomScale = 1 + (target.scale() - 1) * target.progress();
			double pad = WebcamOverlay.scalePixels(PROTECTION_PAD_PX, stageWidth);
			Rect currentBox = box(base, stageWidth, stageHeight, current, zoomScale, webcamWidth,
					webcamHeight);
			Rect layoutBox = box(base, stageWidth, stageHeight, layoutOverride, zoomScale,
					webcamWidth, webcamHeight);
			boolean insideCurrent = !protectedPoints.isEmpty()
					&& anyInside(protectedPoints, currentBox, pad);
			boolean insideLayout = !protectedPoints.isEmpty()
					&& anyInside(protectedPoints, layoutBox, pad);
			if (insideCurrent) {
				clearSinceMs = Double.NaN;
				if (!Double.isFinite(dwellSinceMs)) {
					dwellSinceMs = timeMs;
				}
				if (timeMs - dwellSinceMs >= GRACE_MS) {
					Override dodge = dodgeTarget(base, stageWidth, stageHeight, current,
							zoomScale, webcamWidth, webcamHeight, protectedPoints, pad);
					if (!nearly(dodge, current)) {
						double startMs = timeMs;
						moves.add(new Move(startMs, resolvedAt(moves, startMs, layoutOverride),
								dodge, false));
						current = dodge;
						dwellSinceMs = timeMs;
					}
					// Nothing clears the point: keep dwelling; the card is as far away as it goes.
				}
			} else {
				dwellSinceMs = Double.NaN;
			}
			if (!insideCurrent && !insideLayout) {
				if (!Double.isFinite(clearSinceMs)) {
					clearSinceMs = timeMs;
				}
				if (!current.isBase(layoutOverride) && timeMs - clearSinceMs >= RETURN_MS) {
					double startMs = timeMs;
					moves.add(new Move(startMs, resolvedAt(moves, startMs, layoutOverride),
							layoutOverride, true));
					current = layoutOverride;
					clearSinceMs = timeMs;
				}
			} else {
				clearSinceMs = Double.NaN;
			}
		}
		return new Plan(moves, layoutOverride);
	}

	// ---- the walk -----------------------------------------------------------------------

	/**
	 * True when the camera is not the project layout here: a Camera layout segment pins it (the
	 * manual override), its smooth exit glide is still gliding, a per-clip layout fades or fills
	 * it, the webcam is hidden at this source time, or the preset is a split (the camera never
	 * floats over the content).
	 */
	private static boolean standsDown(WebcamSpec base, List<ClipRegion> clips,
			List<CameraLayoutSegment> segments, double timeMs, SceneSpec scene) {
		if (CameraLayoutSegment.at(segments, timeMs).isPresent()) {
			return true;
		}
		for (CameraLayoutSegment segment : segments) {
			double elapsed = timeMs - segment.endMs();
			if (elapsed >= 0 && elapsed < CameraLayoutSegment.SMOOTH_MS
					&& segment.transitionOut() == CameraLayoutSegment.Transition.SMOOTH
					&& segment.followedByGap(segments)) {
				return true;
			}
		}
		if (scene.cameraFill() > 0.01 || scene.cameraOpacity() < 0.99) {
			return true;
		}
		double sourceMs = Clips.mapTimelineTimeToSourceTime(timeMs, clips);
		return !base.enabled() || !base.isVisibleAt(sourceMs);
	}

	/** The zoom camera target for one walk step, carrying the cursor-follow state like the render. */
	private static SceneMotion.SceneZoomTarget zoomTarget(ZoomSpec zoom, double timeMs,
			double sourceMs, List<CursorSample> sorted,
			CursorFollowCamera.CursorFollowCameraState followState, boolean zoomWasActive) {
		if (zoom == null || !zoom.isActive()) {
			CursorFollowCamera.resetCursorFollowCamera(followState);
			return SceneMotion.SceneZoomTarget.NEUTRAL;
		}
		if (!zoomWasActive) {
			// The render resets the follow state when the camera comes back from idle.
			CursorFollowCamera.resetCursorFollowCamera(followState);
		}
		return SceneMotion.resolveSceneZoomTarget(new SceneMotion.SceneZoomRequest(
				zoom.regions(), timeMs, sourceMs, zoom.connectZooms(), zoom.zoomInDurationMs(),
				zoom.zoomOutDurationMs(), zoom.zoomClassicMode(), sorted, followState));
	}

	/** The cursor point (stage px) plus the zoom focus point when a zoom is arriving or active. */
	private static List<double[]> protectedPoints(List<CursorSample> sorted, double sourceMs,
			LayoutSpec layout, Rect baseMask, SceneMotion.SceneZoomTarget target,
			ZoomTransform.AppliedTransform transform) {
		List<double[]> points = new ArrayList<>(2);
		CursorSample cursor = CursorPosition.interpolateCursorPosition(sorted, sourceMs);
		if (cursor != null) {
			double[] projected = CursorRenderer.project(cursor.x(), cursor.y(), layout.crop());
			if (projected[2] > 0) {
				// The cursor rides inside the camera transform (stage 6), like the render draws it.
				points.add(toStage(baseMask, projected[0], projected[1], transform));
			}
		}
		if (target.progress() > 0) {
			// The focus lands at the stage centre at full zoom; track it through the same
			// transform the video layer takes so partial ramps protect the sliding point too.
			points.add(toStage(baseMask, target.focus().cx(), target.focus().cy(), transform));
		}
		return points;
	}

	private static double[] toStage(Rect baseMask, double maskX, double maskY,
			ZoomTransform.AppliedTransform transform) {
		double x = baseMask._left + maskX * baseMask.getWidth();
		double y = baseMask._top + maskY * baseMask.getHeight();
		return new double[] {x * transform.scale() + transform.x(),
				y * transform.scale() + transform.y()};
	}

	/** Corners first (nearest to the camera now), then shrink steps; the first clearing one wins. */
	private static Override dodgeTarget(WebcamSpec base, int stageWidth, int stageHeight,
			Override current, double zoomScale, int webcamWidth, int webcamHeight,
			List<double[]> protectedPoints, double pad) {
		double[] centre = centreOf(box(base, stageWidth, stageHeight, current, zoomScale,
				webcamWidth, webcamHeight));
		record Candidate(Override override, double distance) {}
		List<Candidate> candidates = new ArrayList<>();
		for (double[] corner : new double[][] {{1, 1}, {0, 1}, {1, 0}, {0, 0}}) {
			Override candidate = new Override(corner[0], corner[1], current.sizeFactor(), true);
			Rect box = box(base, stageWidth, stageHeight, candidate, zoomScale, webcamWidth,
					webcamHeight);
			double[] candidateCentre = centreOf(box);
			candidates.add(new Candidate(candidate,
					Math.hypot(candidateCentre[0] - centre[0], candidateCentre[1] - centre[1])));
		}
		candidates.sort(Comparator.comparingDouble(Candidate::distance));
		for (Candidate candidate : candidates) {
			if (!anyInside(protectedPoints,
					box(base, stageWidth, stageHeight, candidate.override(), zoomScale,
							webcamWidth, webcamHeight), pad)) {
				return candidate.override();
			}
		}
		for (double factor = current.sizeFactor() * SHRINK_FACTOR;
				factor >= MIN_SHRINK_FACTOR - 1e-9; factor *= SHRINK_FACTOR) {
			Override candidate = new Override(current.positionX(), current.positionY(), factor,
					true);
			if (!anyInside(protectedPoints,
					box(base, stageWidth, stageHeight, candidate, zoomScale, webcamWidth,
							webcamHeight), pad)) {
				return candidate;
			}
		}
		// Nothing fully clears: shrink as far as the floor allows, which still gets out of the way.
		return new Override(current.positionX(), current.positionY(),
				Math.max(MIN_SHRINK_FACTOR, current.sizeFactor() * SHRINK_FACTOR), true);
	}

	// ---- geometry -----------------------------------------------------------------------

	/** The camera box for one override, the same math the compositor draws with. */
	private static Rect box(WebcamSpec spec, int stageWidth, int stageHeight, Override override,
			double zoomScale, int webcamWidth, int webcamHeight) {
		double margin = WebcamOverlay.scalePixels(spec.margin(), stageWidth);
		double widthPercent = (spec.width() > 0 ? spec.width() : spec.size()) * override.sizeFactor();
		double heightPercent = (spec.height() > 0 ? spec.height() : spec.size())
				* override.sizeFactor();
		if (webcamWidth > 0 && webcamHeight > 0) {
			heightPercent = WebcamOverlay.cropMatchedHeightPercent(widthPercent, heightPercent,
					(double) webcamWidth, (double) webcamHeight, spec.cropRegion());
		}
		WebcamOverlay.Size size = WebcamOverlay.dimensionsPx(stageWidth, stageHeight, widthPercent,
				heightPercent, margin, zoomScale, spec.reactToZoom());
		WebcamOverlay.Position position = WebcamOverlay.position(stageWidth, stageHeight,
				size.width(), size.height(), margin, "custom", override.positionX(),
				override.positionY(), spec.corner());
		return Rect.makeXYWH((float) position.x(), (float) position.y(), (float) size.width(),
				(float) size.height());
	}

	/** The override that reproduces the layout placement (the fraction pair the spec authors). */
	private static Override layoutOverride(WebcamSpec spec, int stageWidth, int stageHeight) {
		double margin = WebcamOverlay.scalePixels(spec.margin(), stageWidth);
		double widthPercent = spec.width() > 0 ? spec.width() : spec.size();
		double heightPercent = spec.height() > 0 ? spec.height() : spec.size();
		WebcamOverlay.Size size = WebcamOverlay.dimensionsPx(stageWidth, stageHeight, widthPercent,
				heightPercent, margin, 1, spec.reactToZoom());
		WebcamOverlay.Position position = WebcamOverlay.position(stageWidth, stageHeight,
				size.width(), size.height(), margin, spec.positionPreset(), spec.positionX(),
				spec.positionY(), spec.corner());
		double availableWidth = Math.max(0, stageWidth - size.width() - margin * 2);
		double availableHeight = Math.max(0, stageHeight - size.height() - margin * 2);
		return new Override(availableWidth > 0 ? (position.x() - margin) / availableWidth : 0.5,
				availableHeight > 0 ? (position.y() - margin) / availableHeight : 0.5, 1, true);
	}

	/** The video rect inside the output (padding + crop applied), the camera transform's base. */
	private static Rect baseMask(LayoutSpec layout, int videoWidth, int videoHeight,
			int stageWidth, int stageHeight) {
		if (videoWidth <= 0 || videoHeight <= 0) {
			return Rect.makeWH(stageWidth, stageHeight);
		}
		PaddedLayout padded = LayoutUtils.computePaddedLayout(stageWidth, stageHeight,
				layout.padding(), null, layout.crop(), videoWidth, videoHeight);
		return Rect.makeXYWH((float) padded.centerOffsetX(), (float) padded.centerOffsetY(),
				(float) padded.croppedDisplayWidth(), (float) padded.croppedDisplayHeight());
	}

	private static boolean anyInside(List<double[]> points, Rect box, double pad) {
		for (double[] point : points) {
			if (point[0] >= box._left - pad && point[0] <= box.getRight() + pad
					&& point[1] >= box._top - pad && point[1] <= box.getBottom() + pad) {
				return true;
			}
		}
		return false;
	}

	private static boolean nearly(Override a, Override b) {
		return Math.abs(a.positionX() - b.positionX()) < 1e-4
				&& Math.abs(a.positionY() - b.positionY()) < 1e-4
				&& Math.abs(a.sizeFactor() - b.sizeFactor()) < 1e-4;
	}

	private static double[] centreOf(Rect box) {
		return new double[] {box._left + box.getWidth() / 2, box._top + box.getHeight() / 2};
	}

	/** The camera override in force at {@code startMs}, mid-glide included. */
	private static Override resolvedAt(List<Move> moves, double startMs, Override base) {
		Override resolved = Override.NONE;
		for (Move move : moves) {
			if (move.startMs() > startMs) {
				break;
			}
			double progress = Math.max(0, Math.min(1, (startMs - move.startMs()) / MOVE_MS));
			double eased = progress * progress * (3 - 2 * progress);
			resolved = move.from().lerp(move.to(), eased);
		}
		return resolved.isBase(base) ? base : resolved;
	}

	private static double lastMoveStart(List<Move> moves) {
		return moves.isEmpty() ? 0 : moves.get(moves.size() - 1).startMs();
	}

	// ---- walk schedule ------------------------------------------------------------------

	/**
	 * The times the walk evaluates: cursor telemetry decimated to {@link #SAMPLE_STEP_MS} (mapped
	 * to timeline time) plus a uniform {@link #WALK_STEP_MS} grid for the zoom focus, ascending
	 * and deduplicated.
	 */
	private static List<Double> walkSteps(List<CursorSample> sorted, List<ClipRegion> clips,
			long timelineDurationMs) {
		java.util.TreeSet<Double> times = new java.util.TreeSet<>();
		double lastSampleMs = Double.NEGATIVE_INFINITY;
		for (CursorSample sample : sorted) {
			double timeMs = Clips.mapSourceTimeToTimelineTime(sample.timeSeconds() * 1000, clips);
			if (timeMs - lastSampleMs >= SAMPLE_STEP_MS) {
				times.add(timeMs);
				lastSampleMs = timeMs;
			}
		}
		for (long grid = 0; grid <= timelineDurationMs; grid += WALK_STEP_MS) {
			times.add((double) grid);
		}
		return List.copyOf(times);
	}

	/** The timeline span the walk covers: the clips' span, or the source duration. */
	private static long timelineDurationMs(List<ClipRegion> clips, double sourceDurationSeconds,
			List<CursorSample> sorted) {
		long fromClips = clips.isEmpty()
				? 0
				: clips.stream().mapToLong(ClipRegion::endMs).max().orElse(0);
		if (fromClips > 0) {
			return fromClips;
		}
		if (sourceDurationSeconds > 0) {
			return Math.round(sourceDurationSeconds * 1000);
		}
		double lastSampleSeconds = sorted.isEmpty()
				? 0
				: sorted.get(sorted.size() - 1).timeSeconds();
		return Math.round(lastSampleSeconds * 1000);
	}
}
