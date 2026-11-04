package dev.gravaai.core.project;

/**
 * Key-press sound settings: one sound for every recorded character key press. Off by default,
 * like {@link ClickSoundSettings}.
 */
public record KeySoundSettings(boolean enabled, KeySoundStyle sound, double volume) {

	public static final KeySoundSettings DEFAULT = new KeySoundSettings(
			EditorDefaults.DEFAULT_KEY_SOUND_ENABLED,
			EditorDefaults.DEFAULT_KEY_SOUND_STYLE,
			EditorDefaults.DEFAULT_KEY_SOUND_VOLUME);
}
