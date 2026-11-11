package dev.gravaai.ui.editor;

import java.util.function.BiConsumer;

import dev.gravaai.core.project.TimelineMarker;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Dialog;
import dev.gravaai.ui.kit.Input;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

/**
 * Rename dialog for a timeline marker (the {@code M} flow and the ruler's rename gesture).
 *
 * <p>Enter or the Rename button commits, Escape and the backdrop cancel. The committed name goes
 * through {@link TimelineMarker#cleanName}, so a blank field falls back to the generic name
 * instead of producing a nameless chapter.
 */
public final class MarkerRenameDialog {

	private final Dialog dialog;
	private final Input input;
	private final BiConsumer<String, String> onRename;
	private final TimelineMarker marker;
	private boolean committed;

	/** @param onRename receives the marker id and the cleaned name when the user commits */
	public MarkerRenameDialog(TimelineMarker marker, BiConsumer<String, String> onRename) {
		this.marker = marker;
		this.onRename = onRename;
		dialog = new Dialog("Rename Marker");
		dialog.description("Chapter titles appear in exported videos and the chapters.txt sidecar.");
		input = new Input("Marker name").text(marker.name());
		input.fullWidth();
		VBox body = new VBox(input);
		body.setSpacing(8);
		dialog.content(body);

		Button cancel = new Button("Cancel", Button.Variant.GHOST, Button.Size.LG);
		cancel.setOnAction(event -> dialog.close());
		Button save = new Button("Rename", Button.Variant.DEFAULT, Button.Size.LG);
		save.setOnAction(event -> {
			committed = true;
			dialog.close();
		});
		input.setOnAction(event -> {
			committed = true;
			dialog.close();
		});
		dialog.footer(cancel, save);
		dialog.onClose(() -> {
			if (committed) {
				onRename.accept(marker.id(), TimelineMarker.cleanName(input.getText()));
			}
		});
	}

	/** Opens the dialog inside the kit root that owns {@code owner}. */
	public MarkerRenameDialog show(Node owner) {
		dialog.show(owner);
		input.requestFocus();
		input.selectAll();
		return this;
	}

	public Dialog dialog() {
		return dialog;
	}
}
