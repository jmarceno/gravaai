package dev.gravaai.media.export;

/** Receives throttled progress snapshots from an export encoder. */
@FunctionalInterface
public interface ExportProgressListener {

	ExportProgressListener NONE = progress -> {};

	void onProgress(ExportProgress progress);
}
