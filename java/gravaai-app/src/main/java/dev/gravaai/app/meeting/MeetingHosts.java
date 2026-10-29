package dev.gravaai.app.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.Consumer;

import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.meeting.MeetingLibraryView;
import dev.gravaai.ui.meeting.MeetingSettingsView;
import dev.gravaai.ui.meeting.MeetingViewActions;
import dev.gravaai.ui.meeting.ServiceMeetingUiService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * Standalone hosts for the meeting library and meeting settings screens (plan P1.11).
 *
 * <p>The dashboard embeds the same views in P2.10; during the Phase 1 gate they are reachable with
 * {@code GRAVAAI_MEETING_LIBRARY=1} / {@code GRAVAAI_MEETING_SETTINGS=1} so the gate can exercise
 * import, library and settings without the editor shell.
 */
public final class MeetingHosts {

	private MeetingHosts() {
	}

	/** Library window; {@code onOpenMeeting} receives the meeting the user opens. */
	public static void openLibrary(MeetingService service, Consumer<Path> onOpenMeeting) {
		runOnFx(() -> {
			MeetingLibraryView library = new MeetingLibraryView(
					new ServiceMeetingUiService(service), MeetingViewActions.none(),
					onOpenMeeting);
			show(library, "Gravaai - Meetings", 1200, 760, library::refresh);
		});
	}

	/** Settings -> Meetings window. */
	public static void openSettings(MeetingService service) {
		runOnFx(() -> {
			MeetingSettingsView settings = new MeetingSettingsView(
					new ServiceMeetingUiService(service));
			show(settings, "Gravaai - Meeting settings", 900, 820, null);
		});
	}

	private static void show(Region content, String title, double width, double height,
			@Nullable Runnable onShown) {
		Stage stage = new Stage();
		stage.setTitle(title);
		Scene scene = new Scene(new KitRoot(content), width, height);
		Theme.install(scene);
		stage.setScene(scene);
		stage.setMinWidth(Math.min(width, 760));
		stage.setMinHeight(Math.min(height, 560));
		stage.setOnHidden(event -> {
			if (content instanceof MeetingSettingsView settings) {
				settings.close();
			}
		});
		stage.show();
		stage.toFront();
		stage.requestFocus();
		if (onShown != null) {
			onShown.run();
		}
	}

	private static void runOnFx(Runnable action) {
		if (Platform.isFxApplicationThread()) {
			action.run();
		} else {
			Platform.runLater(action);
		}
	}
}
