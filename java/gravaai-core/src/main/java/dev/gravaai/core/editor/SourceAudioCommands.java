package dev.gravaai.core.editor;

import dev.gravaai.core.project.SourceAudioSettings;
import dev.gravaai.core.project.SourceAudioTrackSettings;
import tools.jackson.databind.node.ObjectNode;

/** The {@code useSourceAudioTrackSettings} operations (plan P2.7 source-audio lanes). */
public final class SourceAudioCommands {

	private SourceAudioCommands() {}

	/** Sets one track's {@code volume}/{@code normalize} for a clip, preserving unknown raw keys. */
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
			SourceAudioTrackSettings current = SourceAudioSettings.parse(
					raw == null ? null : raw.get(clipId));
			SourceAudioTrackSettings next = current.withTrack(trackId,
					new SourceAudioTrackSettings.Track(SourceAudioSettings.clampVolume(volume),
							normalize));
			store.setSourceAudioTrackSettingsByClip(SourceAudioSettings.merge(raw, clipId, next));
			return true;
		}
	}
}
