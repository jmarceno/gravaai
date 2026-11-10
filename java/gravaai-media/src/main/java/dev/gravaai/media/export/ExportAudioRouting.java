package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.project.AudioCleanup;
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

		/**
		 * The key a kind resolves its settings under. Embedded audio is the project's mixed lane
		 * (the timeline shows the recording's own audio as the mixed track when there are no
		 * dedicated sidecars), so its cleanup, gain and loudness target come from the same
		 * {@code mixed} entry the preview and the inspector use — never from a settings entry the
		 * editor cannot write.
		 */
		public String settingsTrackId() {
			return this == EMBEDDED ? MIXED.sourceTrackId() : sourceTrackId();
		}
	}

	/** A {@code SourceAudioTrackSetting}: gain plus the edit-time {@link AudioCleanup}. */
	public record TrackSetting(double volume, boolean normalize, AudioCleanup cleanup) {

		public static final TrackSetting DEFAULT = new TrackSetting(1, false, AudioCleanup.NONE);

		/** A setting without cleanup: the shape every pre-cleanup project file and call site has. */
		public TrackSetting(double volume, boolean normalize) {
			this(volume, normalize, AudioCleanup.NONE);
		}

		public TrackSetting {
			cleanup = cleanup == null ? AudioCleanup.NONE : cleanup;
		}
	}

	/** {@code ResolvedAudioTrack}; every resolved track has an audio file. */
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

	/** {@code resolveSourceAudioFallbackPaths}; never {@code null}. */
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

	/**
	 * {@code normalizeSourceAudioFallbackPath}.
	 *
	 * @return the normalized path, or {@code null} when the input has no usable path (blank)
	 */
	public static @Nullable String normalizeFallbackPath(String resourceOrPath) {
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
		TrackSetting setting = settings == null ? null : settings.get(kind.settingsTrackId());
		if (setting == null) {
			return 1;
		}
		double normalizeGain = setting.normalize() ? SOURCE_AUDIO_NORMALIZE_GAIN : 1;
		return clamp(setting.volume() * normalizeGain, MAX_TRACK_GAIN);
	}

	/**
	 * The edit-time cleanup of one source track; a track without a setting is left untouched.
	 * Cleanup is a per-track decision like {@link #resolveSourceTrackGain}, so it is resolved from
	 * the same settings map (under {@link TrackKind#settingsTrackId()}) and travels with the
	 * resolved track into the graph.
	 */
	public static AudioCleanup resolveSourceTrackCleanup(Map<String, TrackSetting> settings,
			TrackKind kind) {
		TrackSetting setting = settings == null ? null : settings.get(kind.settingsTrackId());
		return setting == null ? AudioCleanup.NONE : setting.cleanup();
	}

	/** {@code clampGain}. */
	public static double clampGain(@Nullable Double value, double max) {
		if (value == null || !Double.isFinite(value)) {
			return 1;
		}
		return Math.max(0, Math.min(max, value));
	}

	private static double clamp(double value, double max) {
		return Math.max(0, Math.min(max, value));
	}

	/**
	 * {@code buildResolvedAudioPlan}.
	 *
	 * @param embeddedGain the explicit embedded-audio gain, or null to use the track setting
	 * @param masterGain the explicit master gain, or null to use the default
	 */
	public static ResolvedPlan buildResolvedPlan(String videoPath, List<String> sourceAudioFallbackPaths,
			List<AudioRegion> audioRegions, Map<String, TrackSetting> sourceAudioTrackSettings,
			@Nullable Double embeddedGain, @Nullable Double masterGain) {
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
			// Embedded audio is the mixed lane: the explicit embedded gain wins when a caller
			// passes one, otherwise the mixed setting (volume and normalize) applies exactly as
			// it does for a mixed sidecar.
			double gain = embeddedGain != null
					? embeddedGain
					: resolveSourceTrackGain(sourceAudioTrackSettings, TrackKind.EMBEDDED);
			tracks.add(new ResolvedTrack("embedded:" + videoPath, TrackKind.EMBEDDED, videoPath,
					Math.max(0, Math.min(MAX_TRACK_GAIN, gain)), 0, Double.POSITIVE_INFINITY));
		}
		return new ResolvedPlan(fallback.hasEmbeddedSourceAudio(), pathsByTrack, playbackPaths,
				hasDedicatedTracks && !includeEmbeddedInExport, includeEmbeddedInExport, tracks,
				clampGain(masterGain, 1));
	}
}
