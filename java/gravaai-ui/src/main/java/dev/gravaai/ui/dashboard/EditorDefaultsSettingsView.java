package dev.gravaai.ui.dashboard;

import java.util.function.DoubleFunction;

import dev.gravaai.core.editor.EditorPreferences;
import dev.gravaai.core.editor.EditorPreferencesStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.ui.kit.Slider;
import dev.gravaai.ui.kit.Switch;
import javafx.scene.layout.VBox;
import tools.jackson.databind.node.ObjectNode;

/**
 * Settings → General and Settings → Motion: the editor defaults that new projects start from
 * ({@code gravaai.editor.preferences}, the same store the editor writes when you change a look).
 * Each control saves a one-field patch, so the next new project picks it up.
 */
public final class EditorDefaultsSettingsView extends VBox {

	private final EditorPreferencesStore store;

	private EditorDefaultsSettingsView(EditorPreferencesStore store) {
		super(24);
		this.store = store;
		getStyleClass().add("editor-defaults-settings");
	}

	/** General: what a fresh recording gets automatically. */
	public static EditorDefaultsSettingsView general(EditorPreferencesStore store) {
		EditorDefaultsSettingsView view = new EditorDefaultsSettingsView(store);
		EditorPreferences prefs = store.loadPreferences();
		view.getChildren().addAll(
				view.toggle("Auto-apply zooms to new recordings",
						"Adds zooms on clicks and typing when a fresh recording opens in the editor.",
						"autoApplyFreshRecordingAutoZooms", prefs.autoApplyFreshRecordingAutoZooms()),
				view.toggle("Show the cursor",
						"New projects draw the recorded cursor over the video.",
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
				view.toggle("Connect nearby zooms",
						"Zooms close together glide from one to the next instead of zooming out between them.",
						"connectZooms", prefs.connectZooms()),
				view.slider("Zoom in duration", "How long a zoom takes to move in.",
						"zoomInDurationMs", prefs.zoomInDurationMs(), 50, 2000, 50, ms),
				view.slider("Zoom out duration", "How long a zoom takes to move back out.",
						"zoomOutDurationMs", prefs.zoomOutDurationMs(), 50, 2000, 50, ms),
				view.slider("Zoom motion blur", "Blur while the camera zooms.",
						"zoomMotionBlur", prefs.zoomMotionBlur(), 0, 1, 0.05, percent),
				view.slider("Cursor smoothing", "Smooths shaky cursor movement.",
						"cursorSmoothing", prefs.cursorSmoothing(), 0, 1, 0.01, percent),
				view.slider("Cursor motion blur", "Blur on fast cursor movement.",
						"cursorMotionBlur", prefs.cursorMotionBlur(), 0, 1, 0.05, percent),
				view.slider("Cursor sway", "How much the cursor tilts as it moves.",
						"cursorSway", prefs.cursorSway(), 0, 1, 0.05, percent));
		return view;
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
