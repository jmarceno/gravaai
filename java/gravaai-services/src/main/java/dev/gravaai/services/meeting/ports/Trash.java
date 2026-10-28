package dev.gravaai.services.meeting.ports;

import java.nio.file.Path;

/** Moves files to the system trash so deleting a meeting is recoverable (plan §5.5). */
public interface Trash {

	void trash(Path path);
}
