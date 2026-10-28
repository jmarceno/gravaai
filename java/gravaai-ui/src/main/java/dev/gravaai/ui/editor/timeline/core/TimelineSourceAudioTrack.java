package dev.gravaai.ui.editor.timeline.core;

/**
 * One source-audio track rendered on the timeline (port of {@code SourceAudioTrackWithPeaks} in
 * {@code audio/audioTypes.ts}): a stable id ({@code mixed}/{@code system}/{@code mic}/custom), the
 * display label and the media path the waveform provider should read.
 */
public record TimelineSourceAudioTrack(String id, String label, String audioPath) {
}
