package dev.gravaai.ui.editor;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Left tools rail of the editor (port of {@code EditorSidebar.tsx}, restyled to the Instrument
 * mock's {@code .rail}): one button per inspector section, icon over a tiny label, hairline
 * dividers between groups, Settings pinned at the foot.
 */
public final class EditorSidebarRail extends VBox {

	/** Editor inspector sections, in the React rail's visual order. */
	public enum Section {
		SCENE("Scene", "Scene", Icons.FrameCorners),
		COLOR("Color & luminosity", "Color", Icons.Palette),
		CURSOR("Cursor", "Cursor", Icons.Cursor),
		KEYSTROKES("Keystrokes", "Keys", Icons.Keyboard),
		WEBCAM("Webcam", "Webcam", Icons.Camera),
		CAPTIONS("Captions", "Captions", Icons.ClosedCaptioning),
		TRANSCRIPT("Transcript editing", "Transcript", Icons.TextT),
		MEETING("Transcript & notes", "Notes", Icons.DocumentText),
		SETTINGS("Settings", "Settings", Icons.Gear);

		private final String label;
		private final String railLabel;
		private final String icon;

		Section(String label, String railLabel, String icon) {
			this.label = label;
			this.railLabel = railLabel;
			this.icon = icon;
		}

		public String label() {
			return label;
		}

		/** The kit icon key for the rail button and the inspector tab. */
		public String icon() {
			return icon;
		}

		/** The short label under the rail icon ({@code span} in the mock's rail button). */
		public String railLabel() {
			return railLabel;
		}
	}

	private final Map<Section, Button> buttons = new EnumMap<>(Section.class);
	private Consumer<Section> onSelect = section -> { };
	private Section active = Section.SCENE;

	public EditorSidebarRail() {
		getStyleClass().add("editor-rail");
		for (Section section : Section.values()) {
			Button button = railButton(section);
			buttons.put(section, button);
			if (section == Section.SETTINGS) {
				// The mock pins Settings at the rail foot, below a hairline gap.
				getChildren().add(footSpacer());
				getChildren().add(button);
			} else {
				getChildren().add(button);
				if (section == Section.KEYSTROKES) {
					getChildren().add(gap());
				}
			}
		}
		select(Section.SCENE);
	}

	private Button railButton(Section section) {
		Button button = new Button(section.railLabel, Button.Variant.GHOST, Button.Size.SM);
		button.icon(Icon.of(section.icon, 15));
		button.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
		button.setGraphicTextGap(3);
		button.setOnAction(event -> select(section));
		dev.gravaai.ui.kit.Tooltip.install(button, section.label);
		return button;
	}

	/** Hairline divider between rail groups (mock {@code .rail .gap}). */
	private static Region gap() {
		Region gap = new Region();
		gap.getStyleClass().add("editor-rail__gap");
		return gap;
	}

	/** Flexible spacer that pins the foot button to the rail bottom (mock {@code .foot}). */
	private static Region footSpacer() {
		Region spacer = new Region();
		VBox.setVgrow(spacer, Priority.ALWAYS);
		return spacer;
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
			Button button = buttons.get(active);
			if (button != null) {
				button.getStyleClass().add("is-active");
			}
		}
	}
}
