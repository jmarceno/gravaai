package dev.gravaai.media.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.project.AudioRegion;

/**
 * Source-audio routing decisions (port of {@code audioRoutingEngine.ts},
 * {@code sourceTrackRoutingPolicy.ts}, {@code sourceAudioFallback.ts} and
 * {@code audioProcessorShared.ts}, plan §5.10). These are pure decisions shared by the export audio
 * graph builder.
 */
public final class ExportAudioRouting {

	/** {@code SOURCE_AUDIO_NORMALIZE_GAIN}. */
	public static final double SOURCE_AUDIO_NORMALIZE_GAIN = 1.35;

	/** {@code MinimumAudioTrackGain} / system-track clamp. */
	public static final double MAX_TRACK_GAIN = 2;

	/** Track kinds of the resolved plan. */
	public enum TrackKind {
		USER("user"),
		SYSTEM("system"),
		MIC("mic"),
		MIXED("mixed"),
		EMBEDDED("embedded");

		private final String wire;

		TrackKind(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		/** The source-track id used by the recording layout / settings map. */
		public String sourceTrackId() {
			return switch (this) {
				case SYSTEM -> "system";
				case MIC -> "mic";
				case MIXED -> "mixed";
				default -> wire;
			};
		}
	}

	/** A {@code SourceAudioTrackSetting}. */
	public record TrackSetting(double volume, boolean normalize) {
		public static final TrackSetting DEFAULT = new TrackSetting(1, false);
	}

	/** {@code ResolvedAudioTrack}. */
	public record ResolvedTrack(String id, TrackKind kind, String path, double gain,
			double timelineStartMs, double timelineEndMs) {}

	/** {@code ResolvedAudioPlan}. */
	public record ResolvedPlan(boolean hasEmbeddedSourceAudio, Map<String, String> pathsByTrack,
			List<String> playbackPaths, boolean muteEmbeddedPreview,
			boolean includeEmbeddedInExport, List<ResolvedTrack> tracks, double masterGain) {
		public ResolvedPlan {
			pathsByTrack = Map.copyOf(pathsByTrack);
			playbackPaths = List.copyOf(playbackPaths);
			tracks = List.copyOf(tracks);
		}
	}

	/** {@code resolveSourceAudioFallbackPaths} result. */
	public record FallbackPaths(boolean hasEmbeddedSourceAudio, List<String> externalAudioPaths) {}

	private ExportAudioRouting() {}

	/** {@code resolveSourceAudioFallbackPaths}. */
	public static FallbackPaths resolveSourceAudioFallbackPaths(String videoSource,
			List<String> sourceAudioFallbackPaths) {
		List<String[]> normalized = new ArrayList<>();
		if (sourceAudioFallbackPaths != null) {
			for (String audioPath : sourceAudioFallbackPaths) {
				if (audioPath == null || audioPath.trim().isEmpty()) {
					continue;
				}
				normalized.add(new String[] {audioPath, normalizeFallbackPath(audioPath)});
			}
		}
		String localVideoSourcePath = videoSource == null || videoSource.isBlank()
				? null
				: normalizeFallbackPath(videoSource);
		boolean hasEmbeddedSourceAudio = localVideoSourcePath != null && normalized.stream()
				.anyMatch(entry -> localVideoSourcePath.equals(entry[1]));
		List<String> externalAudioPaths = new ArrayList<>();
		for (String[] entry : normalized) {
			if (!hasEmbeddedSourceAudio || !entry[1].equals(localVideoSourcePath)) {
				externalAudioPaths.add(entry[0]);
			}
		}
		return new FallbackPaths(hasEmbeddedSourceAudio, List.copyOf(externalAudioPaths));
	}

	/** {@code normalizeSourceAudioFallbackPath}. */
	public static String normalizeFallbackPath(String resourceOrPath) {
		if (resourceOrPath == null) {
			return null;
		}
		String resolved = localFilePath(resourceOrPath).trim();
		if (resolved.isEmpty()) {
			return null;
		}
		if (isWindowsPath(resolved)) {
			return resolved.replace('/', '\\').toLowerCase(java.util.Locale.ROOT);
		}
		return resolved.replace('\\', '/');
	}

	private static boolean isWindowsPath(String value) {
		if (value.length() >= 3 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':') {
			char separator = value.charAt(2);
			if (separator == '/' || separator == '\\') {
				return true;
			}
		}
		return value.startsWith("\\\\");
	}

	/** {@code getLocalFilePathFromResource} for the file-URL forms the app uses. */
	private static String localFilePath(String resourceOrPath) {
		String value = resourceOrPath.trim();
		if (!value.regionMatches(true, 0, "file://", 0, 7)) {
			return value;
		}
		String path = value.substring(7);
		// file:///tmp/x -> /tmp/x; file:///C:/Users/... -> C:/Users/...
		if (path.length() > 2 && path.charAt(0) == '/' && Character.isLetter(path.charAt(1))
				&& path.charAt(2) == ':') {
			return path.substring(1);
		}
		return path;
	}

