package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * Meeting labels and formatters shared by the UI and the document export. The UI layer used to own
 * these; the export needs the exact same title and duration, so they live here and the UI's
 * {@code MeetingText} delegates, keeping one definition of each rule.
 *
 * <p>The one UI-owned input is the localized fallback label ("Meeting"), which the caller passes
 * in: services have no dependency on the translation lookup.
 */
public final class MeetingTexts {

	private static final DateTimeFormatter TIME =
			DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault());

	private MeetingTexts() {
	}

	/**
	 * The meeting's display title: the saved title, else the creation date/time, else the fallback
	 * label.
	 *
	 * @param meta the meeting metadata, or null when it could not be read
	 * @param fallbackMs a creation time to use when the metadata has none, or null
	 * @param fallbackLabel the label for a meeting with no title and no date ("Meeting")
	 */
	public static String meetingTitle(@Nullable MeetingMeta meta, @Nullable Long fallbackMs,
			String fallbackLabel) {		if (meta != null && meta.title() != null && !meta.title().trim().isEmpty()) {
			return meta.title().trim();
		}
		Long created = meta == null ? null : epochMillis(meta.createdAt());
		if (created == null) {
			created = fallbackMs;
		}
		if (created == null) {
			return fallbackLabel;
		}
		LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(created),
				ZoneId.systemDefault());
		return fallbackLabel + " " + DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
				.withLocale(Locale.getDefault()).format(date) + " " + TIME.format(date);
	}

	/** The ISO creation time as epoch milliseconds, or null when absent or unreadable. */
	static @Nullable Long epochMillis(@Nullable String iso) {
		if (iso == null || iso.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(iso).toEpochMilli();
		} catch (RuntimeException error) {
			return null;
		}
	}

	/** "1 h 5 min", "5 min 3 s", "3 s", or null when the duration is unknown or zero. */
	public static @Nullable String formatDuration(@Nullable Long ms) {
		if (ms == null || ms == 0) {
			return null;
		}
		long total = Math.round(ms / 1000.0);
		long hours = total / 3600;
		long minutes = (total % 3600) / 60;
		long seconds = total % 60;
		if (hours > 0) {
			return hours + " h " + minutes + " min";
		}
		return minutes > 0 ? minutes + " min " + seconds + " s" : seconds + " s";
	}

	/**
	 * The meeting date and time in the user's locale, or null when the metadata carries no
	 * readable timestamp.
	 */
	public static @Nullable String formatDateTime(@Nullable String iso) {
		Long millis = epochMillis(iso);
		if (millis == null) {
			return null;
		}
		LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis),
				ZoneId.systemDefault());
		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.withLocale(Locale.getDefault()).format(date);
	}

	/** "1.2 GB" or "574 MB". */
	public static @Nullable String formatSize(long bytes) {
		return bytes >= 1_000_000_000L
				? String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
				: Math.round(bytes / 1_000_000.0) + " MB";
	}
}
