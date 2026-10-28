package dev.gravaai.ui.dashboard;

import dev.gravaai.ui.kit.Label;
import javafx.scene.layout.VBox;

/**
 * Settings → Permissions page (port of {@code RecordingPermissionsSettings.tsx}, plan P3.4).
 *
 * <p>The React page is a macOS permission report (Screen Recording / Accessibility status read
 * through the Electron bridge). The Java app targets Linux/COSMIC, where capture permission is
 * granted by the desktop portal at pick time: React renders exactly this static Linux variant
 * ({@code status && !status.mac}). The macOS status rows are therefore ⛔ N/A by platform.
 */
public final class RecordingPermissionsSettings extends VBox {

	/** {@code Choose what to capture when you start a recording.} */
	public static final String CAPTURE_HINT =
			"Choose what to capture when you start a recording.";
	/** Microphone and camera row texts. */
	public static final String MEDIA_TITLE = "Microphone and camera";
	public static final String MEDIA_DESCRIPTION =
			"Access is requested when you enable a microphone or camera for recording.";
	public static final String MEDIA_STATUS = "Requested when needed";

	public RecordingPermissionsSettings() {
		super(24);
		getStyleClass().add("recording-permissions");

		Label hint = new Label(CAPTURE_HINT);
		hint.setWrapText(true);
		hint.getStyleClass().add("recording-permissions__hint");
		Label mediaStatus = new Label(MEDIA_STATUS);
		mediaStatus.getStyleClass().add("recording-permissions__status");
		getChildren().addAll(hint, new SettingsRow(MEDIA_TITLE, MEDIA_DESCRIPTION, mediaStatus, true));
	}
}
