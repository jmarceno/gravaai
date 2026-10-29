package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Duration probing for duration conditions (port of {@code electron/automations/duration.ts}).
 *
 * <p>Duration is only probed when a rule asks for it. The cache key includes the file size and
 * modification time so a re-exported file with the same path is re-probed.
 */
public final class AutomationDuration {

	private static final System.Logger LOG = System.getLogger("gravaai.automations");

	private record CacheEntry(String fingerprint, @Nullable Double durationSec) {}

	private static final Map<Path, CacheEntry> DURATION_CACHE = new ConcurrentHashMap<>();

	private AutomationDuration() {}

	public static @Nullable Double probeAutomationDurationSec(Path filePath, AutomationDurationProbe probe) {
		String fingerprint;
		try {
			BasicFileAttributes attributes = Files.readAttributes(filePath, BasicFileAttributes.class);
			fingerprint = attributes.size() + ":" + attributes.lastModifiedTime().toMillis();
			CacheEntry cached = DURATION_CACHE.get(filePath);
			if (cached != null && cached.fingerprint().equals(fingerprint)) {
				return cached.durationSec();
			}
		} catch (IOException error) {
			return null;
		}
		Double durationSec = null;
		try {
			durationSec = probe.probeDurationSec(filePath);
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING, "[automations] Duration probe failed: " + error);
		}
		DURATION_CACHE.put(filePath, new CacheEntry(fingerprint, durationSec));
		return durationSec;
	}

	public static void clearAutomationDurationCache() {
		DURATION_CACHE.clear();
	}
}
