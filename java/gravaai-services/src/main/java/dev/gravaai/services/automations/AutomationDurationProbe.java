package dev.gravaai.services.automations;

import java.nio.file.Path;

/**
 * Container-duration probe used by duration conditions (plan §5.15: "The duration probe uses
 * {@code MediaProbe}"). The app adapts {@code dev.gravaai.media.ffmpeg.MediaProbe} to this
 * interface.
 *
 * <p>It must work for audio-only containers (.m4a meetings), i.e. it cannot require a video
 * stream. Returns null when the duration is unknown and throws when the file cannot be read.
 */
@FunctionalInterface
public interface AutomationDurationProbe {

	Double probeDurationSec(Path filePath) throws Exception;
}
