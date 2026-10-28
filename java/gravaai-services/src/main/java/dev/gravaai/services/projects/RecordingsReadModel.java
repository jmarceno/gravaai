package dev.gravaai.services.projects;

import java.io.IOException;
import java.util.List;

/**
 * Read port for the recordings library (Appendix A: {@code list-recordings}).
 *
 * <p>The recording service owns the real implementation (scan plus its trash-undo lifecycle). The
 * dashboard consumes this port so the in-package scan ({@link RecordingLibraryScan}) can be swapped
 * for the service implementation with one wiring line when it lands.
 */
@FunctionalInterface
public interface RecordingsReadModel {

	/**
	 * @param includeSources {@code true} for the raw library (videos and audio); {@code false} for
	 *     recordings only (video files, no webcam sidecars)
	 * @return non-empty files, newest first
	 */
	List<RecordingEntry> list(boolean includeSources) throws IOException;
}
