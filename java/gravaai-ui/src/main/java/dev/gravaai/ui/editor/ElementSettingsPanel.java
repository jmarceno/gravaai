package dev.gravaai.ui.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.ElementCommands;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ElementComponent;
import dev.gravaai.core.project.ElementMotion;
import dev.gravaai.core.project.SceneElement;
import dev.gravaai.services.elements.BuiltinComponents;
import dev.gravaai.services.elements.ComponentException;
import dev.gravaai.services.elements.ComponentInfo;
import dev.gravaai.services.elements.ComponentValidator;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.ChoiceGroup;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Select;
import dev.gravaai.ui.kit.Slider;
import dev.gravaai.ui.kit.Switch;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Inspector for a selected animated element (plan §5.8 "Inspector"): a form generated from the
 * component's {@code propsSchema} (numbers with a range become sliders, booleans switches, enums
 * selects, colours and text fields inputs), entrance/exit motion presets, screen/scene space and
 * motion-blur trails. Every change is one undoable {@link ElementCommands.Replace}.
 */
final class ElementSettingsPanel extends VBox {

	private static final String NONE = "none";

	private final EditorStore store;

	ElementSettingsPanel(EditorStore store) {
		super(14);
		this.store = store;
		refresh();
	}

	void refresh() {
		getChildren().clear();
		Optional<SceneElement> selected = store.selectedElementId().flatMap(id -> store.state()
				.elements().stream().filter(element -> element.id().equals(id)).findFirst());
		if (selected.isEmpty()) {
			getChildren().add(muted("Select an element on the ANIM lane to edit it."));
			return;
		}
		SceneElement element = selected.get();
		Label name = new Label(element.component());
		name.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
		Label span = muted(PlaybackControls.formatDuration(element.startMs() / 1000.0) + " – "
				+ PlaybackControls.formatDuration(element.endMs() / 1000.0));
		getChildren().addAll(name, span);

		Optional<ElementComponent> component = BuiltinComponents.resolve(element.component(),
				store.state().elementComponents());
		if (component.isEmpty()) {
			getChildren().add(muted("Unknown component: the element draws an error box."));
		} else {
			try {
				ComponentInfo info = ComponentValidator.describe(component.get());
				if (!info.description().isEmpty()) {
					getChildren().add(muted(info.description()));
				}
				getChildren().add(header("Props"));
				getChildren().addAll(propsForm(element, info));
			} catch (ComponentException error) {
				getChildren().add(muted("The component does not compile: " + error.describe()));
			}
		}

		getChildren().add(header("Motion"));
		getChildren().addAll(motionControls(element));
		getChildren().add(header("Placement"));
		getChildren().add(spaceControl(element));
		getChildren().addAll(blurControls(element));
		Button delete = new Button("Delete Element");
		delete.setOnAction(event -> store.apply(new ElementCommands.Delete(element.id())));
		getChildren().add(delete);
	}

	// ---- props ---------------------------------------------------------------------------

	private List<Node> propsForm(SceneElement element, ComponentInfo info) {
		List<Node> rows = new ArrayList<>();
		ObjectNode schema = info.propsSchema();
		if (schema.isEmpty()) {
			rows.add(muted("This component has no editable props."));
			return rows;
		}
		for (Map.Entry<String, JsonNode> entry : schema.properties()) {
			String key = entry.getKey();
			JsonNode rule = entry.getValue();
			String type = rule.isString() ? rule.asString() : rule.path("type").asString("any");
			JsonNode current = element.props().has(key) ? element.props().get(key)
					: info.defaultProps().path(key);
			rows.add(propControl(element, key, type, rule, current));
		}
		return rows;
	}

