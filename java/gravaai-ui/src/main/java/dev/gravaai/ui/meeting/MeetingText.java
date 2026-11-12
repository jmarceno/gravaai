package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import dev.gravaai.services.meeting.MeetingJob;
import dev.gravaai.services.meeting.MeetingJob.Stage;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.meeting.MeetingMeta;
import dev.gravaai.services.meeting.MeetingTexts;
import dev.gravaai.ui.i18n.UiI18n;

/**
 * Labels and formatters from the React meetings components ({@code MeetingView.tsx},
 * {@code MeetingThumbnail.tsx}, {@code MeetingSettingsPanel.tsx}).
 *
 * <p>The title, duration and size rules live in {@link MeetingTexts} (services), because the
 * document export must name and format a meeting exactly as the window does; this class adds only
 * the labels that come from the UI's translation bundles.
 */
public final class MeetingText {

	private MeetingText() {
	}

	/**
	 * Port of {@code meetingTitle}: the saved title, else the creation date/time, else "Meeting".
	 * The rule lives in {@link MeetingTexts} so the document export names a meeting exactly as the
	 * window does; only the untitled label comes from the UI's translations.
	 */
	public static String meetingTitle(@Nullable MeetingMeta meta, @Nullable Long fallbackMs) {
		return MeetingTexts.meetingTitle(meta, fallbackMs, UiI18n.t("dashboard:library.meeting"));
	}

	/** Port of {@code formatMeetingDuration}: "1 h 5 min", "5 min 3 s", "3 s" or null. */
	public static @Nullable String formatMeetingDuration(@Nullable Long ms) {
		return MeetingTexts.formatDuration(ms);
	}

	/** Port of {@code formatSize}: "1.2 GB" or "574 MB". */
	public static @Nullable String formatSize(long bytes) {
		return MeetingTexts.formatSize(bytes);
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
