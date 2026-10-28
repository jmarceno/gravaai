package dev.gravaai.ui.dashboard;

import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.meeting.MeetingText;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Placeholder thumbnail of a meeting card (port of {@code MeetingThumbnail.tsx}).
 *
 * <p>aspect-[4/3] sized by the host, centered 32 px {@code VideoCamera} (video) or
 * {@code MusicNotes} (audio) icon at 40% muted, the {@code Notes ready} / {@code Transcript ready}
 * / {@code Not transcribed} line at 11 px, the duration chip bottom-right and the job status chip
 * top-left. Meetings have a media file and sidecars, not a poster image, so React never renders a
 * frame here. The parent paints the {@code bg-default/60} rounded thumbnail (the dashboard's
 * {@code .project-thumbnail} host and {@code MeetingLibraryView}'s host).
 */
public final class MeetingThumbnail extends StackPane {

	/** Port of the thumbnail state lines in {@code MeetingThumbnail.tsx}. */
	public static final String NOTES_READY = "Notes ready";

	/** Port of the thumbnail state lines in {@code MeetingThumbnail.tsx}. */
	public static final String TRANSCRIPT_READY = "Transcript ready";

	/** Port of the thumbnail state lines in {@code MeetingThumbnail.tsx}. */
	public static final String NOT_TRANSCRIBED = "Not transcribed";

	public MeetingThumbnail(MeetingLibraryEntry meeting) {
		getStyleClass().add("meeting-thumbnail");
		setMinSize(0, 0);
		setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

		VBox center = new VBox(8);
		center.setAlignment(Pos.CENTER);
		center.setMouseTransparent(true);
		Icon icon = Icon.of(meeting.hasVideo() ? Icons.VideoCamera : Icons.MusicNotes, 32, "fill");
		icon.setIconColor(withOpacity(Theme.MUTED, 0.4));
		center.getChildren().addAll(icon, stateLine(meeting));
		getChildren().add(center);

		String duration = MeetingText.formatMeetingDuration(meeting.durationMs());
		if (duration != null) {
			Label durationLabel = chip(duration, 2, 6);
			durationLabel.setStyle(durationLabel.getStyle()
					+ "-fx-font-size: 10px; -fx-text-fill: " + css(Theme.FG) + ";"
					+ "-fx-background-color: " + rgba(Theme.BG, 0.8) + ";"
					+ "-fx-background-radius: 6; -fx-padding: 2 6 2 6;");
			StackPane.setAlignment(durationLabel, Pos.BOTTOM_RIGHT);
			StackPane.setMargin(durationLabel, new Insets(8));
			getChildren().add(durationLabel);
		}

		String status = MeetingText.libraryStatusLabel(meeting.status());
		if (status != null) {
			boolean danger = meeting.status() == MeetingLibraryEntry.Status.FAILED
					|| meeting.status() == MeetingLibraryEntry.Status.INTERRUPTED;
			Label badge = chip(status, 1, 6);
			badge.setStyle(badge.getStyle()
					+ "-fx-font-size: 10px; -fx-font-weight: 500;"
					+ "-fx-text-fill: " + css(danger ? Theme.DANGER : Theme.ACCENT) + ";"
					+ "-fx-background-color: "
					+ rgba(danger ? Theme.DANGER : Theme.ACCENT, 0.15) + ";"
					+ "-fx-background-radius: 6; -fx-padding: 2 6 2 6;");
			StackPane.setAlignment(badge, Pos.TOP_LEFT);
			StackPane.setMargin(badge, new Insets(8));
			getChildren().add(badge);
		}
	}

	/** Port of the middle line: notes win over the transcript, else "Not transcribed". */
	public static String stateLabel(MeetingLibraryEntry meeting) {
		if (meeting.hasNotes()) {
			return NOTES_READY;
		}
		if (meeting.hasTranscript()) {
			return TRANSCRIPT_READY;
		}
		return NOT_TRANSCRIBED;
	}

	/** {@code true} when the state line carries the 14 px {@code DocumentText} icon. */
	public static boolean hasNotesIcon(MeetingLibraryEntry meeting) {
		return meeting.hasNotes();
	}

	private static Node stateLine(MeetingLibraryEntry meeting) {
		HBox line = new HBox(6);
		line.setAlignment(Pos.CENTER);
		if (hasNotesIcon(meeting)) {
			Icon document = Icon.of(Icons.DocumentText, 14, "fill");
			document.setIconColor(Theme.MUTED);
			line.getChildren().add(document);
		}
		Label text = new Label(stateLabel(meeting));
		text.setStyle("-fx-font-size: 11px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		line.getChildren().add(text);
		return line;
	}

	private static Label chip(String text, double verticalPadding, double horizontalPadding) {
		Label label = new Label(text);
		label.setStyle("-fx-padding: " + verticalPadding + " " + horizontalPadding + " "
				+ verticalPadding + " " + horizontalPadding + ";");
		label.setMouseTransparent(true);
		return label;
	}

	private static Color withOpacity(Color color, double opacity) {
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), opacity);
	}

	private static String css(Color color) {
		return String.format(java.util.Locale.ROOT, "#%02x%02x%02x",
				Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255),
				Math.round(color.getBlue() * 255));
	}

	private static String rgba(Color color, double alpha) {
		return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.2f)",
				Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255),
				Math.round(color.getBlue() * 255), alpha);
	}
}
