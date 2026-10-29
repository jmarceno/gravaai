package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import java.io.IOException;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.services.recording.KeystrokeCapture;
import dev.gravaai.services.recording.KeystrokeCaptureMode;
import dev.gravaai.services.recording.MicNoiseReduction;
import dev.gravaai.services.recording.MicNoiseReductionFilters;
import dev.gravaai.ui.kit.ChoiceGroup;
import dev.gravaai.ui.kit.Toast;
import javafx.scene.layout.VBox;
import tools.jackson.databind.JsonNode;

/**
 * Settings → Recording page (port of the {@code category="recording"} block of
 * {@code DashboardSettings.tsx}): "Microphone noise reduction" (Linux) and "Record key presses".
 *
 * <p>Both rows write the same app settings the recorder reads when a recording starts
 * ({@code keystrokeCaptureMode}, {@code micNoiseReduction}), so a change applies to the next
 * recording. Key presses are what the editor's "Key presses" overlay draws; with capture off no
 * {@code .keys.json} sidecar is written and the overlay has nothing to show. The HUD
 * capture-protection row is not rendered: React shows it only where
 * {@code supportsHudCaptureProtection(platform)}, which is false on Linux.
 */
public final class RecordingSettingsView extends VBox {

	public static final String KEYS_TITLE = "Record key presses";
	public static final String KEYS_DESCRIPTION = "Shortcuts only skips normal typing. Key presses are"
			+ " saved only next to the recording. Letters follow the US keyboard layout.";
	public static final String MIC_TITLE = "Microphone noise reduction";
	public static final String MIC_DESCRIPTION = "Cleans background noise from your microphone in new"
			+ " recordings. System audio is not changed. Strong can make voices sound slightly processed.";

	private final AppSettingsStore settings;
	private final ChoiceGroup keyMode = new ChoiceGroup();
	private final ChoiceGroup micNoise = new ChoiceGroup();
	private boolean loading;

	public RecordingSettingsView(AppSettingsStore settings) {
		super(24);
		this.settings = settings;
		getStyleClass().add("recording-settings");

		micNoise.setAccessibleText(MIC_TITLE);
		micNoise.item(MicNoiseReduction.OFF.wire(), "Off");
		micNoise.item(MicNoiseReduction.LIGHT.wire(), "Light");
		micNoise.item(MicNoiseReduction.BALANCED.wire(), "Balanced");
		micNoise.item(MicNoiseReduction.STRONG.wire(), "Strong");

		keyMode.setAccessibleText(KEYS_TITLE);
		keyMode.item(KeystrokeCaptureMode.OFF.wire(), "Off");
		keyMode.item(KeystrokeCaptureMode.SHORTCUTS.wire(), "Shortcuts only");
		keyMode.item(KeystrokeCaptureMode.ALL.wire(), "All keys");

		load();
		micNoise.onValueChange(level -> save(MicNoiseReductionFilters.MIC_NOISE_REDUCTION_SETTING, level,
				"Could not update microphone noise reduction"));
		keyMode.onValueChange(mode -> save(KeystrokeCapture.KEYSTROKE_CAPTURE_MODE_SETTING, mode,
				"Could not update key press capture"));

		getChildren().addAll(
				new SettingsRow(MIC_TITLE, MIC_DESCRIPTION, micNoise, true),
				new SettingsRow(KEYS_TITLE, KEYS_DESCRIPTION, keyMode, true));
	}

	/** The selected key-press capture mode ({@code off}, {@code shortcuts}, {@code all}). */
	public String keyMode() {
		return keyMode.value();
	}

	/** Selects a key-press capture mode as a click would (tests, automation). */
	public void selectKeyMode(String mode) {
		keyMode.setValue(mode);
	}

	/** The selected microphone noise-reduction level. */
	public @Nullable String micNoise() {
		return micNoise.value();
	}

	private void load() {
		loading = true;
		try {
			keyMode.setValue(KeystrokeCapture.normalizeKeystrokeCaptureMode(
					stringSetting(KeystrokeCapture.KEYSTROKE_CAPTURE_MODE_SETTING)).wire());
			micNoise.setValue(MicNoiseReductionFilters.normalizeMicNoiseReduction(
					stringSetting(MicNoiseReductionFilters.MIC_NOISE_REDUCTION_SETTING)).wire());
		} finally {
			loading = false;
		}
	}

	private @Nullable String stringSetting(String key) {
		try {
			JsonNode value = settings.readSetting(key);
			return value != null && value.isString() ? value.asString() : null;
		} catch (RuntimeException error) {
			return null;
		}
	}

	private void save(String key, String value, String failure) {
		if (loading || value == null) {
			return;
		}
		try {
			settings.writeSetting(key, Json.nodeFactory().stringNode(value));
		} catch (IOException | RuntimeException error) {
			if (getScene() != null) {
				Toast.show(this, failure).variant(Toast.Variant.DANGER);
			}
			load();
		}
	}
}
