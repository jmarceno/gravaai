package dev.gravaai.services.captions;

/** An audio source the caption generator may transcribe. */
public record CaptionAudioCandidate(String path, String label) {

	public CaptionAudioCandidate {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("path must not be blank");
		}
		if (label == null) {
			throw new IllegalArgumentException("label must not be null");
		}
	}
}
