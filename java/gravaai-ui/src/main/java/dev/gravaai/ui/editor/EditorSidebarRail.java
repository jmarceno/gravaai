package dev.gravaai.ui.editor;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import javafx.scene.layout.VBox;

/**
 * Left icon rail of the editor (port of {@code EditorSidebar.tsx}): one ghost icon button per
 * inspector section, the active one highlighted.
 */
public final class EditorSidebarRail extends VBox {

	/** Editor inspector sections, in the React rail's visual order. */
	public enum Section {
		SCENE("Scene", Icons.FrameCorners),
		CURSOR("Cursor", Icons.Cursor),
		WEBCAM("Webcam", Icons.Camera),
		CAPTIONS("Captions", Icons.ClosedCaptioning),
		TRANSCRIPT("Transcript editing", Icons.TextT),
		MEETING("Transcript & notes", Icons.DocumentText),
		SETTINGS("Settings", Icons.Gear);

		private final String label;
		private final String icon;

		Section(String label, String icon) {
			this.label = label;
			this.icon = icon;
		}

		public String label() {
			return label;
		}
	}

	private final Map<Section, Button> buttons = new EnumMap<>(Section.class);
	private Consumer<Section> onSelect = section -> { };
	private Section active = Section.SCENE;

	public EditorSidebarRail() {
		getStyleClass().add("editor-rail");
		for (Section section : Section.values()) {
			Button button = new Button("", Button.Variant.GHOST, Button.Size.ICON);
			button.icon(Icon.of(section.icon, 18));
			button.setOnAction(event -> select(section));
			dev.gravaai.ui.kit.Tooltip.install(button, section.label);
			buttons.put(section, button);
			getChildren().add(button);
		}
		select(Section.SCENE);
	}

	public void onSelect(Consumer<Section> listener) {
		this.onSelect = listener == null ? section -> { } : listener;
	}

	public Section active() {
		return active;
	}

	/** Selects a section and notifies the listener. */
	public void select(Section section) {
		this.active = section;
		for (Map.Entry<Section, Button> entry : buttons.entrySet()) {
			boolean selected = entry.getKey() == section;
			entry.getValue().getStyleClass().remove("is-active");
			if (selected) {
				entry.getValue().getStyleClass().add("is-active");
			}
		}
		onSelect.accept(section);
	}

	/**
	 * Shows no active section while the Clips panel replaces the inspector
	 * ({@code selectedKeys={panelContent ? [] : [activeSection]}}); restores it on close.
	 */
	public void setPanelOpen(boolean open) {
		for (Button button : buttons.values()) {
			button.getStyleClass().remove("is-active");
		}
		if (!open) {
			buttons.get(active).getStyleClass().add("is-active");
		}
	}
}
