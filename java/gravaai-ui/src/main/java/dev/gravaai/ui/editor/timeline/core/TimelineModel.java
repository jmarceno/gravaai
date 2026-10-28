package dev.gravaai.ui.editor.timeline.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationType;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionTimeline;
import dev.gravaai.core.project.ClipCameraLayout;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipSpeedChanges;
import dev.gravaai.core.project.ClipTransitions;
import dev.gravaai.core.project.SourceAudioTrackSettings;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomMode;
import dev.gravaai.core.project.ZoomRegion;

/**
 * Maps the editor state to timeline blocks (port of {@code timeline/model/timelineModel.ts}).
 *
 * <p>Pure: it reads {@code core.project} records and produces {@link TimelineRenderItem}s for the
 * Canvas renderer. The primary clip row is a compact sequence; regions keep their own rows.
 */
public final class TimelineModel {

	/** Port of the params object of {@code buildTimelineItems}. */
	public record ItemsRequest(
			List<ZoomRegion> zoomRegions,
			List<ClipRegion> clipRegions,
			List<AnnotationRegion> annotationRegions,
			List<AudioRegion> audioRegions,
			List<CaptionCue> captionCues,
			java.util.Map<String, SourceAudioTrackSettings> sourceAudioTrackSettings,
			List<TimelineSourceAudioTrack> sourceAudioTracks) {

		public ItemsRequest {
			zoomRegions = zoomRegions == null ? List.of() : List.copyOf(zoomRegions);
			clipRegions = clipRegions == null ? List.of() : List.copyOf(clipRegions);
			annotationRegions = annotationRegions == null ? List.of() : List.copyOf(annotationRegions);
			audioRegions = audioRegions == null ? List.of() : List.copyOf(audioRegions);
			captionCues = captionCues == null ? List.of() : List.copyOf(captionCues);
			sourceAudioTrackSettings = sourceAudioTrackSettings == null
					? java.util.Map.of() : java.util.Map.copyOf(sourceAudioTrackSettings);
			sourceAudioTracks = sourceAudioTracks == null ? List.of()
					: List.copyOf(sourceAudioTracks);
		}

		/** Backwards-compatible request without source-audio lanes (Phase A fixtures/tests). */
		public ItemsRequest(List<ZoomRegion> zoomRegions, List<ClipRegion> clipRegions,
				List<AnnotationRegion> annotationRegions, List<AudioRegion> audioRegions,
				List<CaptionCue> captionCues) {
			this(zoomRegions, clipRegions, annotationRegions, audioRegions, captionCues,
					java.util.Map.of(), List.of());
		}
	}

	/** Stable id of one source-audio block: {@code source-audio-<trackId>-<clipId>}. */
	public static String sourceAudioItemId(String trackId, String clipId) {
		return "source-audio-" + trackId + "-" + clipId;
	}

	/** Port of the params object of {@code buildAllRegionSpans}. */
	public record RegionSpansRequest(
			List<AnnotationRegion> annotationRegions,
			List<CaptionCue> captionCues,
			List<ZoomRegion> zoomRegions,
			List<ClipRegion> clipRegions,
			List<AudioRegion> audioRegions) {

		public RegionSpansRequest {
			annotationRegions = annotationRegions == null ? List.of() : List.copyOf(annotationRegions);
			captionCues = captionCues == null ? List.of() : List.copyOf(captionCues);
			zoomRegions = zoomRegions == null ? List.of() : List.copyOf(zoomRegions);
			clipRegions = clipRegions == null ? List.of() : List.copyOf(clipRegions);
			audioRegions = audioRegions == null ? List.of() : List.copyOf(audioRegions);
		}
	}

	private TimelineModel() {
	}

	/** Port of {@code getAnnotationLabel}. */
	public static String getAnnotationLabel(AnnotationRegion region) {
		if (region.type() == AnnotationType.TEXT) {
			String content = region.content() == null ? "" : region.content();
			String preview = content.trim().isEmpty() ? "Empty text" : content.trim();
			return preview.length() > 20 ? preview.substring(0, 20) + "..." : preview;
		}
		if (region.type() == AnnotationType.IMAGE) {
			return "Image";
		}
		return "Annotation";
	}

	/** Port of {@code getAudioLabel}: the file name without its extension. */
	public static String getAudioLabel(AudioRegion region) {
		String path = region.audioPath() == null ? "" : region.audioPath();
		int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		String name = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
		int lastDot = name.lastIndexOf('.');
		String base = lastDot > 0 ? name.substring(0, lastDot) : name;
		return base.isEmpty() ? "Audio" : base;
	}

