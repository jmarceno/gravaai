package dev.gravaai.ui.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/** Date/number formatting the React dashboard does with {@code toLocaleDateString} and friends. */
public final class DashboardDates {

	private static final DateTimeFormatter SHORT_DATE =
			DateTimeFormatter.ofPattern("MMM d", Locale.getDefault());

	private DashboardDates() {
	}

	/** Card caption date: {@code { month: "short", day: "numeric" }}. */
	public static String shortDate(long epochMillis) {
		return SHORT_DATE.format(LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
	}

	/** Banner date: {@code { dateStyle: "medium", timeStyle: "short" }}; falls back to the raw value. */
	public static String mediumDateTime(String isoCreatedAt) {
		if (isoCreatedAt == null || isoCreatedAt.isBlank()) {
			return "";
		}
		try {
			LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.parse(isoCreatedAt), ZoneId.systemDefault());
			return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
					.withLocale(Locale.getDefault())
					.format(dateTime);
		} catch (RuntimeException error) {
			return isoCreatedAt;
		}
	}
}
