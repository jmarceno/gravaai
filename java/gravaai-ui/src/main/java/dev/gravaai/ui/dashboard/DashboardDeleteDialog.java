package dev.gravaai.ui.dashboard;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.gravaai.ui.i18n.UiI18n;
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
				? UiI18n.t("dashboard:deleteDialog.titleOne",
						Map.of("name", displayName(projectPaths.get(0))))
				: UiI18n.t("dashboard:deleteDialog.titleMany",
						Map.of("count", projectPaths.size()));
		Dialog dialog = new Dialog(title);
		dialog.width(420);
		dialog.description(singular
				? UiI18n.t("dashboard:deleteDialog.descriptionOne")
				: UiI18n.t("dashboard:deleteDialog.descriptionMany"));

		Switch deleteRecordings = new Switch(
				singular ? UiI18n.t("dashboard:deleteDialog.alsoDeleteVideoOne")
						: UiI18n.t("dashboard:deleteDialog.alsoDeleteVideoMany"));
		dialog.content(deleteRecordings);

		Button cancel = new Button(UiI18n.t("common:actions.cancel"), Button.Variant.GHOST, Button.Size.SM);
		cancel.setOnAction(event -> dialog.close());
		Button delete = new Button(UiI18n.t("common:actions.delete"), Button.Variant.DESTRUCTIVE, Button.Size.SM);
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