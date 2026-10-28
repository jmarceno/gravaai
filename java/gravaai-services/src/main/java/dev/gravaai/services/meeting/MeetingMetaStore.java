package dev.gravaai.services.meeting;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and patches {@code <recording>.meeting.json} while preserving keys this build does not
 * know about (electron/ipc/meeting/library.ts readJson/updateMeetingMeta).
 *
 * <p>Electron deletes a key by spreading {@code undefined}, which JSON.stringify drops; the Java
 * equivalent is the {@link #REMOVE} sentinel in a patch.
 */
public final class MeetingMetaStore {

	/** Patch value that removes a key. */
	public static final Object REMOVE = new Object();

	public Map<String, Object> readRaw(Path mediaPath) {
		return JsonFiles.readObjectOrEmpty(MeetingFiles.meetingFilesFor(mediaPath).meta());
	}

	public MeetingMeta read(Path mediaPath) {
		return MeetingMeta.fromRaw(readRaw(mediaPath));
	}

	/** Applies a patch atomically and returns the new raw object (unknown keys preserved). */
	public Map<String, Object> update(Path mediaPath, Map<String, Object> patch) {
		Map<String, Object> next = new LinkedHashMap<>(readRaw(mediaPath));
		for (Map.Entry<String, Object> entry : patch.entrySet()) {
			if (entry.getValue() == REMOVE) {
				next.remove(entry.getKey());
			} else {
				next.put(entry.getKey(), entry.getValue());
			}
		}
		JsonFiles.writeJsonAtomic(MeetingFiles.meetingFilesFor(mediaPath).meta(), next);
		return next;
	}
}
