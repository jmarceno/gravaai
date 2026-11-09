package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

import dev.gravaai.services.meeting.MeetingJob;
import dev.gravaai.services.meeting.MeetingJob.Stage;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.meeting.MeetingMeta;
import dev.gravaai.ui.i18n.UiI18n;

/**
 * Labels and formatters from the React meetings components ({@code MeetingView.tsx},
 * {@code MeetingThumbnail.tsx}, {@code MeetingSettingsPanel.tsx}).
 */
public final class MeetingText {

	private static final DateTimeFormatter TIME =
			DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault());

	private MeetingText() {
	}

	/** Port of {@code meetingTitle}: the saved title, else the creation date/time, else "Meeting". */
	public static String meetingTitle(@Nullable MeetingMeta meta, @Nullable Long fallbackMs) {
		if (meta != null && meta.title() != null && !meta.title().trim().isEmpty()) {
			return meta.title().trim();
		}
		Long created = null;
		if (meta != null && meta.createdAt() != null) {
			try {
				created = Instant.parse(meta.createdAt()).toEpochMilli();
			} catch (RuntimeException error) {
				created = null;
			}
		}
		if (created == null) {
			created = fallbackMs;
		}
		if (created == null) {
			return UiI18n.t("dashboard:library.meeting");
		}
		LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(created),
				ZoneId.systemDefault());
		return UiI18n.t("dashboard:library.meeting") + " "
				+ DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
						.withLocale(Locale.getDefault()).format(date) + " " + TIME.format(date);
	}

	/** Port of {@code formatMeetingDuration}: "1 h 5 min", "5 min 3 s", "3 s" or null. */
	public static @Nullable String formatMeetingDuration(@Nullable Long ms) {
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

	/** Port of {@code formatSize}: "1.2 GB" or "574 MB". */
	public static @Nullable String formatSize(long bytes) {
		return bytes >= 1_000_000_000L
				? String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
				: Math.round(bytes / 1_000_000.0) + " MB";
	}

	/** Port of the thumbnail status badge labels; {@code null} hides the badge. */
	public static @Nullable String libraryStatusLabel(MeetingLibraryEntry.Status status) {
		if (status == null) {
			return null;
		}
		return switch (status) {
			case IDLE -> null;
			case QUEUED -> UiI18n.t("dashboard:meetingStatus.waiting");
			case RUNNING -> UiI18n.t("dashboard:meetingStatus.processing");
			case FAILED -> UiI18n.t("dashboard:meetingStatus.failed");
			case INTERRUPTED -> UiI18n.t("dashboard:meetingStatus.interrupted");
		};
	}

	/** Port of {@code STAGE_LABEL} in {@code MeetingNotes.tsx}. */
	public static String stageLabel(@Nullable Stage stage) {
		if (stage == null) {
			return UiI18n.t("dashboard:meetingStatus.waitingToStart");
		}
		return switch (stage) {
			case TRANSCRIBE -> UiI18n.t("dashboard:meetingStatus.transcribing");
			case SUMMARIZE -> UiI18n.t("dashboard:meetingStatus.writingNotes");
			case TITLE -> UiI18n.t("dashboard:meetingStatus.namingMeeting");
		};
	}

	/** True for the two job states where the UI shows progress/cancel. */
	public static boolean isActive(MeetingJob job) {
		return job != null && job.isActive();
	}
}