	/** The zoom amount shown on timeline blocks and in the zoom inspector, e.g. {@code 1.8x}. */
	public static String formatZoomScale(double scale) {
		String amount = BigDecimal.valueOf(scale).stripTrailingZeros().toPlainString();
		return amount + "x";
	}

	private static String getCaptionLabel(CaptionCue cue) {
		String text = cue.text() == null ? "" : cue.text();
		String preview = text.trim().isEmpty() ? "Caption" : text.trim();
		return preview.length() > 24 ? preview.substring(0, 24) + "..." : preview;
	}

	/** Port of {@code buildTimelineItems}: zooms, clips, annotations, audios, captions. */
	public static List<TimelineRenderItem> buildTimelineItems(ItemsRequest request) {
		List<TimelineRenderItem> items = new ArrayList<>();

		for (int index = 0; index < request.zoomRegions().size(); index++) {
			ZoomRegion region = request.zoomRegions().get(index);
			ZoomDepth depth = region.depth() == null ? ZoomDepth.DEFAULT : region.depth();
			items.add(new TimelineRenderItem(region.id(), TimelineConstants.ZOOM_ROW_ID,
					new Span(region.startMs(), region.endMs()), null,
					formatZoomScale(depth.scale()),
					null, null, null, depth, region.mode() == null ? ZoomMode.AUTO : region.mode(),
					null, null, null, null, null, null, TimelineRenderItem.Variant.ZOOM));
		}

		List<ClipTransitions.FadeWindow> fadeWindows = ClipTransitions
				.buildFadeWindows(request.clipRegions());
		for (int index = 0; index < request.clipRegions().size(); index++) {
			ClipRegion region = request.clipRegions().get(index);
			double speed = Double.isFinite(region.speed()) && region.speed() > 0 ? region.speed() : 1;
			String speedLabel = ClipSpeedChanges.formatClipSpeedLabel(speed);
			ClipTransitions.FadeWindow fadeOut = null;
			ClipTransitions.FadeWindow fadeIn = null;
			for (ClipTransitions.FadeWindow window : fadeWindows) {
				if (window.peakMs() == region.endMs() && fadeOut == null) {
					fadeOut = window;
				}
				if (ClipTransitions.getFadeWindowHoldEnd(window) == region.startMs()
						&& window.endMs() > ClipTransitions.getFadeWindowHoldEnd(window)
						&& fadeIn == null) {
					fadeIn = window;
				}
			}
			ClipCameraLayout cameraLayout = region.cameraLayout() != null
					&& region.cameraLayout() != ClipCameraLayout.DEFAULT
							? region.cameraLayout()
							: null;
			items.add(new TimelineRenderItem(region.id(), TimelineConstants.CLIP_ROW_ID,
					new Span(region.startMs(), region.endMs()),
					new Span(Clips.getClipSourceStartMs(region), Clips.getClipSourceEndMs(region)),
					speedLabel != null ? "Clip " + (index + 1) + " " + speedLabel
							: "Clip " + (index + 1),
					null, null, null, null, null, speedLabel != null ? speed : null,
					region.showSourceAudio(), Boolean.TRUE.equals(region.muted()),
					fadeIn != null ? fadeIn.endMs() - ClipTransitions.getFadeWindowHoldEnd(fadeIn) : null,
					fadeOut != null ? fadeOut.peakMs() - fadeOut.startMs() : null,
					cameraLayout, TimelineRenderItem.Variant.CLIP));
		}

		for (AnnotationRegion region : request.annotationRegions()) {
			int trackIndex = region.trackIndex() == null ? 0 : region.trackIndex();
			items.add(new TimelineRenderItem(region.id(), TimelineRows.getAnnotationTrackRowId(trackIndex),
					new Span(region.startMs(), region.endMs()), null, getAnnotationLabel(region),
					null, null, null, null, null, null, null, null, null, null, null,
					TimelineRenderItem.Variant.ANNOTATION));
		}

		for (AudioRegion region : request.audioRegions()) {
			int trackIndex = region.trackIndex() == null ? 0 : region.trackIndex();
			items.add(new TimelineRenderItem(region.id(), TimelineRows.getAudioTrackRowId(trackIndex),
					new Span(region.startMs(), region.endMs()), null, getAudioLabel(region),
					region.audioPath(), region.volume(), Boolean.TRUE.equals(region.normalize()),
					null, null, null, null, null, null, null, null,
					TimelineRenderItem.Variant.AUDIO));
		}

		for (TimelineSourceAudioTrack track : request.sourceAudioTracks()) {
			for (ClipRegion clip : request.clipRegions()) {
				if (!Boolean.TRUE.equals(clip.showSourceAudio())) {
					continue;
				}
				SourceAudioTrackSettings settings = request.sourceAudioTrackSettings()
						.getOrDefault(clip.id(), SourceAudioTrackSettings.EMPTY);
				SourceAudioTrackSettings.Track trackSettings = settings
						.trackOrDefault(track.id());
				items.add(new TimelineRenderItem(sourceAudioItemId(track.id(), clip.id()),
						TimelineRows.getSourceAudioTrackRowId(track.id()),
						new Span(clip.startMs(), clip.endMs()),
						new Span(Clips.getClipSourceStartMs(clip),
								Clips.getClipSourceEndMs(clip)),
						track.label(), track.audioPath(),
						Math.max(0, Math.min(1, trackSettings.volume())),
						trackSettings.normalize(), null, null, null, null,
						Boolean.TRUE.equals(clip.muted()), null, null, null,
						TimelineRenderItem.Variant.AUDIO));
			}
		}

		for (CaptionTimeline.Fragment fragment : CaptionTimeline.projectCaptionCues(
				request.captionCues(), request.clipRegions())) {
			items.add(new TimelineRenderItem(fragment.id(), TimelineConstants.CAPTION_ROW_ID,
					new Span(fragment.startMs(), fragment.endMs()), null,
					getCaptionLabel(fragment.sourceCue()),
					null, null, null, null, null, null, null, null, null, null, null,
					TimelineRenderItem.Variant.CAPTION));
		}

		return List.copyOf(items);
	}

