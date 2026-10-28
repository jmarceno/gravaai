package dev.gravaai.ui.dashboard;

import java.util.List;
import java.util.function.Consumer;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Dialog;
import dev.gravaai.ui.kit.Switch;
import javafx.scene.Node;

/**
 * The project-delete confirmation (port of {@code ProjectDeleteDialog.tsx}) on the kit's in-window
 * {@link Dialog}.
 *
 * <p>Asks whether the recordings referenced by the projects should be trashed as well; the switch
 * defaults to off, so the recordings are kept unless the user opts in.
 */
public final class DashboardDeleteDialog {

	private DashboardDeleteDialog() {
	}

	/** Shows the confirmation; {@code onConfirm} receives the switch state. */
	public static void show(Node owner, List<String> projectPaths, Consumer<Boolean> onConfirm) {
		if (projectPaths == null || projectPaths.isEmpty()) {
			return;
		}
		boolean singular = projectPaths.size() == 1;
		String title = singular
				? "Delete \u201c" + displayName(projectPaths.get(0)) + "\u201d?"
				: "Delete " + projectPaths.size() + " projects?";
		Dialog dialog = new Dialog(title);
		dialog.width(420);
		dialog.description(singular
				? "The project moves to the system trash and can still be restored from there."
				: "The projects move to the system trash and can still be restored from there.");

		Switch deleteRecordings = new Switch(
				singular ? "Also delete the recorded video on disk"
						: "Also delete the recorded videos on disk");
		dialog.content(deleteRecordings);

		Button cancel = new Button("Cancel", Button.Variant.GHOST, Button.Size.SM);
		cancel.setOnAction(event -> dialog.close());
		Button delete = new Button("Delete", Button.Variant.DESTRUCTIVE, Button.Size.SM);
		delete.setOnAction(event -> {
			dialog.close();
			onConfirm.accept(deleteRecordings.isChecked());
		});
		dialog.footer(cancel, delete);
		dialog.show(owner);
	}

	private static String displayName(String projectPath) {
		String base = projectPath.replaceAll(".*[\\\\/]", "");
		return base.replaceAll("(?i)\\.(gravaai|gvp)$", "");
	}
}