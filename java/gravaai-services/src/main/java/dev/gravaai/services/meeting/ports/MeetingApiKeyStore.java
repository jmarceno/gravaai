package dev.gravaai.services.meeting.ports;

import java.util.Optional;

/**
 * Stores the meeting API key outside the plain settings file.
 *
 * <p>Electron keeps it encrypted through {@code safeStorage} (the OS keyring) in app-settings.json
 * under "meetingApiKey". The Java app must not write keys in plaintext; the Lead wires a
 * libsecret / Secret-Service implementation via platform. A plain string already present is
 * readable; a keyring-encrypted blob is reported as "not migrated yet" by the adapter.
 */
public interface MeetingApiKeyStore {

	Optional<String> read();

	void write(String key);

	void clear();
}
