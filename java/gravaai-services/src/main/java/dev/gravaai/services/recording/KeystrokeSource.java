package dev.gravaai.services.recording;

import java.util.List;

import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;

/** The in-memory key-press buffer the recorder empties when it saves a recording. */
public interface KeystrokeSource {

	List<KeystrokeEvent> takeRecordedKeystrokes();
}
