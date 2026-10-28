package dev.gravaai.media.export;

import java.util.List;
import java.util.Locale;

import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportPipelineModel;

/**
 * View-model of the export status line (port of
 * {@code src/components/video-editor/exportStatusModel.ts}, plan §5.10/§5.12).
 */
public record ExportStatusModel(boolean isExportSaving, boolean isExportPreparing,
		boolean isExportFinalizing, boolean isRenderingAudio, Double exportFinalizingProgress,
		Integer exportFinalizingPercent, boolean isExportMuxingAndSaving,
		boolean isExportFinalSaveIndeterminate, boolean isLightningExportInProgress,
		boolean shouldSuspendPreviewRendering, boolean isLegacyExportInProgress,
		String renderSpeedFps, String runtimeLabel, List<String> nativeSkipReasons,
		String nativeSkipLabel) {

	public ExportStatusModel {
		nativeSkipReasons = nativeSkipReasons == null ? List.of() : List.copyOf(nativeSkipReasons);
	}

	/** {@code resolveExportStatusModel}. */
	public static ExportStatusModel resolve(boolean isExporting, ExportProgress exportProgress,
			ExportFormat exportFormat, ExportPipelineModel exportPipelineModel) {
		ExportFormat format = exportFormat == null ? ExportFormat.MP4 : exportFormat;
		ExportPipelineModel model = exportPipelineModel == null
				? ExportPipelineModel.MODERN
				: exportPipelineModel;
		boolean isExportSaving = exportProgress != null
				&& exportProgress.phase() == ExportProgress.Phase.SAVING;
		boolean isExportPreparing = isExporting
				&& (exportProgress == null || exportProgress.phase() == ExportProgress.Phase.PREPARING);
		boolean isExportFinalizing = exportProgress != null
				&& exportProgress.phase() == ExportProgress.Phase.FINALIZING;
		boolean isRenderingAudio = isExportFinalizing && exportProgress.audioProgress() != null;
		double rawFinalizingProgress = exportProgress != null && exportProgress.renderProgress() != null
				? exportProgress.renderProgress()
				: exportProgress == null ? 100 : exportProgress.percentage();
		Double exportFinalizingProgress = isExportFinalizing
				? Math.max(0, Math.min(100,
						Double.isFinite(rawFinalizingProgress) ? rawFinalizingProgress : 0))
				: null;
		Integer exportFinalizingPercent = isExportFinalizing
				? (int) Math.round(exportFinalizingProgress)
				: null;
		boolean isExportMuxingAndSaving = isExportFinalizing
				&& format == ExportFormat.MP4
				&& model == ExportPipelineModel.MODERN
				&& !isRenderingAudio;
		boolean isExportFinalSaveIndeterminate = isExportMuxingAndSaving
				&& (exportFinalizingPercent == null ? 0 : exportFinalizingPercent) >= 98;
		boolean isLightningExportInProgress = format == ExportFormat.MP4
				&& model == ExportPipelineModel.MODERN
				&& (isExporting || exportProgress != null);
		boolean shouldSuspendPreviewRendering = isExporting
				&& format == ExportFormat.MP4
				&& model == ExportPipelineModel.MODERN;
		boolean isLegacyExportInProgress = format == ExportFormat.MP4
				&& model == ExportPipelineModel.LEGACY
				&& (isExporting || exportProgress != null);
		String renderSpeedFps = !isExportPreparing
				&& !isExportFinalizing
				&& !isExportSaving
				&& exportProgress != null
				&& exportProgress.renderFps() != null
				&& Double.isFinite(exportProgress.renderFps())
				&& exportProgress.renderFps() > 0
						? String.format(Locale.ROOT, "%.1f", exportProgress.renderFps())
						: null;
		String runtimeLabel = resolveRuntimeLabel(exportProgress);
		List<String> nativeSkipReasons = exportProgress != null
				&& !exportProgress.nativeStaticLayoutSkipReasons().isEmpty()
						? exportProgress.nativeStaticLayoutSkipReasons()
						: exportProgress != null && exportProgress.nativeStaticLayoutSkipReason() != null
								? List.of(exportProgress.nativeStaticLayoutSkipReason())
								: List.of();
		String nativeSkipLabel = nativeSkipReasons.isEmpty()
				? null
				: "Native skipped: " + nativeSkipReasons.get(0)
						+ (nativeSkipReasons.size() > 1
								? " (+" + (nativeSkipReasons.size() - 1) + " more)"
								: "");
		return new ExportStatusModel(isExportSaving, isExportPreparing, isExportFinalizing,
				isRenderingAudio, exportFinalizingProgress, exportFinalizingPercent,
				isExportMuxingAndSaving, isExportFinalSaveIndeterminate,
				isLightningExportInProgress, shouldSuspendPreviewRendering, isLegacyExportInProgress,
				renderSpeedFps, runtimeLabel, nativeSkipReasons, nativeSkipLabel);
	}

	private static String resolveRuntimeLabel(ExportProgress exportProgress) {
		if (exportProgress == null) {
			return null;
		}
		ExportProgress.RenderBackend renderBackend = exportProgress.renderBackend();
		ExportTuning.EncodeBackend encodeBackend = exportProgress.encodeBackend();
		String encoderName = exportProgress.encoderName();
		if (renderBackend == null && encodeBackend == null && encoderName == null) {
			return null;
		}
		String rendererLabel = renderBackend == ExportProgress.RenderBackend.WEBGPU
				? "WebGPU"
				: renderBackend == ExportProgress.RenderBackend.WEBGL ? "WebGL" : null;
		String encoderLabel = encodeBackend == ExportTuning.EncodeBackend.FFMPEG
				? "FFmpeg"
				: encodeBackend == ExportTuning.EncodeBackend.WEBCODECS ? "WebCodecs" : null;
		String pathLabel = rendererLabel != null && encoderLabel != null
				? rendererLabel + " + " + encoderLabel
				: rendererLabel != null ? rendererLabel : encoderLabel;
		if (pathLabel == null) {
			return encoderName;
		}
		return encoderName != null && !encoderName.isEmpty()
				? pathLabel + " (" + encoderName + ")"
				: pathLabel;
	}
}
