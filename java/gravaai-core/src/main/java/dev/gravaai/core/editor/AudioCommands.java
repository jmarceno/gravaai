package dev.gravaai.core.editor;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.AudioRegion;

/** The {@code useAudioRegionCommands} operations, one class per command. */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class AudioCommands {

	private AudioCommands() {}

	private static List<String> ids(List<AudioRegion> regions) {
		List<String> ids = new ArrayList<>(regions.size());
		for (AudioRegion region : regions) {
			ids.add(region.id());
		}
		return ids;
	}

	/** {@code handleSelectAudio}. */
	public static final class Select implements EditorCommand {
		private final String id;

		public Select(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setSelectedAudioId(id);
			if (id != null) {
				store.clearSelectionsExcept(null, null, null, id, null);
				store.setActiveEffectSection(EditorStore.SECTION_AUDIO);
			}
			return true;
		}
	}

	/** {@code handleAudioAdded}. */
	public static final class Add implements EditorCommand {
		private final double startMs;
		private final double endMs;
		private final String audioPath;
		private final Integer trackIndex;

		public Add(double startMs, double endMs, String audioPath, Integer trackIndex) {
			this.startMs = startMs;
			this.endMs = endMs;
			this.audioPath = audioPath;
			this.trackIndex = trackIndex;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AudioRegion> regions = store.state().audioRegions();
			String id = store.nextId("audio", ids(regions));
			AudioRegion region = new AudioRegion(id, Math.round(startMs), Math.round(endMs),
					audioPath, 1, false, trackIndex);
			List<AudioRegion> next = new ArrayList<>(regions);
			next.add(region);
			store.setAudioRegions(next);
			store.clearSelectionsExcept(null, null, null, id, null);
			store.setActiveEffectSection(EditorStore.SECTION_AUDIO);
			return true;
		}
	}

	/** {@code handleAudioSpanChange}. */
	public static final class ChangeSpan implements EditorCommand {
		private final String id;
		private final double startMs;
		private final double endMs;
		private final Integer trackIndex;

		public ChangeSpan(String id, double startMs, double endMs, Integer trackIndex) {
			this.id = id;
			this.startMs = startMs;
			this.endMs = endMs;
			this.trackIndex = trackIndex;
		}

		@Override
		public boolean apply(EditorStore store) {
			int normalizedTrackIndex = trackIndex != null && Double.isFinite(trackIndex)
					? Math.max(0, (int) Math.floor(trackIndex)) : -1;
			List<AudioRegion> next = new ArrayList<>();
			for (AudioRegion region : store.state().audioRegions()) {
				next.add(region.id().equals(id)
						? new AudioRegion(region.id(), Math.round(startMs), Math.round(endMs),
								region.audioPath(), region.volume(), region.normalize(),
								normalizedTrackIndex >= 0 ? normalizedTrackIndex
										: region.trackIndex())
						: region);
			}
			store.setAudioRegions(next);
			return true;
		}
	}

	/** {@code handleAudioVolumeChange}: clamps into 0..1 on the selected region. */
	public static final class ChangeVolume implements EditorCommand {
		private final double volume;

		public ChangeVolume(double volume) {
			this.volume = volume;
		}

		@Override
		public boolean apply(EditorStore store) {
			String selectedAudioId = store.selectedAudioId();
			if (selectedAudioId == null || !Double.isFinite(volume)) {
				return false;
			}
			double nextVolume = Math.max(0, Math.min(1, volume));
			List<AudioRegion> next = new ArrayList<>();
			for (AudioRegion region : store.state().audioRegions()) {
				next.add(region.id().equals(selectedAudioId)
						? new AudioRegion(region.id(), region.startMs(), region.endMs(),
								region.audioPath(), nextVolume, region.normalize(),
								region.trackIndex())
						: region);
			}
			store.setAudioRegions(next);
			return true;
		}
	}

	/** {@code handleAudioDelete}. */
	public static final class Delete implements EditorCommand {
		private final String id;

		public Delete(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<AudioRegion> next = new ArrayList<>();
			for (AudioRegion region : store.state().audioRegions()) {
				if (!region.id().equals(id)) {
					next.add(region);
				}
			}
			store.setAudioRegions(next);
			if (id.equals(store.selectedAudioId())) {
				store.setSelectedAudioId(null);
			}
			return true;
		}
	}

	/** {@code handleAudioNormalizeChange}. */
	public static final class SetNormalize implements EditorCommand {
		private final boolean normalize;

		public SetNormalize(boolean normalize) {
			this.normalize = normalize;
		}

		@Override
		public boolean apply(EditorStore store) {
			String selectedAudioId = store.selectedAudioId();
			if (selectedAudioId == null) {
				return false;
			}
			List<AudioRegion> next = new ArrayList<>();
			for (AudioRegion region : store.state().audioRegions()) {
				next.add(region.id().equals(selectedAudioId)
						? new AudioRegion(region.id(), region.startMs(), region.endMs(),
								region.audioPath(), region.volume(), normalize, region.trackIndex())
						: region);
			}
			store.setAudioRegions(next);
			return true;
		}
	}
}
