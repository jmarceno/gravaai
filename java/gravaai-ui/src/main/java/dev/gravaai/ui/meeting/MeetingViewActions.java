package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import dev.gravaai.services.document.DocumentFormat;

/**
 * Callbacks the app shell provides to the meetings UI (plan §5.3: no Electron process split; the
 * AppContext wires window navigation and the XDG portal file chooser).
 *
 * @param onClose invoked by the meeting view's back button
 * @param importFileChooser opens the file chooser and returns the picked media path, or null when
 *     cancelled
 * @param media the playback host ({@link MeetingPlayerHost} in the app shell); null for views
 *     without playback (snapshots/tests)
 * @param exportFileChooser asks where to write an exported document, given the chosen format and
 *     the suggested file name; returns null when cancelled. The format is passed so the chooser can
 *     offer the matching extension filter.
 */
public record MeetingViewActions(Runnable onClose, Supplier<@Nullable Path> importFileChooser,
		@Nullable MeetingMediaHost media,
		BiFunction<DocumentFormat, String, @Nullable Path> exportFileChooser) {

	public MeetingViewActions {
		onClose = onClose == null ? () -> { } : onClose;
		importFileChooser = importFileChooser == null ? () -> null : importFileChooser;
		exportFileChooser = exportFileChooser == null ? (format, name) -> null : exportFileChooser;
	}

	/** Defaults for a view that only shows data (snapshots/tests). */
	public static MeetingViewActions none() {
		return new MeetingViewActions(() -> { }, () -> null, null, (format, name) -> null);
	}
}
