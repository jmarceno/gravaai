package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import dev.gravaai.core.json.Json;

/**
 * Parse/clamp/merge helpers for typed source-audio settings (plan P2.7, Appendix A
 * {@code sourceAudioTrackSettingsByClip}). The raw {@link ObjectNode} remains canonical: parsing
 * clamps into a typed view, and {@link #merge} writes back only the known {@code volume}/
 * {@code normalize} fields while preserving every unknown key (clip ids, track ids and any extra
 * fields) so project files round-trip unchanged.
 */
public final class SourceAudioSettings {

	/** TS {@code SOURCE_AUDIO_NORMALIZE_GAIN} used by the exporter resolver. */
	public static final double NORMALIZE_GAIN = 1.35;
	/** Volume range accepted by the resolver. */
	public static final double MIN_VOLUME = 0.0;
	public static final double MAX_VOLUME = 2.0;

	private SourceAudioSettings() {}

	/** {@code {volume: 1, normalize: false}} for a clip without stored settings. */
	public static SourceAudioTrackSettings defaults() {
		return SourceAudioTrackSettings.EMPTY;
	}

	/** Parses one clip's raw settings; invalid entries fall back to the defaults. */
	public static SourceAudioTrackSettings parse(JsonNode rawClipSettings) {
		if (rawClipSettings == null || !rawClipSettings.isObject()) {
			return SourceAudioTrackSettings.EMPTY;
		}
		Map<String, SourceAudioTrackSettings.Track> tracks = new LinkedHashMap<>();
		rawClipSettings.propertyNames().forEach(trackId -> {
			JsonNode rawTrack = rawClipSettings.get(trackId);
			if (rawTrack == null || !rawTrack.isObject()) {
				return;
			}
			double volume = SourceAudioTrackSettings.Track.DEFAULT.volume();
			if (rawTrack.has("volume") && rawTrack.get("volume").isNumber()) {
				volume = clampVolume(rawTrack.get("volume").doubleValue());
			}
			boolean normalize = rawTrack.path("normalize").isBoolean()
					&& rawTrack.get("normalize").booleanValue();
			tracks.put(trackId, new SourceAudioTrackSettings.Track(volume, normalize));
		});
		return new SourceAudioTrackSettings(tracks);
	}

	/** Parses the whole {@code sourceAudioTrackSettingsByClip} map. */
	public static Map<String, SourceAudioTrackSettings> parseAll(@Nullable ObjectNode byClip) {
		Map<String, SourceAudioTrackSettings> parsed = new LinkedHashMap<>();
		if (byClip == null) {
			return parsed;
		}
		byClip.propertyNames().forEach(clipId -> {
			JsonNode raw = byClip.get(clipId);
			parsed.put(clipId, parse(raw));
		});
		return parsed;
	}

	/**
	 * Writes the typed settings for one clip into a copy of the raw map, preserving unknown keys at
	 * both the clip and track level (only {@code volume}/{@code normalize} are replaced).
	 */
	public static ObjectNode merge(ObjectNode rawByClip, String clipId,
			SourceAudioTrackSettings settings) {
		ObjectNode next = rawByClip == null
				? Json.nodeFactory().objectNode() : rawByClip.deepCopy();
		if (clipId == null) {
			return next;
		}
		ObjectNode rawClip = next.path(clipId).isObject()
				? (ObjectNode) next.get(clipId) : Json.nodeFactory().objectNode();
		if (settings != null) {
			for (Map.Entry<String, SourceAudioTrackSettings.Track> entry
					: settings.tracks().entrySet()) {
				String trackId = entry.getKey();
				SourceAudioTrackSettings.Track track = entry.getValue();
				ObjectNode rawTrack = rawClip.path(trackId).isObject()
						? (ObjectNode) rawClip.get(trackId) : Json.nodeFactory().objectNode();
				rawTrack.put("volume", clampVolume(track.volume()));
				rawTrack.put("normalize", track.normalize());
				rawClip.set(trackId, rawTrack);
			}
		}
		next.set(clipId, rawClip);
		return next;
	}

	/** Resolver gain for one track id (exporter behaviour: {@code volume * normalizeGain}). */
	public static double resolveGain(SourceAudioTrackSettings settings, String trackId) {
		if (settings == null) {
			return 1.0;
		}
		SourceAudioTrackSettings.Track track = settings.track(trackId);
		if (track == null) {
			return 1.0;
		}
		double normalizeGain = track.normalize() ? NORMALIZE_GAIN : 1.0;
		return Math.max(0.0, Math.min(2.0, track.volume() * normalizeGain));
	}

	/** Clamps a volume value into the resolver's accepted range. */
	public static double clampVolume(double volume) {
		if (!Double.isFinite(volume)) {
			return SourceAudioTrackSettings.Track.DEFAULT.volume();
		}
		return Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, volume));
	}
}
