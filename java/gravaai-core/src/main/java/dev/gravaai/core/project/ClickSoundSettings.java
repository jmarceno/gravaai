package dev.gravaai.core.project;

/**
 * Click sound settings: one sound for every recorded click, like the visual click effect.
 * The default is off, matching {@code cursorClickEffect = NONE}.
 */
public record ClickSoundSettings(boolean enabled, ClickSoundStyle sound, double volume) {

	public static final ClickSoundSettings DEFAULT = new ClickSoundSettings(
			EditorDefaults.DEFAULT_CLICK_SOUND_ENABLED,
			EditorDefaults.DEFAULT_CLICK_SOUND_STYLE,
			EditorDefaults.DEFAULT_CLICK_SOUND_VOLUME);
}
