package dev.gravaai.ui.editor.export;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.media.export.CaptionSidecars;
import dev.gravaai.media.export.ExportProgress;

/**
 * State of the editor export popover (port of {@code useExportSession.ts} plus the screen mapping
 * of {@code EditorExportMenu.tsx}, plan §5.10/§5.12). Plain state without JavaFX so tests can drive
 * it like the React hook: views subscribe with {@link #onChange(Runnable)} and re-render, the
 * app-side controller mutates it on the FX thread.
 */
public final class ExportSessionModel {

	/** The four popover states of the React export menu. */
	public enum Screen { SETTINGS, PROGRESS, ERROR, COMPLETE }

	/**
	 * A rendered export waiting to be saved (port of {@code PendingExportSave}).
	 *
	 * @param captionSidecar the caption sidecar payload, or null when the export has no captions
	 */
	public record PendingExportSave(String fileName, Path stagedFile,
			CaptionSidecars.@Nullable Payload captionSidecar) {}

	private boolean exporting;
	private @Nullable ExportProgress progress;
	private @Nullable String error;
	private boolean dropdownOpen;
	private @Nullable String exportedFilePath;
	private boolean hasPendingExportSave;
	private @Nullable PendingExportSave pendingExportSave;
	private int exportRunId;
	private @Nullable Integer cancelledExportRunId;
	private final List<Runnable> listeners = new ArrayList<>();
	private boolean notifying;

	/** Subscribes a re-render callback (fired after every state change). */
	public void onChange(Runnable listener) {
		listeners.add(listener);
	}

	private void changed() {
		if (notifying) {
			return;
		}
		notifying = true;
		try {
			for (Runnable listener : List.copyOf(listeners)) {
				listener.run();
			}
		} finally {
			notifying = false;
		}
	}

	public boolean isExporting() {
		return exporting;
	}

	public void setExporting(boolean exporting) {
		if (this.exporting != exporting) {
			this.exporting = exporting;
			changed();
		}
	}

	public @Nullable ExportProgress progress() {
		return progress;
	}

	public void setProgress(@Nullable ExportProgress progress) {
		if (this.progress != progress) {
			this.progress = progress;
			changed();
		}
	}

	public @Nullable String error() {
		return error;
	}

	public void setError(@Nullable String error) {
		if (!java.util.Objects.equals(this.error, error)) {
			this.error = error;
			changed();
		}
	}

	public boolean isDropdownOpen() {
		return dropdownOpen;
	}

	public void setDropdownOpen(boolean open) {
		if (this.dropdownOpen != open) {
			this.dropdownOpen = open;
			changed();
		}
	}

	public @Nullable String exportedFilePath() {
		return exportedFilePath;
	}

	public void setExportedFilePath(@Nullable String exportedFilePath) {
		if (!java.util.Objects.equals(this.exportedFilePath, exportedFilePath)) {
			this.exportedFilePath = exportedFilePath;
			changed();
		}
	}

	public boolean hasPendingExportSave() {
		return hasPendingExportSave;
	}

	public @Nullable PendingExportSave pendingExportSave() {
		return pendingExportSave;
	}

	/** Stores a rendered export as the pending save and switches to the pending-save error state. */
	public void setPendingExportSave(PendingExportSave pendingExportSave) {
		this.pendingExportSave = pendingExportSave;
		this.hasPendingExportSave = pendingExportSave != null && pendingExportSave.stagedFile() != null;
		changed();
	}

	/** {@code clearPendingExportSave}: drops the pending save; the caller discards the staged file. */
	public @Nullable PendingExportSave clearPendingExportSave() {
		PendingExportSave discarded = pendingExportSave;
		pendingExportSave = null;
		if (hasPendingExportSave) {
			hasPendingExportSave = false;
			changed();
		}
		return discarded;
	}

	/** Starts a new run: bumps the run id and clears the cancelled marker. */
	public int beginExportRun() {
		exportRunId++;
		cancelledExportRunId = null;
		return exportRunId;
	}

	/** Marks the current run cancelled (late callbacks with its id are ignored). */
	public void cancelRun() {
		cancelledExportRunId = exportRunId;
		exportRunId++;
	}

	/** {@code true} while {@code runId} is the active run. */
	public boolean isCurrentRun(int runId) {
		return exportRunId == runId;
	}

	/** {@code exportWasCancelled}: {@code runId} was explicitly cancelled. */
	public boolean isCancelledRun(int runId) {
		return cancelledExportRunId != null && cancelledExportRunId == runId;
	}

	/** {@code markExportAsSaving}: pins the progress bar at 100% with the saving phase. */
	public void markExportAsSaving() {
		ExportProgress previous = progress;
		int frames = previous == null ? 1
				: previous.totalFrames() != 0 ? previous.totalFrames() : previous.currentFrame();
		progress = new ExportProgress(frames, frames, 100, 0,
				previous == null ? null : previous.renderFps(),
				previous == null ? null : previous.renderBackend(),
				previous == null ? null : previous.encodeBackend(),
				previous == null ? null : previous.encoderName(),
				previous == null ? null : previous.nativeStaticLayoutSkipReason(),
				previous == null ? null : previous.nativeStaticLayoutSkipReasons(),
				ExportProgress.Phase.SAVING,
				previous == null ? null : previous.renderProgress(),
				previous == null ? null : previous.audioProgress());
		changed();
	}

	/** The popover screen for the current state ({@code EditorExportMenu} mapping). */
	public Screen screen() {
		if (exporting) {
			return Screen.PROGRESS;
		}
		if (error != null) {
			return Screen.ERROR;
		}
		if (exportedFilePath != null) {
			return Screen.COMPLETE;
		}
		return Screen.SETTINGS;
	}
}
