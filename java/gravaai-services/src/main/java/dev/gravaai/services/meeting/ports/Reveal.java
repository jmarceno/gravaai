package dev.gravaai.services.meeting.ports;

import java.nio.file.Path;

/** Shows a file in the user's file manager (org.freedesktop.FileManager1). */
public interface Reveal {

	void revealInFolder(Path path);
}
