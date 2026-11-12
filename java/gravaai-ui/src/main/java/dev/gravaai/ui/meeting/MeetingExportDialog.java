package dev.gravaai.ui.meeting;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.gravaai.services.document.DocumentFormat;
import dev.gravaai.services.meeting.MeetingNotesExport;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.ChoiceGroup;
import dev.gravaai.ui.kit.Dialog;
import dev.gravaai.ui.kit.Switch;
import dev.gravaai.ui.kit.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The "Export notes" dialog: pick the format, decide whether the full transcript is appended, then
 * choose the destination.
 *
 * <p>Format and transcript are one choice each, typed as {@link DocumentFormat} and a boolean, and
 * the dialog only answers with a {@link MeetingNotesExport.Request}: where to write it is the
 * caller's seam, so the same dialog works in the app (a GTK/JavaFX save chooser) and in tests.
 */
public final class MeetingExportDialog {

	/** The chosen request plus the destination the user picked. */
	public record Choice(MeetingNotesExport.Request request, Path destination) {
	}

	private final Dialog dialog;

	/**
	 * Builds and shows the dialog.
	 *
	 * @param owner any node inside the window that hosts the dialog
	 * @param initialFormat the format to preselect
	 * @param transcriptAvailable whether the meeting has a transcript to append; when false the
	 *     checkbox is disabled and stays off
	 * @param fileNameFor the default file name for a format (usually
	 *     {@code MeetingNotesExport.defaultFileName})
	 * @param saveChooser asks the user where to write; empty when they cancel. Called on the FX
	 *     thread, once the format is known.
	 * @param onExport receives the finished choice; not called when the user cancels
	 */
	public static void show(Node owner, DocumentFormat initialFormat,
			boolean transcriptAvailable,
			Function<DocumentFormat, String> fileNameFor,
			BiFunction<DocumentFormat, String, Optional<Path>> saveChooser,
			Consumer<Choice> onExport) {
		MeetingExportDialog export = new MeetingExportDialog(initialFormat, transcriptAvailable,
				fileNameFor, saveChooser, onExport);
		export.show(owner);
	}

	private MeetingExportDialog(DocumentFormat initialFormat, boolean transcriptAvailable,
			Function<DocumentFormat, String> fileNameFor,
			BiFunction<DocumentFormat, String, Optional<Path>> saveChooser,
			Consumer<Choice> onExport) {
		dialog = new Dialog("Export notes");
		dialog.width(460);
		dialog.description("Save the meeting notes as a document. The transcript is optional.");

		ChoiceGroup formats = new ChoiceGroup();
		for (DocumentFormat format : DocumentFormat.values()) {
			formats.item(format.name(), format.label());
		}
		formats.setValue(initialFormat.name());

		Switch transcript = new Switch(transcriptAvailable
				? "Include the full transcript"
				: "No transcript to include");
		transcript.setChecked(false);
		transcript.setDisable(!transcriptAvailable);

		VBox body = new VBox(14);
		body.setPadding(new Insets(4, 0, 8, 0));
		Label formatCaption = caption("Format");
		body.getChildren().addAll(formatCaption, formats, transcript);

		Button cancel = new Button("Cancel", Button.Variant.GHOST, Button.Size.SM);
		cancel.setOnAction(event -> dialog.close());
		Button export = new Button("Choose file…", Button.Variant.DEFAULT, Button.Size.SM);
		export.setOnAction(event -> {
			DocumentFormat format = formatOf(formats.value(), initialFormat);
			saveChooser.apply(format, fileNameFor.apply(format)).ifPresent(destination -> {
				dialog.close();
				onExport.accept(new Choice(
						new MeetingNotesExport.Request(format, transcript.isChecked()),
						destination));
			});
		});

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox footer = new HBox(8, spacer, cancel, export);
		footer.setAlignment(Pos.CENTER_RIGHT);

		dialog.content(body);
		dialog.footer(footer);
	}

	private void show(Node owner) {
		dialog.show(owner);
	}

	/**
	 * The chip value as a format. The picker only offers {@link DocumentFormat} names, so the value
	 * always resolves; the fallback keeps a corrupted selection from crashing the dialog.
	 */
	private static DocumentFormat formatOf(String value, DocumentFormat fallback) {
		return java.util.Arrays.stream(DocumentFormat.values())
				.filter(format -> format.name().equals(value))
				.findFirst()
				.orElse(fallback);
	}

	private static Label caption(String text) {
		Label label = new Label(text);
		label.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		return label;
	}

	private static String css(javafx.scene.paint.Color color) {
		return "#" + color.toString().substring(2, 8);
	}
}
