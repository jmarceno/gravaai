package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.gravaai.core.editor.AnnotationCommands;
import dev.gravaai.core.editor.AnnotationCommands.StylePatch;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.project.AnnotationPosition;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationSize;
import dev.gravaai.core.project.AnnotationTextStyle;
import dev.gravaai.core.project.AnnotationTextStyle.FontWeight;
import dev.gravaai.core.project.AnnotationType;
import dev.gravaai.core.project.ArrowDirection;
import dev.gravaai.core.project.FigureData;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.ChoiceGroup;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Select;
import dev.gravaai.ui.kit.Slider;
import dev.gravaai.ui.kit.Switch;
import dev.gravaai.ui.kit.TextArea;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Annotation appearance editor (port of {@code AnnotationSettingsPanel.tsx}, plan P2.6): the
 * text/figure/image/blur sections, font family/size/colour, background box, position presets and
 * size, with a custom-font hookup from {@link EditorViewActions#customFonts()}.
 *
 * <p>Every edit is an {@code AnnotationCommands} command on the shared {@link EditorStore}, so
 * undo/redo and project saving see the same state. Image annotations keep their content from the
 * annotation tool (there is no image-picker seam in the core model yet).
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class AnnotationSettingsPanel extends VBox {

	public record PositionPreset(String id, String label, double x, double y) {}

	private static final List<PositionPreset> POSITION_PRESETS = List.of(
			new PositionPreset("top-left", "Top left", 15, 15),
			new PositionPreset("top", "Top", 50, 15),
			new PositionPreset("top-right", "Top right", 85, 15),
			new PositionPreset("left", "Left", 15, 50),
			new PositionPreset("center", "Center", 50, 50),
			new PositionPreset("right", "Right", 85, 50),
			new PositionPreset("bottom-left", "Bottom left", 15, 85),
			new PositionPreset("bottom", "Bottom", 50, 85),
			new PositionPreset("bottom-right", "Bottom right", 85, 85));

	private static final List<String> BUILT_IN_FONTS = List.of("DM Sans", "Inter", "Roboto",
			"Open Sans", "Lato", "Montserrat", "Source Sans 3", "JetBrains Mono", "Space Grotesk");

	private static final List<String> COLOR_SWATCHES = List.of("#FFFFFF", "#000000", "#2563EB",
			"#EF4444", "#F59E0B", "#22C55E", "#A855F7", "#EC4899", "#14B8A6", "#6B7280");

	private final EditorStore store;
	private final EditorViewActions actions;
	private final VBox content = new VBox(18);

	public AnnotationSettingsPanel(EditorStore store, EditorViewActions actions) {
		this.store = store;
		this.actions = actions == null ? new EditorViewActions() { } : actions;
		setSpacing(12);
		setFillWidth(true);
		getChildren().add(content);
		refresh();
	}

	/** Rebuilds the controls for the selected annotation (or the empty state). */
	public void refresh() {
		content.getChildren().clear();
		AnnotationRegion region = selected();
		if (region == null) {
			Label empty = new Label("Select an annotation to edit its style.");
			empty.setWrapText(true);
			empty.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
			content.getChildren().add(empty);
			return;
		}

		content.getChildren().add(typeSection(region));
		content.getChildren().add(positionSection(region));
		content.getChildren().add(sizeSection(region));
		switch (region.type()) {
			case TEXT -> content.getChildren().add(textSection(region));
			case FIGURE -> content.getChildren().add(figureSection(region));
			case BLUR -> content.getChildren().add(blurSection(region));
			case IMAGE -> content.getChildren().add(imageSection(region));
		}
	}

	// ---- sections -------------------------------------------------------

	private Node typeSection(AnnotationRegion region) {
		ChoiceGroup tabs = new ChoiceGroup();
		tabs.item("text", "Text");
		tabs.item("figure", "Figure");
		tabs.item("image", "Image");
		tabs.item("blur", "Blur");
		tabs.setValue(region.type().wire());
		tabs.onValueChange(value -> {
			AnnotationType type = AnnotationType.fromWire(value);
			apply(new AnnotationCommands.ChangeType(region.id(), type));
			refresh();
		});
		return new VBox(8, sectionLabel("Annotation"), tabs);
	}

	private Node positionSection(AnnotationRegion region) {
		GridPane grid = new GridPane();
		grid.setHgap(6);
		grid.setVgap(6);
		for (int column = 0; column < 3; column++) {
			ColumnConstraints constraints = new ColumnConstraints();
			constraints.setPercentWidth(33.33);
			grid.getColumnConstraints().add(constraints);
		}
		List<PositionPreset> presets = positionPresets();
		for (int index = 0; index < presets.size(); index++) {
			PositionPreset preset = presets.get(index);
			Button button = new Button(preset.label(), Button.Variant.SECONDARY, Button.Size.SM);
			button.setMaxWidth(Double.MAX_VALUE);
			button.setAlignment(Pos.CENTER);
			if (matches(region.position(), preset)) {
				button.getStyleClass().add("editor-tile--selected");
			}
			button.setOnAction(event -> apply(new AnnotationCommands.ChangePosition(region.id(),
					new AnnotationPosition(preset.x(), preset.y()))));
			grid.add(button, index % 3, index / 3);
		}
		return new VBox(8, sectionLabel("Position"), grid);
	}

	private Node sizeSection(AnnotationRegion region) {
		Slider width = new Slider("Width");
		width.range(5, 100).step(1).formatter(value -> Math.round(value) + "%").sliderControl();
		width.setValue(region.size().width());
		width.onValueCommit(value -> apply(new AnnotationCommands.ChangeSize(region.id(),
				new AnnotationSize(value, region.size().height()))));
		Slider height = new Slider("Height");
		height.range(5, 100).step(1).formatter(value -> Math.round(value) + "%").sliderControl();
		height.setValue(region.size().height());
		height.onValueCommit(value -> apply(new AnnotationCommands.ChangeSize(region.id(),
				new AnnotationSize(region.size().width(), value))));
		return new VBox(8, sectionLabel("Size"), width, height);
	}

	private Node textSection(AnnotationRegion region) {
		AnnotationTextStyle style = region.style();
		VBox box = new VBox(10);

		TextArea text = new TextArea();
		text.setText(region.textContent() == null ? region.content() : region.textContent());
		text.setPrefRowCount(3);
		text.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				String next = text.getText() == null ? "" : text.getText().strip();
				if (!next.isEmpty() && !next.equals(region.content())) {
					apply(new AnnotationCommands.ChangeContent(region.id(), next));
				}
			}
		});

		Select font = new Select();
		for (String family : fontFamilies()) {
			font.item(family, family);
		}
		font.value(style.fontFamily());
		font.onValueChange(value -> applyStyle(region,
				new StylePatch(null, null, null, value, null, null, null, null, null)));

		Slider fontSize = new Slider("Font size");
		fontSize.range(12, 72).step(1).formatter(value -> Math.round(value) + "px").sliderControl();
		fontSize.setValue(style.fontSize());
		fontSize.onValueCommit(value -> applyStyle(region,
				new StylePatch(null, null, value, null, null, null, null, null, null)));

		ChoiceGroup weight = new ChoiceGroup();
		weight.item("normal", "Regular");
		weight.item("bold", "Bold");
		weight.setValue(style.fontWeight().wire());
		weight.onValueChange(value -> applyStyle(region,
				new StylePatch(null, null, null, null, FontWeight.fromWire(value), null, null, null,
						null)));

		LazyColorField textColor = colorField("Text color", style.color(), value -> applyStyle(region,
				new StylePatch(value, null, null, null, null, null, null, null, null)));
		LazyColorField background = colorField("Background", style.backgroundColor(),
				value -> applyStyle(region,
						new StylePatch(null, value, null, null, null, null, null, null, null)));

		Slider radius = new Slider("Box radius");
		radius.range(0, 40).step(1).formatter(value -> Math.round(value) + "px").sliderControl();
		radius.setValue(style.borderRadius());
		radius.onValueCommit(value -> applyStyle(region,
				new StylePatch(null, null, null, null, null, null, null, null, value)));

		box.getChildren().addAll(header("Text"), text, row("Font", font), fontSize, row("Weight", weight),
				textColor.node(), background.node(), radius);
		return box;
	}

	private Node figureSection(AnnotationRegion region) {
		FigureData figure = region.figureData() == null ? FigureData.DEFAULT : region.figureData();
		Select arrow = new Select();
		for (ArrowDirection direction : ArrowDirection.values()) {
			arrow.item(direction.name(), prettify(direction.name()));
		}
		arrow.value(arrowDirectionValue(figure));
		arrow.onValueChange(value -> apply(new AnnotationCommands.SetFigureData(region.id(),
				new FigureData(direction(value), figure.color(), figure.strokeWidth()))));

		Slider stroke = new Slider("Stroke width");
		stroke.range(1, 12).step(0.5).formatter(value -> String.format(Locale.ROOT, "%.1fpx", value))
				.sliderControl();
		stroke.setValue(figure.strokeWidth());
		stroke.onValueCommit(value -> apply(new AnnotationCommands.SetFigureData(region.id(),
				new FigureData(direction(arrowDirectionValue(figure)), figure.color(), value))));

		LazyColorField color = colorField("Figure color", figure.color(), value ->
				apply(new AnnotationCommands.SetFigureData(region.id(),
						new FigureData(direction(arrowDirectionValue(figure)), value,
								figure.strokeWidth()))));

		return new VBox(10, header("Figure"), row("Arrow", arrow), stroke, color.node());
	}

	private Node blurSection(AnnotationRegion region) {
		double intensity = region.blurIntensity() == null
				? AnnotationRegion.BLUR_ANNOTATION_STRENGTH : region.blurIntensity();
		Slider strength = new Slider("Blur intensity");
		strength.range(0, 100).step(1).formatter(value -> Math.round(value) + "%").sliderControl();
		strength.setValue(intensity);
		strength.onValueCommit(value -> apply(new AnnotationCommands.SetBlurIntensity(region.id(),
				value)));
		LazyColorField color = colorField("Blur color",
				region.blurColor() == null ? "#000000" : region.blurColor(),
				value -> apply(new AnnotationCommands.SetBlurColor(region.id(), value)));
		return new VBox(10, header("Blur"), strength, color.node());
	}

	private @Nullable Node imageSection(AnnotationRegion region) {
		Label note = new Label(
				"Image annotations are captured from the annotation tool; the selected image "
						+ "content is shown in the preview.");
		note.setWrapText(true);
		note.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
		return new VBox(10, header("Image"), note);
	}

	// ---- helpers --------------------------------------------------------

	private void apply(dev.gravaai.core.editor.EditorCommand command) {
		store.apply(command);
		refresh();
	}

	private void applyStyle(AnnotationRegion region, StylePatch patch) {
		apply(new AnnotationCommands.ChangeStyle(region.id(), patch));
	}

	private @Nullable AnnotationRegion selected() {
		String selectedId = store.selectedAnnotationId();
		if (selectedId == null) {
			return null;
		}
		for (AnnotationRegion region : store.state().annotationRegions()) {
			if (region.id().equals(selectedId)) {
				return region;
			}
		}
		return null;
	}

	private List<String> fontFamilies() {
		Map<String, String> families = new LinkedHashMap<>();
		for (String family : builtInFontFamilies()) {
			families.put(family.toLowerCase(Locale.ROOT), family);
		}
		for (var font : actions.customFonts()) {
			families.putIfAbsent(font.fontFamily().toLowerCase(Locale.ROOT), font.fontFamily());
		}
		return new ArrayList<>(families.values());
	}

	private LazyColorField colorField(String label, @Nullable String value, java.util.function.Consumer<String> onChange) {
		Input input = new Input();
		input.setText(value == null ? "#FFFFFF" : value);
		input.setPrefWidth(110);
		input.setOnAction(event -> {
			String normalized = parseHexColor(input.getText());
			if (normalized != null) {
				onChange.accept(normalized);
			}
		});
		HBox row = new HBox(8);
		row.setAlignment(Pos.CENTER_LEFT);
		Label caption = new Label(label);
		caption.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox swatches = new HBox(4);
		for (String swatch : COLOR_SWATCHES) {
			Button button = new Button();
			button.getStyleClass().add("editor-tile");
			button.setPrefSize(16, 16);
			button.setMinSize(16, 16);
			button.setStyle("-fx-background-color: " + swatch + "; -fx-background-radius: 4px;");
			button.setOnAction(event -> {
				input.setText(swatch);
				onChange.accept(swatch);
			});
			swatches.getChildren().add(button);
		}
		row.getChildren().addAll(caption, spacer, swatches);
		return new LazyColorField(new VBox(6, row, input));
	}

	private static HBox header(String title) {
		Label label = new Label(title);
		label.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: -g-fg;");
		HBox row = new HBox(label);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setPadding(new Insets(4, 0, 0, 0));
		return row;
	}

	private static Label sectionLabel(String text) {
		Label label = new Label(text);
		label.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: -g-muted;");
		return label;
	}

	private static HBox row(String label, Node control) {
		HBox row = new HBox(10);
		row.setAlignment(Pos.CENTER_LEFT);
		Label caption = new Label(label);
		caption.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		row.getChildren().addAll(caption, spacer, control);
		return row;
	}

	private static boolean matches(@Nullable AnnotationPosition position, PositionPreset preset) {
		return position != null && Math.abs(position.x() - preset.x()) < 0.01
				&& Math.abs(position.y() - preset.y()) < 0.01;
	}

	private static String arrowDirectionValue(FigureData figure) {
		return figure.arrowDirection().name();
	}

	private static ArrowDirection direction(String value) {
		try {
			return ArrowDirection.valueOf(value);
		} catch (RuntimeException error) {
			return ArrowDirection.RIGHT;
		}
	}

	private static String prettify(String enumName) {
		String spaced = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
		return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
	}

	private record LazyColorField(Node node) {}

	// ---- pure helpers (unit tested) -------------------------------------

	/** The nine 3x3 placement presets in visual order. */
	public static List<PositionPreset> positionPresets() {
		return POSITION_PRESETS;
	}

	/** Built-in annotation fonts installed with the app; custom fonts are appended at runtime. */
	public static List<String> builtInFontFamilies() {
		return BUILT_IN_FONTS;
	}

	/** Colour swatch palette for text/background/figure/blur colours. */
	public static @Nullable List<String> colorSwatches() {
		return COLOR_SWATCHES;
	}

	/** Accepts {@code #rgb}/{@code #rrggbb} (with or without #) and returns {@code #RRGGBB}. */
	public static @Nullable String parseHexColor(String raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.strip();
		if (value.startsWith("#")) {
			value = value.substring(1);
		}
		if (value.length() == 3) {
			value = "" + value.charAt(0) + value.charAt(0) + value.charAt(1)
					+ value.charAt(1) + value.charAt(2) + value.charAt(2);
		}
		if (value.length() != 6 || !value.matches("[0-9a-fA-F]{6}")) {
			return null;
		}
		return "#" + value.toUpperCase(Locale.ROOT);
	}

	/** Clamps a percent size value into the editable 5..100 range. */
	public static double clampSize(double value) {
		return Math.max(5, Math.min(100, value));
	}
}
