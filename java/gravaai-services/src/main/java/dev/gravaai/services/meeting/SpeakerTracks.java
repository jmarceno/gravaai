package dev.gravaai.services.meeting;

import java.nio.file.Path;

/**
 * The per-speaker sidecar convention Gravaai keeps next to a meeting recording:
 * {@code recording-<ts>.meeting-mic.opus} and {@code recording-<ts>.meeting-system.opus}.
 *
 * <p>The one-line helper lives in the recording service's cosmic.ts in Electron. The recording
 * teammate owns that package; until it exports the helper this small copy is used and the
 * duplication is listed in the phase report (plan §8.5: no behaviour change).
 */
public final class SpeakerTracks {

	private SpeakerTracks() {
	}

	/** Replaces the last extension of {@code outputPath} with {@code .meeting-<role>.opus}. */
	public static Path speakerTrackPath(Path outputPath, SpeakerRole role) {
		String value = outputPath.toString();
		int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
		int dot = value.lastIndexOf('.');
		String base = dot > slash ? value.substring(0, dot) : value;
		return Path.of(base + ".meeting-" + role.wire() + ".opus");
	}
}
