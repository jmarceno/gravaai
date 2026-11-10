package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.services.recording.KeystrokeCapture;
import dev.gravaai.services.recording.KeystrokeCaptureMode;
import dev.gravaai.services.recording.MicNoiseReduction;
import dev.gravaai.services.recording.MicNoiseReductionFilters;
import dev.gravaai.services.recording.RecordingFrameRate;
import dev.gravaai.services.recording.RecordingQuality;
import dev.gravaai.ui.i18n.UiI18n;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.ChoiceGroup;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Toast;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import tools.jackson.databind.JsonNode;

/**
 * Settings → Recording page (port of the {@code category="recording"} block of
 * {@code DashboardSettings.tsx}): "Microphone noise reduction" (Linux), "Record key presses",
 * plus the local card "Recording settings: fps, quality and save location" — frame rate
 * (30/60 fps), video quality (Standard / High / Lossless-ish) and the recordings folder.
 *
 * <p>The rows write the same app settings the recorder reads when a recording starts
 * ({@code keystrokeCaptureMode}, {@code micNoiseReduction}, {@code captureFps},
 * {@code videoQuality}), so a change applies to the next recording on every desktop backend. The
 * folder is written straight into {@code recordings-settings.json}; the recorder, the library and
 * the thumbnails follow it on their next lookup.
 */
public final class RecordingSettingsView extends VBox {

	private final AppSettingsStore settings;
	private final RecordingsDirectory recordingsDirectory;
	private final ChoiceGroup keyMode = new ChoiceGroup();
	private final ChoiceGroup micNoise = new ChoiceGroup();
	private final ChoiceGroup frameRate = new ChoiceGroup();
	private final ChoiceGroup quality = new ChoiceGroup();
	private final Label folderValue = new Label();
	private boolean loading;

