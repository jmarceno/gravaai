package dev.gravaai.ui.dashboard;

import java.util.function.DoubleFunction;

import dev.gravaai.core.editor.EditorPreferences;
import dev.gravaai.core.editor.EditorPreferencesStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.ui.i18n.UiI18n;
import dev.gravaai.ui.kit.Select;
import dev.gravaai.ui.kit.Slider;
import dev.gravaai.ui.kit.Switch;
import javafx.scene.layout.VBox;
import tools.jackson.databind.node.ObjectNode;

/**
 * Settings → General and Settings → Motion: the editor defaults that new projects start from
 * ({@code gravaai.editor.preferences}, the same store the editor writes when you change a look).
 * Each control saves a one-field patch, so the next new project picks it up. General also carries
 * the app language picker: choosing a language persists it in {@code app-settings.json} and the
 * app rebuilds the translated surfaces ({@code UiI18n} listeners).
 */
public final class EditorDefaultsSettingsView extends VBox {

	private final EditorPreferencesStore store;

	private EditorDefaultsSettingsView(EditorPreferencesStore store) {
		super(24);
		this.store = store;
		getStyleClass().add("editor-defaults-settings");
	}

	/** General: the interface language plus what a fresh recording gets automatically. */
	public static EditorDefaultsSettingsView general(EditorPreferencesStore store) {
		EditorDefaultsSettingsView view = new EditorDefaultsSettingsView(store);
		EditorPreferences prefs = store.loadPreferences();
		view.getChildren().addAll(
				view.languageRow(),
				view.toggle(UiI18n.t("dashboard:settings.general.autoZoomsTitle"),
						UiI18n.t("dashboard:settings.general.autoZoomsDescription"),
						"autoApplyFreshRecordingAutoZooms", prefs.autoApplyFreshRecordingAutoZooms()),
				view.toggle(UiI18n.t("dashboard:settings.general.showCursorTitle"),
						UiI18n.t("dashboard:settings.general.showCursorDescription"),
						"showCursor", prefs.showCursor()));
		return view;
	}

	/** Motion: zoom timing and cursor movement defaults. */
	public static EditorDefaultsSettingsView motion(EditorPreferencesStore store) {
		EditorDefaultsSettingsView view = new EditorDefaultsSettingsView(store);
		EditorPreferences prefs = store.loadPreferences();
		DoubleFunction<String> ms = value -> Math.round(value) + " ms";
		DoubleFunction<String> percent = value -> Math.round(value * 100) + "%";
		view.getChildren().addAll(
				view.toggle(UiI18n.t("dashboard:settings.motion.connectZoomsTitle"),
						UiI18n.t("dashboard:settings.motion.connectZoomsDescription"),
						"connectZooms", prefs.connectZooms()),
				view.slider(UiI18n.t("dashboard:settings.motion.zoomInDurationTitle"),
						UiI18n.t("dashboard:settings.motion.zoomInDurationDescription"),
						"zoomInDurationMs", prefs.zoomInDurationMs(), 50, 2000, 50, ms),
				view.slider(UiI18n.t("dashboard:settings.motion.zoomOutDurationTitle"),
						UiI18n.t("dashboard:settings.motion.zoomOutDurationDescription"),
						"zoomOutDurationMs", prefs.zoomOutDurationMs(), 50, 2000, 50, ms),
				view.slider(UiI18n.t("dashboard:settings.motion.zoomMotionBlurTitle"),
						UiI18n.t("dashboard:settings.motion.zoomMotionBlurDescription"),
						"zoomMotionBlur", prefs.zoomMotionBlur(), 0, 1, 0.05, percent),
				view.slider(UiI18n.t("dashboard:settings.motion.cursorSmoothingTitle"),
						UiI18n.t("dashboard:settings.motion.cursorSmoothingDescription"),
						"cursorSmoothing", prefs.cursorSmoothing(), 0, 1, 0.01, percent),
				view.slider(UiI18n.t("dashboard:settings.motion.cursorMotionBlurTitle"),
						UiI18n.t("dashboard:settings.motion.cursorMotionBlurDescription"),
						"cursorMotionBlur", prefs.cursorMotionBlur(), 0, 1, 0.05, percent),
				view.slider(UiI18n.t("dashboard:settings.motion.cursorSwayTitle"),
						UiI18n.t("dashboard:settings.motion.cursorSwayDescription"),
						"cursorSway", prefs.cursorSway(), 0, 1, 0.05, percent));
		return view;
	}

	/**
	 * The app language picker (default: the system locale). Selecting an entry persists it and
	 * notifies the {@code UiI18n} listeners, which rebuild the translated surfaces.
	 */
	private SettingsRow languageRow() {
		Select language = new Select();
		for (UiI18n.LanguageOption option : UiI18n.languageOptions()) {
			language.item(option.value(), option.label());
		}
		language.value(UiI18n.selected());
		language.onValueChange(UiI18n::select);
		language.setAccessibleText(UiI18n.t("common:app.language"));
		return new SettingsRow(UiI18n.t("common:app.language"),
				UiI18n.t("dashboard:settings.general.languageDescription"), language, true);
	}

	private SettingsRow toggle(String title, String description, String key, boolean checked) {
		Switch control = new Switch("", checked);
		control.setAccessibleText(title);
		control.checkedProperty().addListener((observable, was, now) -> {
			ObjectNode patch = Json.nodeFactory().objectNode();
			patch.put(key, now);
			store.savePreferences(patch);
		});
		return new SettingsRow(title, description, control);
	}

	private SettingsRow slider(String title, String description, String key, double value,
			double min, double max, double step, DoubleFunction<String> format) {
		Slider control = new Slider().range(min, max).step(step).formatter(format);
		control.setValue(value);
		control.setPrefWidth(260);
		control.setMinWidth(260);
		control.setAccessibleText(title);
		control.onValueCommit(committed -> {
			ObjectNode patch = Json.nodeFactory().objectNode();
			patch.put(key, committed);
			store.savePreferences(patch);
		});
		return new SettingsRow(title, description, control);
	}
}
