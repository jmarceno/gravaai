package dev.gravaai.services.meeting;

import java.nio.file.Path;
import java.util.Map;

/**
 * Reads and patches {@code <recording>.meeting.json} while preserving keys this build does not
 * know about (electron/ipc/meeting/library.ts readJson/updateMeetingMeta).
 */
public final class MeetingMetaStore {

	public Map<String, Object> readRaw(Path mediaPath) {
		return JsonFiles.readObjectOrEmpty(MeetingFiles.meetingFilesFor(mediaPath).meta());
	}

	public MeetingMeta read(Path mediaPath) {
		return MeetingMeta.fromRaw(readRaw(mediaPath));
	}

	/** Applies a patch atomically and returns the new raw object (unknown keys preserved). */
	public Map<String, Object> update(Path mediaPath, MeetingMetaPatch patch) {
		Map<String, Object> next = patch.applyTo(readRaw(mediaPath));
		JsonFiles.writeJsonAtomic(MeetingFiles.meetingFilesFor(mediaPath).meta(), next);
		return next;
	}
}
