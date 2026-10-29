package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

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

	/** @return the duration in seconds, or null when it is unknown */
	@Nullable Double probeDurationSec(Path filePath) throws Exception;
}