	public RecordingSettingsView(AppSettingsStore settings, RecordingsDirectory recordingsDirectory) {
		super(24);
		this.settings = settings;
		this.recordingsDirectory = recordingsDirectory;
		getStyleClass().add("recording-settings");

		String micTitle = UiI18n.t("dashboard:settings.recording.micTitle");
		String keysTitle = UiI18n.t("dashboard:settings.recording.keysTitle");
		String fpsTitle = UiI18n.t("dashboard:settings.recording.fpsTitle");
		String qualityTitle = UiI18n.t("dashboard:settings.recording.qualityTitle");
		String folderTitle = UiI18n.t("dashboard:settings.recording.folderTitle");
		micNoise.setAccessibleText(micTitle);
		micNoise.item(MicNoiseReduction.OFF.wire(), UiI18n.t("dashboard:settings.recording.off"));
		micNoise.item(MicNoiseReduction.LIGHT.wire(), UiI18n.t("dashboard:settings.recording.light"));
		micNoise.item(MicNoiseReduction.BALANCED.wire(), UiI18n.t("dashboard:settings.recording.balanced"));
		micNoise.item(MicNoiseReduction.STRONG.wire(), UiI18n.t("dashboard:settings.recording.strong"));

		keyMode.setAccessibleText(keysTitle);
		keyMode.item(KeystrokeCaptureMode.OFF.wire(), UiI18n.t("dashboard:settings.recording.off"));
		keyMode.item(KeystrokeCaptureMode.SHORTCUTS.wire(),
				UiI18n.t("dashboard:settings.recording.shortcutsOnly"));
		keyMode.item(KeystrokeCaptureMode.ALL.wire(), UiI18n.t("dashboard:settings.recording.allKeys"));

		frameRate.setAccessibleText(fpsTitle);
		frameRate.item(RecordingFrameRate.FPS_30.wire(), UiI18n.t("dashboard:settings.recording.fps30"));
		frameRate.item(RecordingFrameRate.FPS_60.wire(), UiI18n.t("dashboard:settings.recording.fps60"));

		quality.setAccessibleText(qualityTitle);
		quality.item(RecordingQuality.STANDARD.wire(),
				UiI18n.t("dashboard:settings.recording.qualityStandard"));
		quality.item(RecordingQuality.HIGH.wire(), UiI18n.t("dashboard:settings.recording.qualityHigh"));
		quality.item(RecordingQuality.LOSSLESS.wire(),
				UiI18n.t("dashboard:settings.recording.qualityLossless"));

		load();
		micNoise.onValueChange(level -> save(MicNoiseReductionFilters.MIC_NOISE_REDUCTION_SETTING, level,
				UiI18n.t("dashboard:settings.recording.micSaveFailed")));
		keyMode.onValueChange(mode -> save(KeystrokeCapture.KEYSTROKE_CAPTURE_MODE_SETTING, mode,
				UiI18n.t("dashboard:settings.recording.keysSaveFailed")));
		frameRate.onValueChange(rate -> save(RecordingFrameRate.CAPTURE_FPS_SETTING, rate,
				UiI18n.t("dashboard:settings.recording.fpsSaveFailed")));
		quality.onValueChange(value -> save(RecordingQuality.VIDEO_QUALITY_SETTING, value,
				UiI18n.t("dashboard:settings.recording.qualitySaveFailed")));

		getChildren().addAll(
				new SettingsRow(micTitle, UiI18n.t("dashboard:settings.recording.micDescription"),
						micNoise, true),
				new SettingsRow(keysTitle, UiI18n.t("dashboard:settings.recording.keysDescription"),
						keyMode, true),
				new SettingsRow(fpsTitle, UiI18n.t("dashboard:settings.recording.fpsDescription"),
						frameRate, true),
				new SettingsRow(qualityTitle, UiI18n.t("dashboard:settings.recording.qualityDescription"),
						quality, true),
				new SettingsRow(folderTitle, UiI18n.t("dashboard:settings.recording.folderDescription"),
						folderControls(), true));
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

	/** The selected capture frame rate ({@code 30}, {@code 60}). */
	public String frameRate() {
		return frameRate.value();
	}

	/** Selects a capture frame rate as a click would (tests, automation). */
	public void selectFrameRate(String rate) {
		frameRate.setValue(rate);
	}

	/** The selected video quality ({@code standard}, {@code high}, {@code lossless}). */
	public String quality() {
		return quality.value();
	}

	/** Selects a video quality as a click would (tests, automation). */
	public void selectQuality(String value) {
		quality.setValue(value);
	}

	/** The recordings folder shown in the folder row. */
	public String recordingsFolder() {
		return folderValue.getText();
	}

	private VBox folderControls() {
		folderValue.getStyleClass().add("recording-settings__folder");
		folderValue.setWrapText(true);
		refreshFolderValue();

		Button choose = new Button(UiI18n.t("dashboard:settings.recording.folderChoose"),
				Button.Variant.SECONDARY, Button.Size.SM);
		choose.setOnAction(event -> chooseFolder());
		Button reset = new Button(UiI18n.t("dashboard:settings.recording.folderReset"),
				Button.Variant.GHOST, Button.Size.SM);
		reset.setOnAction(event -> saveFolder(null));

		HBox controls = new HBox(8, choose, reset);
		controls.setAlignment(Pos.CENTER_LEFT);

		VBox column = new VBox(8, folderValue, controls);
		column.setAlignment(Pos.CENTER_LEFT);
		return column;
	}

	private void refreshFolderValue() {
		folderValue.setText(currentFolder().toString());
	}

	private Path currentFolder() {
		try {
			return recordingsDirectory.getRecordingsDir();
		} catch (IOException | RuntimeException error) {
			// The folder row still needs something to show; the picker falls back to home.
			return Path.of(System.getProperty("user.home"));
		}
	}

	private void chooseFolder() {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle(UiI18n.t("dashboard:settings.recording.folderTitle"));
		Path current = currentFolder();
		if (Files.isDirectory(current)) {
			chooser.setInitialDirectory(current.toFile());
		}
		javafx.stage.Window window = getScene() == null ? null : getScene().getWindow();
		java.io.File chosen = chooser.showDialog(window);
		if (chosen != null) {
			saveFolder(chosen.toPath());
		}
	}

	private void saveFolder(@Nullable Path folder) {
		try {
			recordingsDirectory.setCustomRecordingsDir(folder);
			refreshFolderValue();
		} catch (IOException | RuntimeException error) {
			if (getScene() != null) {
				Toast.show(this, UiI18n.t("dashboard:settings.recording.folderSaveFailed"))
						.variant(Toast.Variant.DANGER);
			}
		}
	}

	private void load() {
		loading = true;
		try {
			keyMode.setValue(KeystrokeCapture.normalizeKeystrokeCaptureMode(
					stringSetting(KeystrokeCapture.KEYSTROKE_CAPTURE_MODE_SETTING)).wire());
			micNoise.setValue(MicNoiseReductionFilters.normalizeMicNoiseReduction(
					stringSetting(MicNoiseReductionFilters.MIC_NOISE_REDUCTION_SETTING)).wire());
			frameRate.setValue(RecordingFrameRate.normalize(
					stringSetting(RecordingFrameRate.CAPTURE_FPS_SETTING)).wire());
			quality.setValue(RecordingQuality.normalize(
					stringSetting(RecordingQuality.VIDEO_QUALITY_SETTING)).wire());
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
