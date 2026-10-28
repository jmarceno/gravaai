package dev.gravaai.services.recording.ports;

import java.io.IOException;
import java.nio.file.Path;

import dev.gravaai.platform.trash.Trash;
import dev.gravaai.services.recording.RecordingLibrary;

/** {@link RecordingLibrary.Trash} backed by the freedesktop Trash implementation (plan §5.5). */
public final class PlatformRecordingTrash implements RecordingLibrary.Trash {

	private final Trash trash;

	public PlatformRecordingTrash() {
		this(Trash.defaultTrash());
	}

	public PlatformRecordingTrash(Trash trash) {
		this.trash = trash;
	}

	@Override
	public void trashItem(Path path) throws IOException {
		trash.trash(path);
	}
}
