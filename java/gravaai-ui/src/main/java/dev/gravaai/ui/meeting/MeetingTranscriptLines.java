package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import javafx.scene.Node;

/**
 * The timestamped transcript lines of a meeting document and which one the playhead is on (the
 * meeting audio player's highlight). The markdown view indexes its rendered rows here; the state
 * machine is kept free of {@code javafx.scene.control} so it is testable without the toolkit.
 */
final class MeetingTranscriptLines {

	/** The playing line: a soft accent wash behind the row (8-digit hex = ~13% alpha). */
	static final String ACTIVE_LINE_STYLE =
			"-fx-background-color: " + accentHex() + "22; -fx-background-radius: 6;";

	private final NavigableMap<Integer, Node> rows = new TreeMap<>();
	private @Nullable Node activeRow;
	private int activeSeconds = -1;

	private static String accentHex() {
		return "#" + dev.gravaai.ui.kit.Theme.ACCENT.toString().substring(2, 8);
	}

	/** Indexes one rendered line; a later line with the same timestamp replaces the earlier. */
	void index(int seconds, Node row) {
		rows.put(seconds, row);
	}

	/** Drops every indexed line (a re-render); the highlight state resets with them. */
	void clear() {
		rows.clear();
		Node previous = activeRow;
		if (previous != null) {
			previous.setStyle("");
		}
		activeRow = null;
		activeSeconds = -1;
	}

	boolean isEmpty() {
		return rows.isEmpty();
	}

	/**
	 * Highlights the last line whose timestamp is at or before {@code seconds} and clears the
	 * previous one. Returns the highlighted timestamp in seconds, or null when the playhead is
	 * before the first timestamped line.
	 */
	@Nullable Integer highlight(double seconds) {
		Map.Entry<Integer, Node> entry = rows.floorEntry((int) Math.floor(seconds));
		int target = entry == null ? -1 : entry.getKey();
		if (target == activeSeconds) {
			return target < 0 ? null : target;
		}
		Node previous = activeRow;
		if (previous != null) {
			previous.setStyle("");
		}
		activeRow = entry == null ? null : entry.getValue();
		activeSeconds = target;
		if (activeRow != null) {
			activeRow.setStyle(ACTIVE_LINE_STYLE);
		}
		return target < 0 ? null : target;
	}

	/** The currently highlighted timestamp in seconds, or null. */
	@Nullable Integer activeSeconds() {
		return activeSeconds < 0 ? null : activeSeconds;
	}

	/** The rendered row of a timestamped line (the notes pane reveals it when following). */
	@Nullable Node rowFor(int seconds) {
		return rows.get(seconds);
	}
}
