package dev.gravaai.ui.meeting;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Callbacks the app shell provides to the meetings UI (plan §5.3: no Electron process split; the
 * AppContext wires window navigation and the XDG portal file chooser).
 *
 * @param onClose invoked by the meeting view's back button
 * @param importFileChooser opens the file chooser and returns the picked media path, or null when
 *     cancelled
 * @param media the playback host, or null until the media playback layer is wired
 */
public record MeetingViewActions(Runnable onClose, Supplier<Path> importFileChooser,
		MeetingMediaHost media) {

	public MeetingViewActions {
		onClose = onClose == null ? () -> { } : onClose;
		importFileChooser = importFileChooser == null ? () -> null : importFileChooser;
	}

	/** Defaults for a view that only shows data (snapshots/tests). */
	public static MeetingViewActions none() {
		return new MeetingViewActions(() -> { }, () -> null, null);
	}
}
