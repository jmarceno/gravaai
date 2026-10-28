package dev.gravaai.ui.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Editor keyboard shortcuts (port of {@code src/lib/shortcuts.ts}, plan §5.12): the configurable
 * actions with their defaults, the fixed (non-configurable) shortcuts, conflict detection, platform
 * matching and display formatting.
 */
public final class Shortcuts {

	/** {@code SHORTCUT_ACTIONS}. */
	public enum Action {
		ADD_ZOOM("addZoom"),
		SPLIT_CLIP("splitClip"),
		ADD_ANNOTATION("addAnnotation"),
		ADD_KEYFRAME("addKeyframe"),
		DELETE_SELECTED("deleteSelected"),
		PLAY_PAUSE("playPause");

		private final String wire;

		Action(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		public static Action fromWire(String value) {
			for (Action action : values()) {
				if (action.wire.equals(value)) {
					return action;
				}
			}
			return null;
		}
	}

	/** {@code ShortcutBinding}: key plus the modifier flags. */
	public record Binding(String key, boolean ctrl, boolean shift, boolean alt) {

		public static Binding of(String key) {
			return new Binding(key, false, false, false);
		}

		public static Binding ctrl(String key) {
			return new Binding(key, true, false, false);
		}
	}

	/** A shortcut that cannot be changed ({@code FixedShortcut}). */
	public record FixedShortcut(String label, String display, List<Binding> bindings) {}

	/** Result of {@link #findConflict}. */
	public sealed interface Conflict permits ConfigurableConflict, FixedConflict {}

	public record ConfigurableConflict(Action action) implements Conflict {}

	public record FixedConflict(String label) implements Conflict {}

	private static final Map<Action, String> LABELS = labels();

	private static final Map<Action, Binding> DEFAULTS = defaultsMap();

	/** {@code FIXED_SHORTCUTS}. */
	public static final List<FixedShortcut> FIXED_SHORTCUTS = List.of(
			new FixedShortcut("Cycle Annotations Forward", "Tab", List.of(Binding.of("tab"))),
			new FixedShortcut("Cycle Annotations Backward", "Shift + Tab",
					List.of(new Binding("tab", false, true, false))),
			new FixedShortcut("Delete Selected (alt)", "Del / ⌫",
					List.of(Binding.of("delete"), Binding.of("backspace"))),
			// The in-editor save (header Save button, Ctrl/⌘ + S): fixed, so a custom binding can
			// never take it away.
			new FixedShortcut("Save Project", isMacPlatform() ? "⌘ S" : "Ctrl + S",
					List.of(Binding.ctrl("s"))),
			new FixedShortcut("Pan Timeline", "Shift + Scroll", List.of()),
			new FixedShortcut("Zoom Timeline", "Ctrl + Scroll", List.of()));

	private static final Map<String, String> KEY_LABELS = Map.of(
			" ", "Space", "delete", "Del", "backspace", "⌫", "escape", "Esc",
			"arrowup", "↑", "arrowdown", "↓", "arrowleft", "←", "arrowright", "→");

	private Shortcuts() {}

	private static Map<Action, String> labels() {
		Map<Action, String> labels = new LinkedHashMap<>();
		labels.put(Action.ADD_ZOOM, "Add Zoom");
		labels.put(Action.SPLIT_CLIP, "Split Clip");
		labels.put(Action.ADD_ANNOTATION, "Add Annotation");
		labels.put(Action.ADD_KEYFRAME, "Add Keyframe");
		labels.put(Action.DELETE_SELECTED, "Delete Selected");
		labels.put(Action.PLAY_PAUSE, "Play / Pause");
		return java.util.Collections.unmodifiableMap(labels);
	}

	private static Map<Action, Binding> defaultsMap() {
		Map<Action, Binding> defaults = new LinkedHashMap<>();
		defaults.put(Action.ADD_ZOOM, Binding.of("z"));
		defaults.put(Action.SPLIT_CLIP, Binding.of("c"));
		defaults.put(Action.ADD_ANNOTATION, Binding.of("a"));
		defaults.put(Action.ADD_KEYFRAME, Binding.of("f"));
		defaults.put(Action.DELETE_SELECTED, Binding.ctrl("d"));
		defaults.put(Action.PLAY_PAUSE, Binding.of(" "));
		return java.util.Collections.unmodifiableMap(defaults);
	}

	/** {@code DEFAULT_SHORTCUTS}. */
	public static Map<Action, Binding> defaults() {
		return new LinkedHashMap<>(DEFAULTS);
	}

	/** {@code SHORTCUT_LABELS}. */
	public static String label(Action action) {
		return LABELS.getOrDefault(action, action.wire());
	}

	/** {@code bindingsEqual}: key case-insensitive, modifiers compared as booleans. */
	public static boolean bindingsEqual(Binding left, Binding right) {
		return left.key().toLowerCase(Locale.ROOT).equals(right.key().toLowerCase(Locale.ROOT))
				&& left.ctrl() == right.ctrl() && left.shift() == right.shift()
				&& left.alt() == right.alt();
	}

	/** {@code findConflict}: fixed shortcuts win over configurable ones. */
	public static Conflict findConflict(
			Binding binding, Action forAction, Map<Action, Binding> config) {
		for (FixedShortcut fixed : FIXED_SHORTCUTS) {
			for (Binding fixedBinding : fixed.bindings()) {
				if (bindingsEqual(fixedBinding, binding)) {
					return new FixedConflict(fixed.label());
				}
			}
		}
		for (Action action : Action.values()) {
			if (action != forAction && config.containsKey(action)
					&& bindingsEqual(config.get(action), binding)) {
				return new ConfigurableConflict(action);
			}
		}
		return null;
	}

	/** {@code mergeWithDefaults}: a missing/blank entry keeps the default binding. */
	public static Map<Action, Binding> mergeWithDefaults(Map<Action, Binding> partial) {
		Map<Action, Binding> merged = defaults();
		if (partial != null) {
			for (Map.Entry<Action, Binding> entry : partial.entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null) {
					merged.put(entry.getKey(), entry.getValue());
				}
			}
		}
		return merged;
	}

	/** macOS uses Cmd as the primary modifier (React's {@code isMac} from platformUtils). */
	public static boolean isMacPlatform() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	}

	/**
	 * {@code matchesShortcut}; the platform decides whether Ctrl or Meta is the primary modifier.
	 */
	public static boolean matchesShortcut(String key, boolean ctrlKey, boolean metaKey,
			boolean shiftKey, boolean altKey, Binding binding, boolean isMacPlatform) {
		if (key == null || !key.toLowerCase(Locale.ROOT)
				.equals(binding.key().toLowerCase(Locale.ROOT))) {
			return false;
		}
		boolean primaryMod = isMacPlatform ? metaKey : ctrlKey;
		return primaryMod == binding.ctrl() && shiftKey == binding.shift()
				&& altKey == binding.alt();
	}

	/** {@code formatBinding}: Ctrl/⌘, Shift/⇧, Alt/⌥ plus the key label. */
	public static String formatBinding(Binding binding, boolean isMac) {
		List<String> parts = new ArrayList<>();
		if (binding.ctrl()) {
			parts.add(isMac ? "⌘" : "Ctrl");
		}
		if (binding.shift()) {
			parts.add(isMac ? "⇧" : "Shift");
		}
		if (binding.alt()) {
			parts.add(isMac ? "⌥" : "Alt");
		}
		parts.add(KEY_LABELS.getOrDefault(binding.key().toLowerCase(Locale.ROOT),
				binding.key().toUpperCase(Locale.ROOT)));
		return String.join(" + ", parts);
	}
}