	private Node propControl(SceneElement element, String key, String type, JsonNode rule,
			JsonNode current) {
		switch (type) {
			case "number" -> {
				if (rule.has("min") && rule.has("max")) {
					double min = rule.get("min").asDouble();
					double max = rule.get("max").asDouble();
					Slider slider = new Slider(label(key));
					double step = (max - min) <= 1 ? 0.01 : (max - min) <= 20 ? 0.1 : 1;
					slider.range(min, max).step(step)
							.formatter(value -> trim(value)).sliderControl();
					slider.setValue(current.isNumber() ? current.asDouble() : min);
					slider.onValueCommit(value -> setProp(element, key, Json.nodeFactory().numberNode(value)));
					return slider;
				}
				Input input = new Input();
				input.setText(current.isNumber() ? trim(current.asDouble()) : "");
				input.setOnAction(event -> parseNumber(input.getText()).ifPresent(value ->
						setProp(element, key, Json.nodeFactory().numberNode(value))));
				return row(label(key), input);
			}
			case "boolean" -> {
				Switch toggle = new Switch(label(key));
				toggle.setChecked(current.asBoolean(false));
				toggle.onCheckedChange(value -> setProp(element, key, Json.nodeFactory().booleanNode(value)));
				return toggle;
			}
			case "enum" -> {
				Select select = new Select();
				for (JsonNode value : rule.path("values")) {
					select.item(value.asString(), value.asString());
				}
				if (current.isString()) {
					select.value(current.asString());
				}
				select.onValueChange(value -> setProp(element, key, Json.nodeFactory().stringNode(value)));
				return row(label(key), select);
			}
			case "string", "color" -> {
				Input input = new Input();
				input.setText(current.isString() ? current.asString() : "");
				input.setPrefWidth(type.equals("color") ? 110 : 200);
				Runnable commit = () -> setProp(element, key, Json.nodeFactory().stringNode(input.getText()));
				input.setOnAction(event -> commit.run());
				input.focusedProperty().addListener((observable, was, now) -> {
					if (!now) {
						commit.run();
					}
				});
				if (type.equals("color")) {
					Region swatch = new Region();
					swatch.setPrefSize(18, 18);
					swatch.setMinSize(18, 18);
					swatch.setStyle("-fx-background-color: " + safeColor(input.getText())
							+ "; -fx-background-radius: 4px; -fx-border-color: -g-border; -fx-border-radius: 4px;");
					HBox field = new HBox(6, swatch, input);
					field.setAlignment(Pos.CENTER_LEFT);
					return row(label(key), field);
				}
				return row(label(key), input);
			}
			default -> {
				// object / array / any: edit as JSON.
				Input input = new Input();
				input.setText(current.isMissingNode() ? "" : current.toString());
				input.setPrefWidth(200);
				input.setOnAction(event -> {
					try {
						setProp(element, key, Json.parseJsonWithByteOrderMark(input.getText()));
						input.invalid(false);
					} catch (RuntimeException invalid) {
						input.invalid(true);
					}
				});
				return row(label(key) + " (JSON)", input);
			}
		}
	}

	private void setProp(SceneElement element, String key, JsonNode value) {
		ObjectNode props = element.props().deepCopy();
		props.set(key, value);
		store.apply(new ElementCommands.Replace(element.withProps(props)));
	}

	// ---- motion ----------------------------------------------------------------------------

	private List<Node> motionControls(SceneElement element) {
		List<Node> rows = new ArrayList<>();
		PresetGuess guess = PresetGuess.of(element);
		if (guess.custom()) {
			rows.add(muted("Custom keyframes (" + element.motion().size() + " track"
					+ (element.motion().size() == 1 ? "" : "s") + "). Picking a preset replaces them."));
		}
		Select entrance = presetSelect(guess.in());
		Select exit = presetSelect(guess.out());
		Slider duration = new Slider("Duration");
		duration.range(0.1, 2).step(0.05).formatter(value -> String.format(Locale.ROOT, "%.2fs", value))
				.sliderControl();
		duration.setValue(guess.seconds());
		Runnable apply = () -> {
			long ms = Math.round(duration.getValue() * 1000);
			long half = Math.max(50, element.durationMs() / 2);
			List<ElementMotion.Track> tracks = new ArrayList<>();
			if (!entrance.value().equals(NONE)) {
				tracks.addAll(ElementMotion.preset(entrance.value(), true, false, Math.min(ms, half)));
			}
			if (!exit.value().equals(NONE)) {
				tracks.addAll(ElementMotion.preset(exit.value(), false, true, Math.min(ms, half)));
			}
			store.apply(new ElementCommands.Replace(element.withMotion(ElementMotion.combine(tracks))));
		};
		entrance.onValueChange(value -> apply.run());
		exit.onValueChange(value -> apply.run());
		duration.onValueCommit(value -> apply.run());
		rows.add(row("Entrance", entrance));
		rows.add(row("Exit", exit));
		rows.add(duration);
		return rows;
	}

	private static Select presetSelect(String value) {
		Select select = new Select();
		select.item(NONE, "None");
		for (String preset : ElementMotion.PRESETS) {
			select.item(preset, label(preset));
		}
		select.value(value);
		return select;
	}

