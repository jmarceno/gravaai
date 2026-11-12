package dev.gravaai.app.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

import dev.gravaai.app.StageScenes;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.meeting.MeetingMediaHost;
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
	public static MeetingWindow create(MeetingService service, Runnable onClose,
			@Nullable MeetingMediaHost media) {
		return build(new Stage(), service, onClose, true, media);
	}

	/**
	 * Hosts the meeting view in an existing stage (the app's single main window, plan §5.1); no
	 * second OS window is created.
	 */
	public static MeetingWindow createIn(Stage stage, MeetingService service, Runnable onClose,
			@Nullable MeetingMediaHost media) {
		Objects.requireNonNull(stage, "stage");
		return build(stage, service, onClose, false, media);
	}

	private static MeetingWindow build(Stage stage, MeetingService service, Runnable onClose,
			boolean ownsStage, @Nullable MeetingMediaHost media) {
		Objects.requireNonNull(service, "service");
		MeetingView view = MeetingView.create(service, new MeetingViewActions(
				() -> {
					if (onClose != null) {
						onClose.run();
					}
				}, () -> null, media, (format, fileName) -> chooseExportDestination(stage, format,
						fileName)));
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

	/**
	 * Asks where to write an exported notes document. JavaFX's own save dialog is used (the app has
	 * no portal-based save chooser), filtered to the chosen format so the extension matches what the
	 * writers produce.
	 */
	private static @Nullable Path chooseExportDestination(Stage owner,
			dev.gravaai.services.document.DocumentFormat format, String fileName) {
		javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
		chooser.setTitle("Export notes");
		chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
				format.label(), format.glob()));
		chooser.setInitialFileName(fileName);
		java.io.File file = chooser.showSaveDialog(owner);
		if (file == null) {
			return null;
		}
		Path picked = file.toPath();
		// The chooser can return a name the user edited past the filter; keep the chosen format.
		String name = picked.getFileName() == null ? "" : picked.getFileName().toString();
		if (!name.toLowerCase(java.util.Locale.ROOT).endsWith("." + format.extension())) {
			return Path.of(picked + "." + format.extension());
		}
		return picked;
	}

	/** True when the meeting view is hosted by the app's main window. */
	public boolean isHosted() {
		return hosted;	}

	/** Pauses the meeting player when playing (close-to-tray rule; the position is kept). */
	public void pausePlayback() {
		view.pausePlayback();
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
	public void open(@Nullable Path mediaPath) {
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
