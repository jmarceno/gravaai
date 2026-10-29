package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportPipelineModel;

/**
 * View-model of the export status line (port of
 * {@code src/components/video-editor/exportStatusModel.ts}, plan §5.10/§5.12).
 */
public record ExportStatusModel(boolean isExportSaving, boolean isExportPreparing,
		boolean isExportFinalizing, boolean isRenderingAudio, @Nullable Double exportFinalizingProgress,
		@Nullable Integer exportFinalizingPercent, boolean isExportMuxingAndSaving,
		boolean isExportFinalSaveIndeterminate, boolean isLightningExportInProgress,
		boolean shouldSuspendPreviewRendering, boolean isLegacyExportInProgress,
		@Nullable String renderSpeedFps, @Nullable String runtimeLabel, List<String> nativeSkipReasons,
		@Nullable String nativeSkipLabel) {

	public ExportStatusModel {
		nativeSkipReasons = nativeSkipReasons == null ? List.of() : List.copyOf(nativeSkipReasons);
	}

	/**
	 * {@code resolveExportStatusModel}.
	 *
	 * @param exportProgress the latest progress snapshot, or {@code null} when no export is running
	 * @param exportFormat the selected export format, or {@code null} for the MP4 default
	 * @param exportPipelineModel the selected pipeline model, or {@code null} for the modern default
	 * @return the status model, never {@code null}
	 */
	public static ExportStatusModel resolve(boolean isExporting,
			@Nullable ExportProgress exportProgress, @Nullable ExportFormat exportFormat,
			@Nullable ExportPipelineModel exportPipelineModel) {
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
		boolean isRenderingAudio = exportProgress != null
				&& exportProgress.phase() == ExportProgress.Phase.FINALIZING
				&& exportProgress.audioProgress() != null;
		double rawFinalizingProgress = 100;
		if (exportProgress != null) {
			Double renderProgress = exportProgress.renderProgress();
			rawFinalizingProgress = renderProgress != null
					? renderProgress
					: exportProgress.percentage();
		}
		double finalizingProgress = Math.max(0, Math.min(100,
				Double.isFinite(rawFinalizingProgress) ? rawFinalizingProgress : 0));
		Double exportFinalizingProgress = isExportFinalizing ? finalizingProgress : null;
		Integer exportFinalizingPercent = isExportFinalizing
				? (int) Math.round(finalizingProgress)
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
		List<String> nativeSkipReasons = List.of();
		if (exportProgress != null) {
			List<String> skipReasons = exportProgress.nativeStaticLayoutSkipReasons();
			if (skipReasons != null && !skipReasons.isEmpty()) {
				nativeSkipReasons = skipReasons;
			} else {
				String skipReason = exportProgress.nativeStaticLayoutSkipReason();
				if (skipReason != null) {
					nativeSkipReasons = List.of(skipReason);
				}
			}
		}
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

	private static @Nullable String resolveRuntimeLabel(@Nullable ExportProgress exportProgress) {
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
