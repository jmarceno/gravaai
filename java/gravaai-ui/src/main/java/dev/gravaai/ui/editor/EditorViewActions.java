package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.ui.editor.export.ExportSessionModel;

/**
 * Callback seam between the editor views and the app services (plan §5.12, P2.6).
 *
 * <p>The editor shell only knows these operations; {@code AppContext} wires them to the project
 * services, panels are kept testable with a fake. Every method has a no-op default so a snapshot
 * harness and partial wiring stay explicit rather than crashing.
 */
public interface EditorViewActions {

	/** Opens the project browser (project-name browser button and the unsaved-changes flow). */
	default void openProjectBrowser() {}

	/** Goes Home to the dashboard (header Home button). Defaults to the browser for fakes. */
	default void goHome() {
		openProjectBrowser();
	}

	/** Renames the current project (header inline rename). */
	default void renameProject(String projectName) {}

	/** Save / Save As (menu accelerators are wired in the app module). */
	default void save() {}

	default void saveAs() {}

	/** Persisted export settings (the export popover's initial state). */
	default ExportPreferences exportPreferences() {
		return ExportPreferences.defaults();
	}

	/** Persists an export-settings change from the export popover. */
	default void saveExportPreferences(ExportPreferences preferences) {}

	/** Export button: starts the export with the popover settings. */
	default void startExport(ExportPreferences preferences) {}

	/** Progress pane: cancels the running export. */
	default void cancelExport() {}

	/** Error pane: saves the pending staged export again (Save Again). */
	default void retrySaveExport() {}

	/** Complete pane: reveals the exported file in the file manager. */
	default void revealExportedFile() {}

	/** Deletes the staged file of a dropped pending export save (popover close). */
	default void discardPendingExportSave(ExportSessionModel.PendingExportSave pending) {}

	/** Probed {width, height} of the source recording (the output-size labels). */
	default int[] exportSourceSize() {
		return new int[] {0, 0};
	}

	/** Clips panel toggle (the recording library). */
	default void toggleClips(boolean open) {}

	/** Wallpaper picker for a custom image; empty when the user cancels. */
	default Optional<Path> chooseWallpaper() {
		return Optional.empty();
	}

	/** Video background picker ({@code openVideoFilePicker}); empty when the user cancels. */
	default Optional<Path> chooseVideoWallpaper() {
		return Optional.empty();
	}

	/** Opens the crop editor dialog. */
	default void openCropEditor() {}

	default void openKeyboardShortcutsHelp() {}

	default void openShortcutsConfig() {}

	default void openTutorialHelp() {}

	default void openCaptionList() {}

	default void openExtensions() {}

	/** Saved-project rows for the header Projects popover. */
	default java.util.List<ProjectBrowserDialog.Entry> projectBrowserEntries() {
		return java.util.List.of();
	}

	/** Opens the project chosen in the Projects popover. */
	default void openProject(Path path) {}

	/** Imports a .gravaai file chosen from the Projects popover. */
	default void importProjectFile() {}

	/** Persisted custom annotation fonts ({@code gravaai_custom_fonts}). */
	default java.util.List<CustomFonts.CustomFont> customFonts() {
		return java.util.List.of();
	}

	default void saveCustomFonts(java.util.List<CustomFonts.CustomFont> fonts) {}

	/** Current shortcut bindings (the shell opens the config dialog with these). */
	default java.util.Map<Shortcuts.Action, Shortcuts.Binding> shortcuts() {
		return Shortcuts.defaults();
	}

	/** Persists the shortcut bindings saved from {@link ShortcutsConfigDialog}. */
	default void saveShortcuts(java.util.Map<Shortcuts.Action, Shortcuts.Binding> shortcuts) {}

	// ---- Announcements (bundled/remote feeds + dismissal/impression state) ----

	default Announcements.@Nullable Feed bundledAnnouncements() {
		return Announcements.bundledFeed();
	}

	default Announcements.Feed remoteAnnouncements() {
		return Announcements.Feed.EMPTY;
	}

	default java.util.Set<String> dismissedAnnouncementIds() {
		return java.util.Set.of();
	}

	default java.util.Map<String, Integer> announcementImpressionCounts() {
		return java.util.Map.of();
	}

	default String appVersion() {
		return "0.0.0";
	}

	default void dismissAnnouncement(String id) {}

	default void recordAnnouncementImpression(String id) {}

	default void runAnnouncementAction(Announcements.Action action) {}

	/** Timeline toolbar: add an annotation track row (defaults to text). */
	default void addAnnotationLayer() {
		addTextAnnotation();
	}

	/** Timeline toolbar: add a text annotation at the playhead. */
	default void addTextAnnotation() {}

	/** Timeline toolbar: add a figure annotation at the playhead. */
	default void addFigureAnnotation() {}

	/** Timeline toolbar: add an image annotation at the playhead. */
	default void addImageAnnotation() {}

	/** Timeline toolbar: add a blur annotation at the playhead. */
	default void addBlurAnnotation() {}

	/** Timeline toolbar: add an audio track row. */
	default void addAudioLayer() {}

	/** Timeline toolbar: add a zoom region at the playhead. */
	default void addZoom() {}

	/** Timeline toolbar: request auto-zoom suggestions from the cursor telemetry. */
	default void suggestZooms() {}

	/** Timeline toolbar: split the selected clip at the playhead. */
	default void splitClip() {}

	/** Editor Settings section: auto-apply auto-zooms to a fresh recording. */
	default boolean autoApplyFreshRecordingAutoZooms() {
		return true;
	}

	default void setAutoApplyFreshRecordingAutoZooms(boolean enabled) {}

	// ---- Recording library (the Clips panel) ----

	/** The recordings-library rows, newest first ({@code list-recordings}). */
	default java.util.List<dev.gravaai.services.recording.RecordingLibrary.RecordingLibraryEntry>
			listRecordings() {
		return java.util.List.of();
	}

	/** {@code set-recordings-removed}; {@code false} when the operation failed. */
	default boolean setRecordingsRemoved(java.util.List<String> paths, boolean removed) {
		return false;
	}

	/** Lazy 16:9 still for a library row ({@code get-recording-thumbnail}). */
	default java.util.concurrent.CompletableFuture<String> recordingThumbnail(String videoPath) {
		return java.util.concurrent.CompletableFuture.failedFuture(
				new IllegalStateException("Recording thumbnails are not wired"));
	}

	/**
	 * Appends library recordings to the timeline (the import pipeline); exactly one {@code done}
	 * call is made back, on the FX thread.
	 *
	 * @param index target position in the clip sequence; null appends
	 */
	default void addRecordingsToTimeline(java.util.List<String> paths,
			@org.jspecify.annotations.Nullable Integer index,
			java.util.function.Consumer<ClipImportResult> done) {}

	/** Cancels the running clip import (the overlay's Cancel button). */
	default void cancelRecordingImport() {}

	/** Opens the recordings folder in the system file manager. */
	default void openRecordingsFolder() {}

	/** The {@code addToTimeline} outcome the app reports back to the Clips panel. */
	record ClipImportResult(boolean success, int completed, @Nullable String error, boolean cancelled) {
	}
}