	/** {@code getSourceTrackIdFromPath}. */
	public static TrackKind sourceTrackIdFromPath(String audioPath) {
		String normalized = audioPath == null ? "" : audioPath.toLowerCase(java.util.Locale.ROOT);
		String filename = normalized;
		int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
		if (slash >= 0) {
			filename = normalized.substring(slash + 1);
		}
		if (normalized.contains(".mic.") || normalized.contains("-mic.")
				|| normalized.contains("_mic_") || normalized.contains("/mic.")
				|| normalized.contains("\\mic.") || matchesTrackSuffix(filename, "mic")) {
			return TrackKind.MIC;
		}
		if (normalized.contains(".system.") || normalized.contains("-system.")
				|| normalized.contains("_system_") || normalized.contains("/system.")
				|| normalized.contains("\\system.")
				|| matchesTrackSuffix(filename, "system")) {
			return TrackKind.SYSTEM;
		}
		return TrackKind.MIXED;
	}

	/** {@code /(?:^|[._-])mic\.(?:mp4|m4a|wav)$/} without regex escaping traps. */
	private static boolean matchesTrackSuffix(String filename, String name) {
		for (String extension : new String[] {"mp4", "m4a", "wav"}) {
			String suffix = name + "." + extension;
			if (!filename.endsWith(suffix)) {
				continue;
			}
			int index = filename.length() - suffix.length();
			if (index == 0) {
				return true;
			}
			char before = filename.charAt(index - 1);
			if (before == '.' || before == '_' || before == '-') {
				return true;
			}
		}
		return false;
	}

	/** {@code resolveSourceTrackGain}. */
	public static double resolveSourceTrackGain(Map<String, TrackSetting> settings, TrackKind kind) {
		TrackSetting setting = settings == null ? null : settings.get(kind.sourceTrackId());
		if (setting == null) {
			return 1;
		}
		double normalizeGain = setting.normalize() ? SOURCE_AUDIO_NORMALIZE_GAIN : 1;
		return clamp(setting.volume() * normalizeGain, MAX_TRACK_GAIN);
	}

	/** {@code clampGain}. */
	public static double clampGain(Double value, double max) {
		if (value == null || !Double.isFinite(value)) {
			return 1;
		}
		return Math.max(0, Math.min(max, value));
	}

	private static double clamp(double value, double max) {
		return Math.max(0, Math.min(max, value));
	}

	/** {@code buildResolvedAudioPlan}. */
	public static ResolvedPlan buildResolvedPlan(String videoPath, List<String> sourceAudioFallbackPaths,
			List<AudioRegion> audioRegions, Map<String, TrackSetting> sourceAudioTrackSettings,
			Double embeddedGain, Double masterGain) {
		FallbackPaths fallback = resolveSourceAudioFallbackPaths(videoPath, sourceAudioFallbackPaths);
		Map<String, String> pathsByTrack = new LinkedHashMap<>();
		for (String path : fallback.externalAudioPaths()) {
			TrackKind kind = sourceTrackIdFromPath(path);
			pathsByTrack.putIfAbsent(kind.sourceTrackId(), path);
		}
		boolean hasDedicatedTracks = pathsByTrack.containsKey("system")
				|| pathsByTrack.containsKey("mic");
		List<String> playbackPaths = new ArrayList<>();
		if (pathsByTrack.containsKey("system")) {
			playbackPaths.add(pathsByTrack.get("system"));
		}
		if (pathsByTrack.containsKey("mic")) {
			playbackPaths.add(pathsByTrack.get("mic"));
		}
		if (!hasDedicatedTracks && pathsByTrack.containsKey("mixed")) {
			playbackPaths.add(pathsByTrack.get("mixed"));
		}
		boolean includeEmbeddedInExport = !pathsByTrack.containsKey("system")
				&& !pathsByTrack.containsKey("mixed");

		List<AudioRegion> sortedRegions = audioRegions == null
				? List.of()
				: audioRegions.stream().sorted(java.util.Comparator.comparingLong(AudioRegion::startMs))
						.toList();
		List<ResolvedTrack> tracks = new ArrayList<>();
		for (AudioRegion region : sortedRegions) {
			double normalizeGain = Boolean.TRUE.equals(region.normalize())
					? SOURCE_AUDIO_NORMALIZE_GAIN
					: 1;
			tracks.add(new ResolvedTrack("user:" + region.id(), TrackKind.USER,
					region.audioPath(), clamp(region.volume() * normalizeGain, 1),
					Math.max(0, region.startMs()), Math.max(0, region.endMs())));
		}
		for (String path : playbackPaths) {
			TrackKind kind = sourceTrackIdFromPath(path);
			tracks.add(new ResolvedTrack(kind.sourceTrackId() + ":" + path, kind, path,
					resolveSourceTrackGain(sourceAudioTrackSettings, kind), 0,
					Double.POSITIVE_INFINITY));
		}
		if (fallback.hasEmbeddedSourceAudio() && videoPath != null) {
			double gain = embeddedGain != null
					? embeddedGain
					: sourceAudioTrackSettings != null
							&& sourceAudioTrackSettings.containsKey("mixed")
									? sourceAudioTrackSettings.get("mixed").volume()
									: 1;
			tracks.add(new ResolvedTrack("embedded:" + videoPath, TrackKind.EMBEDDED, videoPath,
					Math.max(0, Math.min(MAX_TRACK_GAIN, gain)), 0, Double.POSITIVE_INFINITY));
		}
		return new ResolvedPlan(fallback.hasEmbeddedSourceAudio(), pathsByTrack, playbackPaths,
				hasDedicatedTracks && !includeEmbeddedInExport, includeEmbeddedInExport, tracks,
				clampGain(masterGain, 1));
	}
}
