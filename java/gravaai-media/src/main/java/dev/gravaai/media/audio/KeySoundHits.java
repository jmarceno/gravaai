package dev.gravaai.media.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.KeySoundSettings;

/**
 * The single source of key-press sound times, shared by preview and export like
 * {@link ClickSoundHits}.
 *
 * <p>Only keys that type something sound: letters, digits, punctuation, Space, Enter, Backspace
 * and Tab. Pure modifiers, function/arrow/navigation keys and shortcut chords (Ctrl/Alt/Super
 * held) stay silent, and presses closer than {@link #MIN_GAP_MS} coalesce so a held-down key or
 * a roll of fast typing does not turn into a buzz.</p>
 */
public final class KeySoundHits {

	/** One recorded press, source-timeline milliseconds. {@code shortcut} = Ctrl/Alt/Super held. */
	public record Press(long timeMs, String key, boolean shortcut) {}

	private static final Set<String> TYPING_NAMES = Set.of("Space", "Enter", "Backspace", "Tab");
	/** Presses closer than this coalesce into one sound. */
	static final long MIN_GAP_MS = 25;

	private KeySoundHits() {}

	/** Whether this press should make a sound. */
	public static boolean sounds(Press press) {
		if (press.shortcut() || press.key() == null) {
			return false;
		}
		String key = press.key();
		return key.codePointCount(0, key.length()) == 1 || TYPING_NAMES.contains(key);
	}

	/** Whether the recording has at least one press that would sound (the inspector hint). */
	public static boolean hasTypedKeys(List<Press> presses) {
		return presses.stream().anyMatch(KeySoundHits::sounds);
	}

	/** Timeline positions (ms, ascending, coalesced) for every audible press that is on screen. */
	public static long[] timesMs(KeySoundSettings settings, List<Press> presses,
			List<ClipRegion> clips) {
		if (settings == null || !settings.enabled()) {
			return new long[0];
		}
		List<Long> mapped = new ArrayList<>();
		for (Press press : presses) {
			if (!sounds(press) || !insideAnyClip(press.timeMs(), clips)) {
				continue;
			}
			mapped.add(Clips.mapSourceTimeToTimelineTime(press.timeMs(), clips));
		}
		mapped.sort(Long::compare);
		List<Long> kept = new ArrayList<>(mapped.size());
		long previous = Long.MIN_VALUE;
		for (long time : mapped) {
			if (kept.isEmpty() || time - previous >= MIN_GAP_MS) {
				kept.add(time);
				previous = time;
			}
		}
		long[] timesMs = new long[kept.size()];
		for (int index = 0; index < timesMs.length; index++) {
			timesMs[index] = kept.get(index);
		}
		return timesMs;
	}

	private static boolean insideAnyClip(long sourceTimeMs, List<ClipRegion> clips) {
		if (clips.isEmpty()) {
			return true;
		}
		for (ClipRegion clip : clips) {
			if (sourceTimeMs >= Clips.getClipSourceStartMs(clip)
					&& sourceTimeMs < Clips.getClipSourceEndMs(clip)) {
				return true;
			}
		}
		return false;
	}
}
