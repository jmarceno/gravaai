package dev.gravaai.ui.editor.export;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportQuality;
import dev.gravaai.core.editor.ExportPreferences.GifSizePreset;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Card;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Switch;
import dev.gravaai.ui.kit.Toggle;
import dev.gravaai.ui.kit.ToggleGroup;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Export settings form (port of {@code ExportSettingsMenu.tsx}, plan §5.10/§5.12): format, quality,
 * encoding, FPS and the caption-sidecar switch for MP4; FPS, size, output size and loop for GIF,
 * with the "Export Video/GIF" action. Inside the export popover the card chrome is stripped
 * ({@code rounded-none bg-transparent px-5 pb-5 pt-0 shadow-none}).
 */
public final class ExportSettingsMenu extends VBox {

	/** Output sizes shown under the quality/size choices (port of {@code useExportDimensions}). */
	public record OutputDimensions(java.util.Map<ExportQuality, int[]> mp4, int[] gif) {}

	private final ExportSettingsState settings;
	private final BooleanSupplier hasCaptionsForSidecar;
	private final OutputDimensions dimensions;
	private final Runnable onExport;

	public ExportSettingsMenu(ExportSettingsState settings, BooleanSupplier hasCaptionsForSidecar,
			OutputDimensions dimensions, Runnable onExport) {
		this.settings = settings;
		this.hasCaptionsForSidecar = hasCaptionsForSidecar == null ? () -> false : hasCaptionsForSidecar;
		this.dimensions = dimensions;
		this.onExport = onExport;
		getStyleClass().add("export-settings");
		setSpacing(16);
		setPrefWidth(360);
		render();
	}

	/** Rebuilds the form from the current settings (the popover calls this on changes). */
	public void render() {
		getChildren().setAll(buildContent());
	}

	private List<Node> buildContent() {
		ExportFormat format = settings.exportFormat();
		java.util.List<Node> children = new java.util.ArrayList<>();
		children.add(choices("Format", format == ExportFormat.GIF ? "gif" : "mp4", List.of(
				choice("mp4", "MP4", Icons.FilmSlate, null),
				choice("gif", "GIF", Icons.Image, null)),
				value -> settings.setExportFormat("gif".equals(value) ? ExportFormat.GIF
						: ExportFormat.MP4)));
		if (format == ExportFormat.MP4) {
			children.add(qualityChoices());
			children.add(encodingChoices());
			children.add(fpsChoices("FPS", String.valueOf(settings.mp4FrameRate()),
					ExportPreferences.MP4_FRAME_RATES, settings::setMp4FrameRate));
			if (hasCaptionsForSidecar.getAsBoolean()) {
				children.add(captionSidecarOption());
			}
		} else {
			children.add(fpsChoices("FPS", String.valueOf(settings.gifFrameRate()),
					ExportPreferences.GIF_FRAME_RATES, settings::setGifFrameRate));
			children.add(gifSizeChoices());
			if (dimensions != null && dimensions.gif() != null) {
				Card.Description gifSize = new Card.Description(
						dimensions.gif()[0] + " × " + dimensions.gif()[1] + "px");
				gifSize.getStyleClass().add("export-settings__hint");
				children.add(gifSize);
			}
			Switch loop = new Switch("Loop", settings.gifLoop());
			loop.onCheckedChange(settings::setGifLoop);
			children.add(loop);
		}
		children.add(exportButton());
		return children;
	}

	private Node qualityChoices() {
		// React maps ["medium","good","high","source"] to the labels low/medium/high/original.
		List<String> labels = List.of("Low", "Medium", "High", "Original");
		List<ExportQuality> values = List.of(ExportQuality.MEDIUM, ExportQuality.GOOD,
				ExportQuality.HIGH, ExportQuality.SOURCE);
		java.util.List<Choice> options = new java.util.ArrayList<>();
		for (int index = 0; index < values.size(); index++) {
			ExportQuality quality = values.get(index);
			String description = dimensions == null || dimensions.mp4() == null
					|| dimensions.mp4().get(quality) == null ? null
					: dimensions.mp4().get(quality)[0] + " × " + dimensions.mp4().get(quality)[1];
			options.add(new Choice(quality.wire(), labels.get(index), null, description));
		}
		String selected = settings.exportQuality() == null ? null : settings.exportQuality().wire();
		return choices("Quality", selected, options,
				value -> settings.setExportQuality(ExportQuality.fromWire(value)));
	}

