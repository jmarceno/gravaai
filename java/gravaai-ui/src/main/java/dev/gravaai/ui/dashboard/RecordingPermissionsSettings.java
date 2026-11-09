package dev.gravaai.ui.dashboard;

import dev.gravaai.ui.i18n.UiI18n;
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

	public RecordingPermissionsSettings() {
		super(24);
		getStyleClass().add("recording-permissions");

		Label hint = new Label(UiI18n.t("dashboard:settings.permissions.captureHint"));
		hint.setWrapText(true);
		hint.getStyleClass().add("recording-permissions__hint");
		Label mediaStatus = new Label(UiI18n.t("dashboard:settings.permissions.mediaStatus"));
		mediaStatus.getStyleClass().add("recording-permissions__status");
		getChildren().addAll(hint, new SettingsRow(
				UiI18n.t("dashboard:settings.permissions.mediaTitle"),
				UiI18n.t("dashboard:settings.permissions.mediaDescription"), mediaStatus, true));
	}
}
