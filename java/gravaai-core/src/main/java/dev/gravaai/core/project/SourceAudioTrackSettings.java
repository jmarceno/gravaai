package dev.gravaai.core.project;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed source-audio settings for one clip (TS {@code SourceAudioTrackSettings} in
 * {@code src/components/video-editor/audio/audioTypes.ts}): a map from source track id to its gain
 * setting. Known ids are {@link #MIXED}, {@link #SYSTEM} and {@link #MIC}; the TS type also allows
 * custom string ids, so the map is kept open. The raw JSON stays canonical in
 * {@link ProjectEditorState#sourceAudioTrackSettingsByClip()}; this type is parsed on demand and
 * written back through {@link SourceAudioSettings#merge}.
 */
public record SourceAudioTrackSettings(Map<String, Track> tracks) {

	public static final String MIXED = "mixed";
	public static final String SYSTEM = "system";
	public static final String MIC = "mic";

	/** One {@code {volume, normalize}} entry. */
	public record Track(double volume, boolean normalize) {

		public static final Track DEFAULT = new Track(1.0, false);
	}

	public static final SourceAudioTrackSettings EMPTY =
			new SourceAudioTrackSettings(Map.of());

	public SourceAudioTrackSettings {
		tracks = tracks == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(tracks));
	}

	/** The setting for {@code trackId}, or {@code null} when the clip has no entry for it. */
	public Track track(String trackId) {
		return trackId == null ? null : tracks.get(trackId);
	}

	/** The setting for {@code trackId}, or the default ({@code volume 1, normalize false}). */
	public Track trackOrDefault(String trackId) {
		Track setting = track(trackId);
		return setting == null ? Track.DEFAULT : setting;
	}

	public SourceAudioTrackSettings withTrack(String trackId, Track setting) {
		Map<String, Track> next = new LinkedHashMap<>(tracks);
		next.put(trackId, setting == null ? Track.DEFAULT : setting);
		return new SourceAudioTrackSettings(next);
	}

	public boolean isEmpty() {
		return tracks.isEmpty();
	}
}
