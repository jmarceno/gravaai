package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Groups raw key presses into display elements for the Keystrokes track: characters typed in
 * quick succession merge into one word element that is shown together, while modifier
 * combinations (Ctrl+S …) and special keys (Enter, Backspace, arrows …) stay their own elements.
 *
 * <p>Both the sidecar seeder ({@code EditorSession}) and the media's sidecar fallback
 * ({@code ProjectEditorInputs}) group through here, so the timeline blocks and the rendered
 * overlay agree on the element boundaries.
 */
public final class KeystrokeGrouping {

	/**
	 * Max gap between two typed characters before the running word is flushed: within this
	 * window the user is still typing the same word, beyond it a new element starts.
	 */
	public static final long WORD_GAP_MS = 1000;

	/** Neutral press shape shared by the services sidecar events and the media sidecar JSON. */
	public record Event(long timeMs, String key, List<String> modifiers) {
		public Event {
			modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
		}
	}

	/** One display element: the typed text (or the key label) with its window and modifiers. */
	public record Group(long startMs, long endMs, String text, List<String> modifiers) {
		public Group {
			modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
		}
	}

	private static final List<String> ARROW_LABELS = List.of("↑", "←", "→", "↓");

	private KeystrokeGrouping() {
	}

	/**
	 * Merges presses (in time order) into display groups. Plain characters within
	 * {@link #WORD_GAP_MS} of each other accumulate into one word group whose window runs from
	 * the first character to the last plus {@code lingerMs}; any other press (a modifier
	 * combination, a special key) is emitted as its own group.
	 */
	public static List<Group> group(List<Event> events, long lingerMs) {
		long window = Math.max(1, lingerMs);
		List<Group> groups = new ArrayList<>();
		StringBuilder word = new StringBuilder();
		long wordStartMs = 0;
		long wordLastMs = 0;
		for (Event event : events) {
			if (event == null || event.key() == null || event.key().isBlank()) {
				continue;
			}
			long timeMs = Math.max(0, event.timeMs());
			String character = wordCharacter(event);
			if (character != null) {
				if (word.length() > 0 && timeMs - wordLastMs > WORD_GAP_MS) {
					flushWord(groups, word, wordStartMs, wordLastMs, window);
				}
				if (word.length() == 0) {
					wordStartMs = timeMs;
				}
				word.append(character);
				wordLastMs = timeMs;
				continue;
			}
			flushWord(groups, word, wordStartMs, wordLastMs, window);
			groups.add(new Group(timeMs, timeMs + window, event.key().trim(), event.modifiers()));
		}
		flushWord(groups, word, wordStartMs, wordLastMs, window);
		return groups;
	}

	private static void flushWord(List<Group> groups, StringBuilder word, long startMs,
			long lastMs, long window) {
		String text = word.toString().strip();
		word.setLength(0);
		if (text.isBlank()) {
			return; // Whitespace-only runs (lone space presses) have nothing to show.
		}
		groups.add(new Group(startMs, lastMs + window, text, List.of()));
	}

	/**
	 * The character a plain key contributes to the running word, or null when the press is not
	 * word typing: any held Ctrl/Alt/Super makes it a combination, and special keys (multi-char
	 * labels, arrows) stay their own elements. Shift only shapes the letter's case.
	 */
	private static @Nullable String wordCharacter(Event event) {
		boolean shift = false;
		for (String modifier : event.modifiers()) {
			if (modifier == null) {
				continue;
			}
			String wire = modifier.toLowerCase(Locale.ROOT).trim();
			if ("shift".equals(wire)) {
				shift = true;
			} else if (!wire.isEmpty()) {
				return null;
			}
		}
		String key = event.key().trim();
		if ("Space".equalsIgnoreCase(key)) {
			return " ";
		}
		if (key.length() != 1 || ARROW_LABELS.contains(key)) {
			return null;
		}
		char typed = key.charAt(0);
		if (Character.isLetter(typed)) {
			String letter = key.toLowerCase(Locale.ROOT);
			return shift ? letter.toUpperCase(Locale.ROOT) : letter;
		}
		return key;
	}
}
