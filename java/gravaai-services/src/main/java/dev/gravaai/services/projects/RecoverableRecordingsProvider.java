package dev.gravaai.services.projects;

import java.util.List;

/**
 * Read port for the recoverable-recordings banner (Appendix A: {@code list-recoverable-recordings}).
 *
 * <p>The recovery logic ({@code cosmicSession.ts}: manifest parsing, pid liveness, empty-session
 * cleanup) belongs to the recording service. The dashboard only needs this read view, so the
 * banner is wired to the port and the recording service supplies the implementation when it
 * lands; until then the wiring passes an empty list (Lead decision, task-9).
 */
@FunctionalInterface
public interface RecoverableRecordingsProvider {

	List<RecoverableRecording> list();
}
