package dev.gravaai.services.meeting.ports;

import java.nio.file.Path;

/** The recordings directory, honouring the user's custom location (electron/ipc/utils.ts). */
public interface RecordingDirectories {

	Path recordingsDirectory();
}
