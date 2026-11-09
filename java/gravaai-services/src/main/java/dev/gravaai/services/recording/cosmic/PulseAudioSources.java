package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * PulseAudio source selection via {@code pactl}, ported from the audio half of
 * {@code electron/ipc/recording/cosmic.ts}.
 *
 * <p>Chromium reports a device by its human-readable description, while {@code parec} needs the
 * source name, so the listing is matched by description and falls back to the default source.
 */
final class PulseAudioSources {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	/** The default source name {@code parec} understands. */
	static final String DEFAULT_SOURCE = "@DEFAULT_SOURCE@";

	/** The monitor source that carries what the system plays. */
	static final String DEFAULT_MONITOR = "@DEFAULT_MONITOR@";

	private final RecordingEnvironment environment;
	private final CosmicProcessIo processIo;

	PulseAudioSources(RecordingEnvironment environment, CosmicProcessIo processIo) {
		this.environment = environment;
		this.processIo = processIo;
	}

	/** Raises the monitor's own volume so captured system audio is not near-silent. */
	void unmuteDefaultMonitor() {
		// The monitor's own volume scales what capture apps get, not what you hear; a low value
		// there records near-silence.
		try {
			processIo.runAndCapture(List.of(
					environment.command("pactl").toString(),
					"set-source-volume", DEFAULT_MONITOR, "100%"));
		} catch (IOException | RuntimeException error) {
			// Best effort, like the TS .catch(() => undefined).
		}
	}

	/**
	 * The PulseAudio source whose description matches Chromium's device label.
	 *
	 * @param label the device label, or null when the default source should be used
	 */
	String resolveMicrophone(@Nullable String label) {
		if (label == null || label.isBlank() || "default".equalsIgnoreCase(label)) {
			return DEFAULT_SOURCE;
		}
		try {
			byte[] stdout = processIo.runAndCapture(List.of(
					environment.command("pactl").toString(), "-f", "json", "list", "sources"));
			JsonNode sources = Json.parseJsonWithByteOrderMark(new String(stdout, StandardCharsets.UTF_8));
			String name = pickMicrophone(sources, label);
			return name != null ? name : DEFAULT_SOURCE;
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not list PulseAudio sources: " + error);
			return DEFAULT_SOURCE;
		}
	}

	/**
	 * The matching source name from {@code pactl -f json list sources} output, or null.
	 *
	 * @param sources the parsed listing, or null when the listing could not be read
	 */
	static @Nullable String pickMicrophone(@Nullable JsonNode sources, String label) {
		if (sources == null || !sources.isArray()) {
			return null;
		}
		for (JsonNode source : sources) {
			JsonNode name = source.get("name");
			JsonNode description = source.get("description");
			if (name == null || !name.isString() || description == null || !description.isString()) {
				continue;
			}
			String descriptionText = description.asString();
			if (descriptionText.equals(label)
					|| label.contains(descriptionText)
					|| descriptionText.contains(label)) {
				return name.asString();
			}
		}
		return null;
	}
}
