package dev.gravaai.services.recording;

import java.io.IOException;
import java.util.List;

import dev.gravaai.media.audio.KeySoundHits;

/** Reads a recording's key-press sidecar as {@link KeySoundHits} planner input. */
public final class KeystrokePresses {

	private KeystrokePresses() {
	}

	/**
	 * The recording's presses; empty without a readable sidecar. A press made with Ctrl, Alt or
	 * Super held is a shortcut (Shift alone still types a character).
	 */
	public static List<KeySoundHits.Press> load(String videoPath) {
		try {
			return KeystrokeTelemetry.readKeystrokeTelemetry(videoPath).stream()
					.map(event -> new KeySoundHits.Press(event.timeMs(), event.key(),
							event.modifiers().stream().anyMatch(
									modifier -> modifier != KeystrokeTypes.KeyModifier.SHIFT)))
					.toList();
		} catch (IOException | RuntimeException error) {
			return List.of();
		}
	}
}
