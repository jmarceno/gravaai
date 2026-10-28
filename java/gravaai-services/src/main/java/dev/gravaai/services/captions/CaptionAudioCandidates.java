package dev.gravaai.services.captions;

import java.util.ArrayList;
import java.util.List;

/** Port of electron/ipc/captions/audioCandidates.ts. */
public final class CaptionAudioCandidates {

	private CaptionAudioCandidates() {
	}

	/** Companion audio sidecars in priority order: microphone, then system. */
	public static List<CaptionAudioCandidate> getCaptionCompanionAudioCandidates(
			List<CompanionAudioCandidate> companions) {
		if (companions == null) {
			return List.of();
		}
		List<CaptionAudioCandidate> candidates = new ArrayList<>();
		for (CompanionAudioCandidate companion : companions) {
			if (companion.usablePaths().contains(companion.micPath())) {
				candidates.add(new CaptionAudioCandidate(
						companion.micPath(), "microphone audio sidecar"));
			}
			if (companion.usablePaths().contains(companion.systemPath())) {
				candidates.add(new CaptionAudioCandidate(
						companion.systemPath(), "system audio sidecar"));
			}
		}
		return List.copyOf(candidates);
	}
}
