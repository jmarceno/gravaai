package dev.gravaai.ui.editor.export;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportPipelineModel;
import dev.gravaai.media.export.ExportCancelledException;
import dev.gravaai.media.export.ExportProgress;
import dev.gravaai.media.export.ExportStatusModel;
import dev.gravaai.ui.i18n.UiI18n;

/**
 * View-model of the export progress panel (port of
 * {@code src/components/video-editor/export/useExportStatusViewModel.ts}, plan §5.12). The labels
 * resolve through {@code UiI18n} at call time, so a locale switch shows up on the next update.
 */
public final class ExportProgressViewModel {

	private boolean exporting;
	private @Nullable ExportProgress progress;
	private ExportFormat format = ExportFormat.MP4;
	private ExportPipelineModel pipelineModel = ExportPipelineModel.MODERN;
	private ExportStatusModel status = ExportStatusModel.resolve(false, null, ExportFormat.MP4,
			ExportPipelineModel.MODERN);

	/** Replaces the snapshot. */
	public void update(boolean exporting, @Nullable ExportProgress progress,
			@Nullable ExportFormat format, ExportPipelineModel pipelineModel) {
		this.exporting = exporting;
		this.progress = progress;
		this.format = format == null ? ExportFormat.MP4 : format;
		this.pipelineModel = pipelineModel == null ? ExportPipelineModel.MODERN : pipelineModel;
		this.status = ExportStatusModel.resolve(exporting, progress, this.format, this.pipelineModel);
	}

	public boolean isExporting() {
		return exporting;
	}

	public @Nullable ExportProgress progress() {
		return progress;
	}

	public ExportFormat format() {
		return format;
	}

	public ExportPipelineModel pipelineModel() {
		return pipelineModel;
	}

	public ExportStatusModel status() {
		return status;
	}

	/** {@code exportPercentLabel}. */
	public String exportPercentLabel() {
		if (progress == null) {
			return UiI18n.t("editor:exportStatus.preparing");
		}
		if (status.isExportPreparing()) {
			return UiI18n.t("editor:exportStatus.preparing");
		}
		if (status.isExportSaving()) {
			return UiI18n.t("dialogs:export.openingSaveDialog");
		}
		if (status.isRenderingAudio()) {
			return UiI18n.t("dialogs:export.renderingAudio",
					java.util.Map.of("percent", Math.round(audioProgressOrZero() * 100)));
		}
		if (status.isExportFinalizing()) {
			if (format == ExportFormat.MP4 && pipelineModel == ExportPipelineModel.MODERN) {
				if (status.isExportFinalSaveIndeterminate()) {
					return UiI18n.t("dialogs:export.muxingSavingFile");
				}
				return UiI18n.t("dialogs:export.muxingSaving",
						java.util.Map.of("percent", status.exportFinalizingPercent() == null
								? 100
								: status.exportFinalizingPercent()));
			}
			return UiI18n.t("dialogs:export.finalizing",
					java.util.Map.of("percent", status.exportFinalizingPercent() == null
							? 100
							: status.exportFinalizingPercent()));
		}
		return UiI18n.t("editor:exportStatus.completePercent",
				java.util.Map.of("percent", Math.round(progress.percentage())));
	}

	/** {@code exportRenderSpeedLabel}. */
	public @Nullable String exportRenderSpeedLabel() {
		if (status.renderSpeedFps() == null) {
			return null;
		}
		String label = UiI18n.t("dialogs:export.renderSpeed",
				java.util.Map.of("fps", status.renderSpeedFps()));
		double remaining = progress == null ? 0 : progress.estimatedTimeRemaining();
		if (Double.isFinite(remaining) && remaining >= 1) {
			label += " · " + UiI18n.t("dialogs:export.timeLeft",
					java.util.Map.of("time", formatRemaining(remaining)));
		}
		return label;
	}

	/** {@code m:ss}, or {@code h:mm:ss} from one hour. */
	static String formatRemaining(double seconds) {
		long total = Math.round(seconds);
		long hours = total / 3600;
		long minutes = total / 60 % 60;
		long secs = total % 60;
		return hours > 0
				? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
				: String.format(java.util.Locale.ROOT, "%d:%02d", minutes, secs);
	}

	/** The 0..1 value of the progress bar. */
	public double progressFraction() {
		if (progress == null) {
			return 0;
		}
		double value;
		if (status.isRenderingAudio()) {
			value = audioProgressOrZero();
		} else if (status.isExportFinalizing()) {
			value = status.exportFinalizingProgress() == null
					? 0
					: status.exportFinalizingProgress() / 100.0;
		} else {
			value = progress.percentage() / 100.0;
		}
		return Math.max(0, Math.min(1, value));
	}

	/** Whether the panel should offer a cancel button. */
	public boolean canCancel() {
		return exporting;
	}

	/** Parse-safe failure text; cancellations are not failures. */
	public static @Nullable String errorText(Throwable error) {
		if (error == null || error instanceof ExportCancelledException) {
			return null;
		}
		String message = error.getMessage();
		return message == null || message.isBlank()
				? UiI18n.t("dialogs:export.exportFailed")
				: UiI18n.t("dialogs:export.failedWithError",
						java.util.Map.of("error", message));
	}

	private double audioProgressOrZero() {
		return progress == null || progress.audioProgress() == null ? 0 : progress.audioProgress();
	}
}
