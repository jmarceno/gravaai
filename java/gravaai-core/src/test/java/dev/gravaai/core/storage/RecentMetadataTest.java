package dev.gravaai.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code electron/ipc/project/recentMetadata.test.ts}. */
class RecentMetadataTest {

	@Test
	@DisplayName("reports a metadata warning without rejecting an already committed operation")
	void reportsWarning() {
		List<LogRecord> records = new ArrayList<>();
		Logger logger = Logger.getLogger("gravaai.storage");
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				records.add(record);
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		};
		logger.addHandler(handler);
		try {
			Optional<String> result = RecentMetadata.persist(() -> {
				throw new IllegalStateException("disk full");
			});

			assertTrue(result.isPresent());
			assertTrue(result.get().contains("operation completed"));
			assertTrue(result.get().contains("disk full"));
			assertEquals(1, records.size());
			assertTrue(records.get(0).getMessage().contains("disk full"));
		} finally {
			logger.removeHandler(handler);
		}
	}

	@Test
	@DisplayName("has no warning after a successful metadata update")
	void hasNoWarningOnSuccess() {
		AtomicBoolean called = new AtomicBoolean();
		Optional<String> result = RecentMetadata.persist(() -> called.set(true));

		assertTrue(result.isEmpty());
		assertTrue(called.get());
	}
}
