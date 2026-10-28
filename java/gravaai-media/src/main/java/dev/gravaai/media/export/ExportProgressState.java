package dev.gravaai.media.export;

/** Progress-state helpers (port of {@code src/components/video-editor/exportProgressState.ts}). */
public final class ExportProgressState {

	private ExportProgressState() {}

	/** {@code resolveSavingExportProgress}: the progress snapshot shown while files are written. */
	public static ExportProgress resolveSavingExportProgress(ExportProgress previous) {
		int currentFrame = previous != null ? previous.totalFrames() : 1;
		int totalFrames = previous != null ? previous.totalFrames() : 1;
		return new ExportProgress(currentFrame, totalFrames, 100, 0,
				previous == null ? null : previous.renderFps(),
				previous == null ? null : previous.renderBackend(),
				previous == null ? null : previous.encodeBackend(),
				previous == null ? null : previous.encoderName(), null, java.util.List.of(),
				ExportProgress.Phase.SAVING, null, null);
	}
}