	/**
	 * Which presets the element's motion came from (the inspector re-expands presets, so a
	 * curve it wrote is recognised by comparing against fresh expansions).
	 */
	private record PresetGuess(String in, String out, double seconds, boolean custom) {
		static PresetGuess of(SceneElement element) {
			if (element.motion().isEmpty()) {
				return new PresetGuess(NONE, NONE, 0.4, false);
			}
			long half = Math.max(50, element.durationMs() / 2);
			for (double seconds = 0.1; seconds <= 2.0001; seconds += 0.05) {
				long ms = Math.min(Math.round(seconds * 1000), half);
				for (String in : withNone()) {
					for (String out : withNone()) {
						List<ElementMotion.Track> tracks = new ArrayList<>();
						if (!in.equals(NONE)) {
							tracks.addAll(ElementMotion.preset(in, true, false, ms));
						}
						if (!out.equals(NONE)) {
							tracks.addAll(ElementMotion.preset(out, false, true, ms));
						}
						if (ElementMotion.combine(tracks).equals(element.motion())) {
							return new PresetGuess(in, out, Math.round(seconds * 100) / 100.0, false);
						}
					}
				}
			}
			return new PresetGuess(NONE, NONE, 0.4, true);
		}

		private static List<String> withNone() {
			List<String> names = new ArrayList<>();
			names.add(NONE);
			names.addAll(ElementMotion.PRESETS);
			return names;
		}
	}

	// ---- placement ---------------------------------------------------------------------------

	private Node spaceControl(SceneElement element) {
		ChoiceGroup space = new ChoiceGroup();
		space.item("screen", "Screen");
		space.item("scene", "Follows zoom");
		space.setValue(element.space().wire());
		space.setAccessibleText("Space");
		space.onValueChange(value -> SceneElement.Space.fromWire(value).ifPresent(choice ->
				store.apply(new ElementCommands.Replace(element.withSpace(choice)))));
		return row("Space", space);
	}

	private List<Node> blurControls(SceneElement element) {
		List<Node> rows = new ArrayList<>();
		ElementMotion.Blur blur = element.blur();
		Switch enabled = new Switch("Motion blur trails");
		enabled.setChecked(blur.enabled());
		enabled.onCheckedChange(value -> store.apply(new ElementCommands.Replace(element.withBlur(
				value ? new ElementMotion.Blur(6, 1) : ElementMotion.Blur.NONE))));
		rows.add(enabled);
		if (blur.enabled()) {
			Slider samples = new Slider("Samples");
			samples.range(2, 16).step(1).formatter(value -> Long.toString(Math.round(value))).sliderControl();
			samples.setValue(blur.samples());
			samples.onValueCommit(value -> store.apply(new ElementCommands.Replace(element.withBlur(
					new ElementMotion.Blur((int) Math.round(value), blur.shutter())))));
			Slider shutter = new Slider("Shutter (frames)");
			shutter.range(0.25, 4).step(0.25).formatter(ElementSettingsPanel::trim).sliderControl();
			shutter.setValue(blur.shutter());
			shutter.onValueCommit(value -> store.apply(new ElementCommands.Replace(element.withBlur(
					new ElementMotion.Blur(blur.samples(), value)))));
			rows.add(samples);
			rows.add(shutter);
		}
		return rows;
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static Label header(String text) {
		Label label = new Label(text.toUpperCase(Locale.ROOT));
		label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -g-muted;");
		return label;
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.setWrapText(true);
		label.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
		return label;
	}

	private static HBox row(String label, Node control) {
		Label caption = new Label(label);
		caption.setStyle("-fx-font-size: 12px;");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox row = new HBox(8, caption, spacer, control);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	/** {@code countFrames} → "Count frames", {@code slide-left} → "Slide left". */
	static String label(String key) {
		String spaced = key.replace('-', ' ').replace('_', ' ')
				.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
		return spaced.isEmpty() ? key : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}

	private static String trim(double value) {
		return value == Math.rint(value) ? Long.toString((long) value)
				: String.format(Locale.ROOT, "%.2f", value);
	}

	private static Optional<Double> parseNumber(String text) {
		try {
			double value = Double.parseDouble(text.trim());
			return Double.isFinite(value) ? Optional.of(value) : Optional.empty();
		} catch (NumberFormatException invalid) {
			return Optional.empty();
		}
	}

	private static String safeColor(String text) {
		return text.matches("#[0-9a-fA-F]{3,8}") ? text : "transparent";
	}
}
