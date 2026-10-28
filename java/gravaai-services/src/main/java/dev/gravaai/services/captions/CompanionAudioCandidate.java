package dev.gravaai.services.captions;

import java.util.List;

/** A recording session's usable audio sidecars ({@code CompanionAudioCandidate} in types.ts). */
public record CompanionAudioCandidate(
		String platform,
		String systemPath,
		String micPath,
		List<String> usablePaths) {

	public CompanionAudioCandidate {
		usablePaths = usablePaths == null ? List.of() : List.copyOf(usablePaths);
	}
}
