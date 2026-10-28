package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of the caching half of {@code electron/automations/duration.test.ts}. The TS parser of
 * {@code ffmpeg -i} output is not ported: plan §5.15 replaces it with {@code MediaProbe}, so only
 * the probe port and its cache remain here (the ffmpeg-text tests are N/A, see the report).
 */
class AutomationDurationTest {

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() {
		AutomationDuration.clearAutomationDurationCache();
	}

	@Test
	@DisplayName("probeAutomationDurationSec caches per size and modification time")
	void cachesByFingerprint() throws IOException {
		Path file = tempDir.resolve("clip.mp4");
		Files.writeString(file, "x");
		Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000));
		AtomicInteger calls = new AtomicInteger();
		AutomationDurationProbe probe = path -> {
			calls.incrementAndGet();
			return 42.0;
		};

		assertEquals(42.0, AutomationDuration.probeAutomationDurationSec(file, probe));
		assertEquals(42.0, AutomationDuration.probeAutomationDurationSec(file, probe));
		assertEquals(1, calls.get(), "second probe must be cached");

		Files.setLastModifiedTime(file, FileTime.fromMillis(2_000_000));
		assertEquals(42.0, AutomationDuration.probeAutomationDurationSec(file, probe));
		assertEquals(2, calls.get(), "a changed modification time must re-probe");
	}

	@Test
	@DisplayName("probeAutomationDurationSec returns null for a missing file without probing")
	void missingFile() {
		AtomicInteger calls = new AtomicInteger();
		assertNull(AutomationDuration.probeAutomationDurationSec(
				tempDir.resolve("missing.mp4"), path -> {
					calls.incrementAndGet();
					return 12.0;
				}));
		assertEquals(0, calls.get());
	}

	@Test
	@DisplayName("probeAutomationDurationSec caches a failed probe as unknown")
	void cachesFailure() throws IOException {
		Path file = tempDir.resolve("broken.mp4");
		Files.writeString(file, "x");
		AtomicInteger calls = new AtomicInteger();
		AutomationDurationProbe probe = path -> {
			calls.incrementAndGet();
			throw new IOException("probe failed");
		};
		assertNull(AutomationDuration.probeAutomationDurationSec(file, probe));
		assertNull(AutomationDuration.probeAutomationDurationSec(file, probe));
		assertEquals(1, calls.get());
	}

	@Test
	@DisplayName("probeAutomationDurationSec caches a null duration")
	void cachesNull() throws IOException {
		Path file = tempDir.resolve("audio.m4a");
		Files.writeString(file, "x");
		AtomicInteger calls = new AtomicInteger();
		AutomationDurationProbe probe = path -> {
			calls.incrementAndGet();
			return null;
		};
		assertNull(AutomationDuration.probeAutomationDurationSec(file, probe));
		assertNull(AutomationDuration.probeAutomationDurationSec(file, probe));
		assertEquals(1, calls.get());
	}
}