	/** Port of {@code buildAllRegionSpans}: row spans used by drag constraints. */
	public static List<TimelineRegionSpan> buildAllRegionSpans(RegionSpansRequest request) {
		List<TimelineRegionSpan> spans = new ArrayList<>();
		for (ZoomRegion region : request.zoomRegions()) {
			spans.add(new TimelineRegionSpan(region.id(), region.startMs(), region.endMs(),
					TimelineConstants.ZOOM_ROW_ID));
		}
		for (ClipRegion region : request.clipRegions()) {
			spans.add(new TimelineRegionSpan(region.id(), region.startMs(), region.endMs(),
					TimelineConstants.CLIP_ROW_ID));
		}
		for (AudioRegion region : request.audioRegions()) {
			int trackIndex = region.trackIndex() == null ? 0 : region.trackIndex();
			spans.add(new TimelineRegionSpan(region.id(), region.startMs(), region.endMs(),
					TimelineRows.getAudioTrackRowId(trackIndex)));
		}
		for (AnnotationRegion region : request.annotationRegions()) {
			int trackIndex = region.trackIndex() == null ? 0 : region.trackIndex();
			spans.add(new TimelineRegionSpan(region.id(), region.startMs(), region.endMs(),
					TimelineRows.getAnnotationTrackRowId(trackIndex)));
		}
		for (CaptionTimeline.Fragment fragment : CaptionTimeline.projectCaptionCues(
				request.captionCues(), request.clipRegions())) {
			spans.add(new TimelineRegionSpan(fragment.id(), fragment.startMs(), fragment.endMs(),
					TimelineConstants.CAPTION_ROW_ID));
		}
		return List.copyOf(spans);
	}

	/** Port of {@code resolveDropRowId}: items stay in their domain rows during dnd. */
	public static String resolveDropRowId(String id, String proposedRowId,
			List<TimelineRenderItem> timelineItems) {
		String currentRowId = null;
		for (TimelineRenderItem item : timelineItems) {
			if (item.id().equals(id)) {
				currentRowId = item.rowId();
				break;
			}
		}
		if (currentRowId == null) {
			return proposedRowId;
		}
		if (TimelineRows.isAnnotationTrackRowId(currentRowId)) {
			return TimelineRows.isAnnotationTrackRowId(proposedRowId)
					? TimelineRows.getAnnotationTrackRowId(
							TimelineRows.getAnnotationTrackIndex(proposedRowId))
					: currentRowId;
		}
		if (TimelineRows.isAudioTrackRowId(currentRowId)) {
			return TimelineRows.isAudioTrackRowId(proposedRowId)
					? TimelineRows.getAudioTrackRowId(TimelineRows.getAudioTrackIndex(proposedRowId))
					: currentRowId;
		}
		return currentRowId;
	}
}