	private Node encodingChoices() {
		List<Choice> options = List.of(
				new Choice(ExportEncodingMode.FAST.wire(), "Fast", null, null),
				new Choice(ExportEncodingMode.BALANCED.wire(), "Balanced", null, null),
				new Choice(ExportEncodingMode.QUALITY.wire(), "Quality", null, null));
		String selected = settings.exportEncodingMode() == null ? null
				: settings.exportEncodingMode().wire();
		return choices("Encoding", selected, options,
				value -> settings.setExportEncodingMode(ExportEncodingMode.fromWire(value)));
	}

	private Node fpsChoices(String label, String selected, int[] rates,
			java.util.function.IntConsumer onChange) {
		java.util.List<Choice> options = new java.util.ArrayList<>();
		for (int rate : rates) {
			options.add(new Choice(String.valueOf(rate), String.valueOf(rate), null, null));
		}
		return choices(label, selected, options, value -> onChange.accept(Integer.parseInt(value)));
	}

	private Node gifSizeChoices() {
		List<Choice> options = List.of(
				new Choice(GifSizePreset.MEDIUM.wire(), "Medium (720p)", null, null),
				new Choice(GifSizePreset.LARGE.wire(), "Large (1080p)", null, null),
				new Choice(GifSizePreset.ORIGINAL.wire(), "Original", null, null));
		String selected = settings.gifSizePreset() == null ? null : settings.gifSizePreset().wire();
		return choices("Size", selected, options,
				value -> settings.setGifSizePreset(GifSizePreset.fromWire(value)));
	}

	private Node captionSidecarOption() {
		VBox box = new VBox(4);
		Switch toggle = new Switch("Export captions file", settings.includeCaptionSidecar());
		toggle.onCheckedChange(settings::setIncludeCaptionSidecar);
		Card.Description hint = new Card.Description(
				"Save .srt and .vtt files next to your exported video.");
		hint.getStyleClass().add("export-settings__hint");
		hint.getStyleClass().add("export-settings__hint--indent");
		box.getChildren().addAll(toggle, hint);
		return box;
	}

	private Node exportButton() {
		Button export = new Button(
				settings.exportFormat() == ExportFormat.GIF ? "Export GIF" : "Export Video",
				Button.Variant.DEFAULT, Button.Size.LG);
		export.icon(Icons.DownloadSimple);
		export.setGraphicTextGap(8);
		export.setMaxWidth(Double.MAX_VALUE);
		export.setOnAction(event -> onExport.run());
		return export;
	}

	private Node choices(String label, @Nullable String selected, List<Choice> options,
			java.util.function.Consumer<String> onChange) {
		VBox box = new VBox(8);
		box.getStyleClass().add("export-settings__choices");
		Label heading = new Label(label);
		box.getChildren().add(heading);
		ToggleGroup group = new ToggleGroup(ToggleGroup.Mode.SINGLE).detached();
		for (Choice option : options) {
			Toggle toggle = group.item(option.value(), option.content());
			toggle.getStyleClass().add("export-tag");
			if (option.value().equals(selected)) {
				toggle.setSelected(true);
			}
		}
		for (Toggle toggle : group.buttons()) {
			HBox.setHgrow(toggle, Priority.ALWAYS);
		}
		group.onValueChange(value -> {
			if (!value.isEmpty()) {
				onChange.accept(value);
			}
		});
		box.getChildren().add(group);
		return box;
	}

	private static Choice choice(String value, String label, String icon, @Nullable String description) {
		return new Choice(value, label, icon, description);
	}

	private record Choice(String value, String label, @Nullable String icon, @Nullable String description) {
		Node content() {
			if (icon != null) {
				HBox row = new HBox(8, Icon.of(icon, 16), new javafx.scene.control.Label(label));
				row.setAlignment(Pos.CENTER_LEFT);
				return row;
			}
			if (description == null) {
				return new javafx.scene.control.Label(label);
			}
			VBox stack = new VBox(2);
			stack.setAlignment(Pos.CENTER);
			javafx.scene.control.Label title = new javafx.scene.control.Label(label);
			javafx.scene.control.Label hint = new javafx.scene.control.Label(description);
			hint.getStyleClass().add("export-tag__description");
			stack.getChildren().addAll(title, hint);
			return stack;
		}
	}
}
