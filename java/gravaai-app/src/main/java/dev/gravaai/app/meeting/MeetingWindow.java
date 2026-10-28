package dev.gravaai.app.meeting;

import java.nio.file.Path;
import java.util.Objects;

import dev.gravaai.app.StageScenes;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.meeting.MeetingView;
import dev.gravaai.ui.meeting.MeetingViewActions;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Standalone host for {@link MeetingView} (plan P1.11): a normal window with the meeting notes and
 * transcript panes. The dashboard embeds the same view later (P2.10); the P1 manual gate opens it
 * from the tray/open-request flow or with {@code GRAVAAI_MEETING_OPEN=<media path>}.
 */
public final class MeetingWindow {

	private final Stage stage;
	private final MeetingView view;
	/** True when the meeting view is hosted by the app's single main window. */
	private final boolean hosted;
	private boolean disposed;

	private MeetingWindow(Stage stage, MeetingView view, boolean hosted) {
		this.stage = stage;
		this.view = view;
		this.hosted = hosted;
	}

	/**
	 * Builds the window on the FX thread.
	 *
	 * @param onClose invoked by the view's back button after the stage is hidden
	 */
	public static MeetingWindow create(MeetingService service, Runnable onClose) {
		return build(new Stage(), service, onClose, true);
	}

	/**
	 * Hosts the meeting view in an existing stage (the app's single main window, plan §5.1); no
	 * second OS window is created.
	 */
	public static MeetingWindow createIn(Stage stage, MeetingService service, Runnable onClose) {
		Objects.requireNonNull(stage, "stage");
		return build(stage, service, onClose, false);
	}

	private static MeetingWindow build(Stage stage, MeetingService service, Runnable onClose,
			boolean ownsStage) {
		Objects.requireNonNull(service, "service");
		MeetingView view = MeetingView.create(service, new MeetingViewActions(
				() -> {
					if (onClose != null) {
						onClose.run();
					}
				}, null, null));
		double initialWidth = !ownsStage && stage.getScene() != null && stage.getWidth() > 0
				? stage.getWidth() : 1200;
		double initialHeight = !ownsStage && stage.getScene() != null && stage.getHeight() > 0
				? stage.getHeight() : 760;
		Scene scene = new Scene(new KitRoot(view.ui()), initialWidth, initialHeight);
		Theme.install(scene);
		stage.setTitle("Gravaai - Meeting");
		stage.setMinWidth(960);
		stage.setMinHeight(600);
		StageScenes.setSceneKeepingSize(stage, scene);
		if (ownsStage) {
			stage.setOnHidden(event -> view.close());
		}
		return new MeetingWindow(stage, view, !ownsStage);
	}

	/** True when the meeting view is hosted by the app's main window. */
	public boolean isHosted() {
		return hosted;
	}

	/** Releases the view resources; the hosted main window stays open. Idempotent. */
	public void dispose() {
		if (disposed) {
			return;
		}
		disposed = true;
		try {
			view.close();
		} catch (RuntimeException ignored) {
			// The view is going away; nothing useful to report.
		}
	}

	/** Shows the window, loading the given meeting when it is not null. */
	public void open(Path mediaPath) {
		Runnable show = () -> {
			if (mediaPath != null) {
				view.open(mediaPath);
			}
			stage.show();
			stage.toFront();
			stage.requestFocus();
		};
		if (Platform.isFxApplicationThread()) {
			show.run();
		} else {
			Platform.runLater(show);
		}
	}

	public void show() {
		open(null);
	}

	public void close() {
		Runnable hide = stage::hide;
		if (Platform.isFxApplicationThread()) {
			hide.run();
		} else {
			Platform.runLater(hide);
		}
	}

	public boolean isShowing() {
		return stage.isShowing();
	}

	public MeetingView view() {
		return view;
	}
}
