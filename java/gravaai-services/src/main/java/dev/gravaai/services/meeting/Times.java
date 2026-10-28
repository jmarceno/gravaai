package dev.gravaai.services.meeting;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Timestamps in the format Electron writes with {@code new Date().toISOString()}. */
public final class Times {

	private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter
			.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
			.withZone(ZoneOffset.UTC);

	private Times() {
	}

	public static String isoNow(Clock clock) {
		return iso(clock.instant());
	}

	public static String iso(Instant instant) {
		return ISO_MILLIS.format(instant);
	}
}
