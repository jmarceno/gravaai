package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Meeting labels and formatters shared by the UI and the document export. The UI layer used to own
 * these; the export needs the exact same title and duration, so they live here and the UI's
 * {@code MeetingText} delegates, keeping one definition of each rule.
 *
 * <p>The one UI-owned input is the localized fallback label ("Meeting"), which the caller passes
 * in: services have no dependency on the translation lookup.
 *
 * <p>The {@link MeetingMeta} fields are nullable (they mirror the stored JSON, where every field is
 * optional), so the inputs here accept null; every answer that may be absent is an
 * {@link Optional}.
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
	 * @param meta the meeting metadata, or empty when it could not be read
	 * @param fallbackMs a creation time to use when the metadata has none
	 * @param fallbackLabel the label for a meeting with no title and no date ("Meeting")
	 */
	public static String meetingTitle(Optional<MeetingMeta> meta, OptionalLong fallbackMs,
			String fallbackLabel) {
		Optional<String> title = meta.map(MeetingMeta::title).map(String::trim)
				.filter(value -> !value.isEmpty());
		if (title.isPresent()) {
			return title.get();
		}
		OptionalLong created = meta.map(value -> epochMillis(value.createdAt()))
				.filter(OptionalLong::isPresent)
				.orElse(fallbackMs);
		if (created.isEmpty()) {
			return fallbackLabel;
		}
		LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(created.getAsLong()),
				ZoneId.systemDefault());
		return fallbackLabel + " " + DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
				.withLocale(Locale.getDefault()).format(date) + " " + TIME.format(date);
	}

	/** The ISO creation time as epoch milliseconds; empty when absent or unreadable. */
	static OptionalLong epochMillis(@Nullable String iso) {
		if (iso == null || iso.isBlank()) {
			return OptionalLong.empty();
		}
		try {
			return OptionalLong.of(Instant.parse(iso).toEpochMilli());
		} catch (RuntimeException error) {
			return OptionalLong.empty();
		}
	}

	/** "1 h 5 min", "5 min 3 s", "3 s"; empty when the duration is unknown or zero. */
	public static Optional<String> formatDuration(@Nullable Long ms) {
		if (ms == null || ms == 0) {
			return Optional.empty();
		}
		long total = Math.round(ms / 1000.0);
		long hours = total / 3600;
		long minutes = (total % 3600) / 60;
		long seconds = total % 60;
		if (hours > 0) {
			return Optional.of(hours + " h " + minutes + " min");
		}
		return Optional.of(minutes > 0 ? minutes + " min " + seconds + " s" : seconds + " s");
	}

	/**
	 * The meeting date and time in the user's locale; empty when the metadata carries no readable
	 * timestamp.
	 */
	public static Optional<String> formatDateTime(@Nullable String iso) {
		OptionalLong millis = epochMillis(iso);
		if (millis.isEmpty()) {
			return Optional.empty();
		}
		LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis.getAsLong()),
				ZoneId.systemDefault());
		return Optional.of(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.withLocale(Locale.getDefault()).format(date));
	}

	/** "1.2 GB" or "574 MB". */
	public static String formatSize(long bytes) {
		return bytes >= 1_000_000_000L
				? String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
				: Math.round(bytes / 1_000_000.0) + " MB";
	}
}
