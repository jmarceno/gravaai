package dev.gravaai.services.recording;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The countdown delay setting, ported from the {@code get/set-countdown-delay} handlers in
 * {@code electron/ipc/register/settings.ts} (Appendix A {@code CountdownService}).
 *
 * <p>{@code countdown-settings.json} holds {@code { "delay": <seconds> }}; a missing or invalid
 * file reads as the default 3 seconds. Setting it broadcasts {@code countdown-delay-changed}.
 */
public final class CountdownDelayStore {

	/** The TS default when the file is missing or unreadable. */
	public static final double DEFAULT_DELAY_SECONDS = 3;

	private final Path file;
	private final List<Consumer<Double>> listeners = new CopyOnWriteArrayList<>();

	public CountdownDelayStore(Path file) {
		this.file = file;
	}

	/** {@code get-countdown-delay}: {@code parsed.delay ?? 3}. */
	public double getCountdownDelay() {
		try {
			JsonNode parsed = Json.parseJsonWithByteOrderMark(
					Files.readString(file, StandardCharsets.UTF_8));
			JsonNode delay = parsed.get("delay");
			if (delay != null && delay.isNumber() && Double.isFinite(delay.asDouble())) {
				return delay.asDouble();
			}
		} catch (IOException | RuntimeException error) {
			// Missing or invalid settings read as the default, like the TS catch branch.
		}
		return DEFAULT_DELAY_SECONDS;
	}

	/** {@code set-countdown-delay}: writes the file and broadcasts the change. */
	public void setCountdownDelay(double delay) throws IOException {
		ObjectNode settings = Json.nodeFactory().objectNode();
		settings.put("delay", delay);
		Json.writeFile(file, settings);
		for (Consumer<Double> listener : listeners) {
			listener.accept(delay);
		}
	}

	/** Subscribes to {@code countdown-delay-changed}; close the handle to unsubscribe. */
	public AutoCloseable onCountdownDelayChanged(Consumer<Double> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}
}
