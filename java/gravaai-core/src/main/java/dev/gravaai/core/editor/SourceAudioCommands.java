package dev.gravaai.core.editor;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AudioCleanup;
import dev.gravaai.core.project.SourceAudioSettings;
import dev.gravaai.core.project.SourceAudioTrackSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The {@code useSourceAudioTrackSettings} operations (plan P2.7 source-audio lanes). */
public final class SourceAudioCommands {

	private SourceAudioCommands() {}

	/** Sets one clip track's {@code volume}/{@code normalize}, preserving unknown raw keys. */
	public static final class SetTrackSettings implements EditorCommand {
		private final String clipId;
		private final String trackId;
		private final double volume;
		private final boolean normalize;

		public SetTrackSettings(String clipId, String trackId, double volume, boolean normalize) {
			this.clipId = clipId;
			this.trackId = trackId;
			this.volume = volume;
			this.normalize = normalize;
		}

		public SetTrackSettings(String clipId, String trackId, SourceAudioTrackSettings.Track track) {
			this(clipId, trackId, track == null ? 1.0 : track.volume(),
					track != null && track.normalize());
		}

		@Override
		public boolean apply(EditorStore store) {
			if (clipId == null || trackId == null || trackId.isBlank()) {
				return false;
			}
			ObjectNode raw = store.state().sourceAudioTrackSettingsByClip();
			// No map yet is the same as an empty map for parse/merge.
			ObjectNode byClip = raw == null ? Json.nodeFactory().objectNode() : raw;
			JsonNode clipSettings = byClip.get(clipId);
			SourceAudioTrackSettings current = clipSettings == null
					? SourceAudioTrackSettings.EMPTY
					: SourceAudioSettings.parse(clipSettings);
			// Keep the track's cleanup when only the level changes.
			AudioCleanup cleanup = current.trackOrDefault(trackId).cleanup();
			SourceAudioTrackSettings next = current.withTrack(trackId,
					new SourceAudioTrackSettings.Track(SourceAudioSettings.clampVolume(volume),
							normalize, cleanup));
			store.setSourceAudioTrackSettingsByClip(SourceAudioSettings.merge(byClip, clipId, next));
			return true;
		}
	}

	/**
	 * Sets one source track's cleanup in the project's {@code defaultSourceAudioTrackSettings} (the
	 * node the exporter resolves per-track gains and cleanup from). Track ids are {@code mic},
	 * {@code system} or {@code mixed}; unknown extra keys of the track entry are preserved.
	 */
	public static final class SetDefaultTrackCleanup implements EditorCommand {
		private final String trackId;
		private final AudioCleanup cleanup;

		public SetDefaultTrackCleanup(String trackId, AudioCleanup cleanup) {
			this.trackId = trackId;
			this.cleanup = cleanup == null ? AudioCleanup.NONE : cleanup;
		}

		@Override
		public boolean apply(EditorStore store) {
			if (trackId == null || trackId.isBlank()) {
				return false;
			}
			store.setDefaultSourceAudioTrackSettings(SourceAudioSettings.mergeDefaultCleanup(
					store.state().defaultSourceAudioTrackSettings(), trackId, cleanup));
			store.setActiveEffectSection(EditorStore.SECTION_AUDIO);
			return true;
		}
	}
}
