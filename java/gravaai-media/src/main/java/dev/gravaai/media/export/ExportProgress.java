package dev.gravaai.media.export;

import java.util.List;

import dev.gravaai.media.export.ExportTuning.EncodeBackend;

/**
 * Progress snapshot of a running export (port of the {@code ExportProgress} interface of
 * {@code src/lib/exporter/types.ts}, plan §5.10). Fields are nullable exactly like the TS optional
 * fields; the exporter/UI fill them as the phases advance.
 */
public record ExportProgress(int currentFrame, int totalFrames, double percentage,
		double estimatedTimeRemaining, Double renderFps, RenderBackend renderBackend,
		EncodeBackend encodeBackend, String encoderName, String nativeStaticLayoutSkipReason,
		List<String> nativeStaticLayoutSkipReasons, Phase phase, Double renderProgress,
		Double audioProgress) {

	/** {@code ExportProgress.phase}. */
	public enum Phase {
		PREPARING("preparing"),
		EXTRACTING("extracting"),
		FINALIZING("finalizing"),
		SAVING("saving");

		private final String wire;

		Phase(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	/** {@code ExportRenderBackend}. */
	public enum RenderBackend {
		WEBGPU("webgpu"),
		WEBGL("webgl");

		private final String wire;

		RenderBackend(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	public ExportProgress {
		nativeStaticLayoutSkipReasons = nativeStaticLayoutSkipReasons == null
				? List.of()
				: List.copyOf(nativeStaticLayoutSkipReasons);
	}

	/** A plain render-loop snapshot. */
	public static ExportProgress of(int currentFrame, int totalFrames, double percentage,
			double estimatedTimeRemaining) {
		return new ExportProgress(currentFrame, totalFrames, percentage, estimatedTimeRemaining, null,
				null, null, null, null, List.of(), null, null, null);
	}

	public ExportProgress withPhase(Phase nextPhase) {
		return new ExportProgress(currentFrame, totalFrames, percentage, estimatedTimeRemaining,
				renderFps, renderBackend, encodeBackend, encoderName, nativeStaticLayoutSkipReason,
				nativeStaticLayoutSkipReasons, nextPhase, renderProgress, audioProgress);
	}

	public ExportProgress withRenderProgress(double nextRenderProgress) {
		return new ExportProgress(currentFrame, totalFrames, percentage, estimatedTimeRemaining,
				renderFps, renderBackend, encodeBackend, encoderName, nativeStaticLayoutSkipReason,
				nativeStaticLayoutSkipReasons, phase, nextRenderProgress, audioProgress);
	}

	public ExportProgress withAudioProgress(double nextAudioProgress) {
		return new ExportProgress(currentFrame, totalFrames, percentage, estimatedTimeRemaining,
				renderFps, renderBackend, encodeBackend, encoderName, nativeStaticLayoutSkipReason,
				nativeStaticLayoutSkipReasons, phase, renderProgress, nextAudioProgress);
	}

	public ExportProgress withRenderFps(double nextRenderFps) {
		return new ExportProgress(currentFrame, totalFrames, percentage, estimatedTimeRemaining,
				nextRenderFps, renderBackend, encodeBackend, encoderName, nativeStaticLayoutSkipReason,
				nativeStaticLayoutSkipReasons, phase, renderProgress, audioProgress);
	}

	public ExportProgress withBackends(RenderBackend nextRenderBackend,
			EncodeBackend nextEncodeBackend) {
		return new ExportProgress(currentFrame, totalFrames, percentage, estimatedTimeRemaining,
				renderFps, nextRenderBackend, nextEncodeBackend, encoderName,
				nativeStaticLayoutSkipReason, nativeStaticLayoutSkipReasons, phase, renderProgress,
				audioProgress);
	}

	public ExportProgress withEncoderName(String nextEncoderName) {
		return new ExportProgress(currentFrame, totalFrames, percentage, estimatedTimeRemaining,
				renderFps, renderBackend, encodeBackend, nextEncoderName,
				nativeStaticLayoutSkipReason, nativeStaticLayoutSkipReasons, phase, renderProgress,
				audioProgress);
	}

	public ExportProgress withSkipReasons(String singleReason, List<String> reasons) {
		return new ExportProgress(currentFrame, totalFrames, percentage, estimatedTimeRemaining,
				renderFps, renderBackend, encodeBackend, encoderName, singleReason,
				reasons == null ? List.of() : reasons, phase, renderProgress, audioProgress);
	}
}
