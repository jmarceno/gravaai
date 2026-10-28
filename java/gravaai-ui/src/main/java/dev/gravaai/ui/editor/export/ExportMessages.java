package dev.gravaai.ui.editor.export;

import java.util.ArrayList;
import java.util.List;

/**
 * Export-dialog tips and announcement stream (port of
 * {@code src/components/video-editor/export/useExportMessages.ts}, plan §5.12). The bundled
 * announcements feed is not ported yet, so this only provides the tips/stream mechanics; the caller
 * appends announcement messages when the announcement service is available.
 */
public final class ExportMessages {

	public static final int MAX_EXPORT_MESSAGE_LENGTH = 220;
	public static final int DEFAULT_MESSAGE_DURATION_SECONDS = 6;

	/** One rotating export message. */
	public record Message(String id, String text, int durationSeconds, String announcementId) {
		public Message {
			if (id == null || id.isBlank()) {
				throw new IllegalArgumentException("message id is required");
			}
			text = compactMessage(text == null ? "" : text);
			if (durationSeconds <= 0) {
				durationSeconds = DEFAULT_MESSAGE_DURATION_SECONDS;
			}
		}

		public Message(String id, String text, int durationSeconds) {
			this(id, text, durationSeconds, null);
		}
	}

	private ExportMessages() {}

	/** {@code compactMessage}: collapse whitespace and clamp the length with an ellipsis. */
	public static String compactMessage(String value) {
		String compacted = value.replaceAll("\\s+", " ").trim();
		if (compacted.length() <= MAX_EXPORT_MESSAGE_LENGTH) {
			return compacted;
		}
		String cropped = compacted.substring(0, MAX_EXPORT_MESSAGE_LENGTH - 1);
		return cropped.stripTrailing() + "…";
	}

	/** {@code buildExportMessageStream}: alternate tips with announcements. */
	public static List<Message> buildExportMessageStream(List<Message> tips,
			List<Message> announcements) {
		List<Message> stream = new ArrayList<>();
		List<Message> resolvedTips = tips == null ? List.of() : tips;
		List<Message> resolvedAnnouncements = announcements == null ? List.of() : announcements;
		int length = Math.max(resolvedTips.size(), resolvedAnnouncements.size());
		for (int index = 0; index < length; index++) {
			if (index < resolvedTips.size()) {
				stream.add(resolvedTips.get(index));
			}
			if (index < resolvedAnnouncements.size()) {
				stream.add(resolvedAnnouncements.get(index));
			}
		}
		return List.copyOf(stream);
	}

	/** The two built-in tips of the export dialog (i18n keys kept in the ids). */
	public static List<Message> defaultTips() {
		return List.of(
				new Message("tip:auto-zooms", "Tip: Turn off auto-applied zooms in settings",
						DEFAULT_MESSAGE_DURATION_SECONDS),
				new Message("tip:cursor-appearance", "Tip: You can customise your cursor appearance",
						DEFAULT_MESSAGE_DURATION_SECONDS));
	}
}
