package dev.gravaai.services.shortcuts;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pure global-shortcut logic shared by the service, the control socket and (later) the settings
 * UI. Port of {@code src/lib/globalShortcuts.ts}; this module must stay free of DOM and JavaFX
 * APIs so services can use it.
 */
public final class GlobalShortcuts {

	/** The app-settings key the config is stored under (port of {@code GLOBAL_SHORTCUTS_SETTING}). */
	public static final String GLOBAL_SHORTCUTS_SETTING = "globalShortcuts";

	/** Port of {@code GLOBAL_SHORTCUT_LABELS}; keyed in action order. */
	public static final Map<GlobalShortcutAction, String> GLOBAL_SHORTCUT_LABELS = labels();

	private static final List<String> NAMED_KEYS = List.of(
			"Escape",
			"space",
			"Return",
			"Home",
			"End",
			"Prior",
			"Next",
			"Insert",
			"Delete",
			"Up",
			"Down",
			"Left",
			"Right");

	private static final Pattern LETTER = Pattern.compile("Key([A-Z])");
	private static final Pattern DIGIT = Pattern.compile("Digit([0-9])");
	private static final Pattern FUNCTION_KEY = Pattern.compile("F([1-9]|1[0-2])");
	private static final Pattern SINGLE_LOWER_DIGIT = Pattern.compile("[a-z0-9]");

	/** Port of {@code CODE_TO_KEY}. */
	private static final Map<String, String> CODE_TO_KEY = Map.ofEntries(
			Map.entry("Space", "space"),
			Map.entry("Enter", "Return"),
			Map.entry("Escape", "Escape"),
			Map.entry("Home", "Home"),
			Map.entry("End", "End"),
			Map.entry("PageUp", "Prior"),
			Map.entry("PageDown", "Next"),
			Map.entry("Insert", "Insert"),
			Map.entry("Delete", "Delete"),
			Map.entry("ArrowUp", "Up"),
			Map.entry("ArrowDown", "Down"),
			Map.entry("ArrowLeft", "Left"),
			Map.entry("ArrowRight", "Right"));

	/** Port of {@code KEY_LABELS}. */
	private static final Map<String, String> KEY_LABELS = Map.of(
			"space", "Space",
			"Return", "Enter",
			"Prior", "Page Up",
			"Next", "Page Down");

	/**
	 * Port of {@code DEFAULT_GLOBAL_SHORTCUTS}. The {@code superAlt} helper of the TS file returns
	 * a Super+Alt binding.
	 */
	public static final GlobalShortcutsConfig DEFAULT_GLOBAL_SHORTCUTS = defaultConfig();

	private GlobalShortcuts() {}

	/** Port of {@code isSupportedGlobalKey}. */
	public static boolean isSupportedGlobalKey(String key) {
		if (key == null) {
			return false;
		}
		return SINGLE_LOWER_DIGIT.matcher(key).matches()
				|| FUNCTION_KEY.matcher(key).matches()
				|| NAMED_KEYS.contains(key);
	}

	/** Port of {@code isValidGlobalBinding}. */
	public static boolean isValidGlobalBinding(GlobalShortcutBinding binding) {
		if (binding == null) {
			return false;
		}
		return (binding.sup() || binding.ctrl() || binding.alt()) && isSupportedGlobalKey(binding.key());
	}

	/** Port of {@code normalizeGlobalShortcutsConfig}: missing or invalid parts keep the defaults. */
	public static GlobalShortcutsConfig normalizeGlobalShortcutsConfig(JsonNode value) {
		JsonNode raw = value != null && value.isObject() ? value : null;
		JsonNode rawBindings =
				raw != null && raw.get("bindings") != null && raw.get("bindings").isObject()
						? raw.get("bindings")
						: null;
		Map<GlobalShortcutAction, GlobalShortcutBinding> bindings =
				new LinkedHashMap<>(DEFAULT_GLOBAL_SHORTCUTS.bindings());
		for (GlobalShortcutAction action : GlobalShortcutAction.values()) {
			if (rawBindings == null || !rawBindings.has(action.wire())) {
				continue;
			}
			BindingOrUndefined normalized = normalizeBinding(rawBindings.get(action.wire()));
			if (normalized.defined()) {
				bindings.put(action, normalized.binding());
			}
		}
		boolean enabled = raw != null && raw.get("enabled") != null && raw.get("enabled").isBoolean()
				? raw.get("enabled").booleanValue()
				: DEFAULT_GLOBAL_SHORTCUTS.enabled();
		return new GlobalShortcutsConfig(enabled, bindings);
	}

	/** The stored JSON shape of a config (port of the object {@code writeAppSetting} receives). */
	public static ObjectNode toJson(GlobalShortcutsConfig config, JsonNodeFactory nodes) {
		ObjectNode root = nodes.objectNode();
		root.put("enabled", config.enabled());
		ObjectNode bindings = nodes.objectNode();
		for (GlobalShortcutAction action : GlobalShortcutAction.values()) {
			if (!config.bindings().containsKey(action)) {
				continue;
			}
			GlobalShortcutBinding binding = config.bindings().get(action);
			if (binding == null) {
				bindings.putNull(action.wire());
				continue;
			}
			ObjectNode node = nodes.objectNode();
			node.put("key", binding.key());
			node.put("super", binding.sup());
			node.put("ctrl", binding.ctrl());
			node.put("alt", binding.alt());
			node.put("shift", binding.shift());
			bindings.set(action.wire(), node);
		}
		root.set("bindings", bindings);
		return root;
	}

	/** Port of {@code keyboardEventToBinding}. */
	public static @Nullable GlobalShortcutBinding keyboardEventToBinding(GlobalShortcutKeyEvent event) {
		if (event == null) {
			return null;
		}
		String key = null;
		Matcher letter = LETTER.matcher(event.code());
		Matcher digit = DIGIT.matcher(event.code());
		if (letter.matches()) {
			key = letter.group(1).toLowerCase(Locale.ROOT);
		} else if (digit.matches()) {
			key = digit.group(1);
		} else if (FUNCTION_KEY.matcher(event.code()).matches()) {
			key = event.code();
		} else {
			key = CODE_TO_KEY.get(event.code());
		}
		if (key == null) {
			return null;
		}
		return new GlobalShortcutBinding(key, event.metaKey(), event.ctrlKey(), event.altKey(), event.shiftKey());
	}

	/** Port of {@code bindingsEqual}. */
	public static boolean bindingsEqual(GlobalShortcutBinding a, GlobalShortcutBinding b) {
		if (a == null || b == null) {
			return false;
		}
		return a.key().equalsIgnoreCase(b.key())
				&& a.sup() == b.sup()
				&& a.ctrl() == b.ctrl()
				&& a.alt() == b.alt()
				&& a.shift() == b.shift();
	}

	/** Port of {@code formatGlobalBinding}, e.g. "Super + Alt + R". */
	public static String formatGlobalBinding(GlobalShortcutBinding binding) {
		List<String> parts = new ArrayList<>();
		if (binding.sup()) {
			parts.add("Super");
		}
		if (binding.ctrl()) {
			parts.add("Ctrl");
		}
		if (binding.alt()) {
			parts.add("Alt");
		}
		if (binding.shift()) {
			parts.add("Shift");
		}
		String key = KEY_LABELS.get(binding.key());
		if (key == null) {
			key = binding.key().length() == 1
					? binding.key().toUpperCase(Locale.ROOT)
					: binding.key();
		}
		parts.add(key);
		return String.join(" + ", parts);
	}

	/**
	 * Port of {@code parseCliAction}: finds {@code --toggle-recording} and friends in argv.
	 *
	 * @param argv the process arguments, or null when there are none
	 */
	public static @Nullable GlobalShortcutAction parseCliAction(@Nullable String[] argv) {
		if (argv == null) {
			return null;
		}
		for (String arg : argv) {
			if (arg == null || !arg.startsWith("--")) {
				continue;
			}
			GlobalShortcutAction action = GlobalShortcutAction.fromWire(arg.substring(2));
			if (action != null) {
				return action;
			}
		}
		return null;
	}

	/** @return the action named on the command line, or null when there is none */
	public static @Nullable GlobalShortcutAction parseCliAction(@Nullable List<String> argv) {
		return argv == null ? null : parseCliAction(argv.toArray(new String[0]));
	}

	/**
	 * The non-COSMIC fallback the settings page shows: how to run each action from a terminal or
	 * another desktop's shortcut settings (port of {@code cliCommands}).
	 */
	public static Map<GlobalShortcutAction, String> cliCommands(String appPath) {
		Map<GlobalShortcutAction, String> commands = new LinkedHashMap<>();
		String quotedApp = CosmicShortcuts.shellQuote(appPath);
		for (GlobalShortcutAction action : GlobalShortcutAction.values()) {
			commands.put(action, quotedApp + " --" + action.wire());
		}
		return commands;
	}

	/** Port of {@code normalizeBinding}: explicit {@code null} stays unbound, invalid is ignored. */
	private static BindingOrUndefined normalizeBinding(JsonNode value) {
		if (value == null || value.isNull()) {
			return new BindingOrUndefined(true, null);
		}
		if (!value.isObject() || !value.has("key") || !value.get("key").isString()) {
			return BindingOrUndefined.UNDEFINED;
		}
		GlobalShortcutBinding binding = new GlobalShortcutBinding(
				value.get("key").stringValue(),
				flag(value, "super"),
				flag(value, "ctrl"),
				flag(value, "alt"),
				flag(value, "shift"));
		return isValidGlobalBinding(binding)
				? new BindingOrUndefined(true, binding)
				: BindingOrUndefined.UNDEFINED;
	}

	private static boolean flag(JsonNode value, String field) {
		JsonNode node = value.get(field);
		return node != null && node.isBoolean() && node.booleanValue();
	}

	private static GlobalShortcutsConfig defaultConfig() {
		Map<GlobalShortcutAction, GlobalShortcutBinding> bindings = new LinkedHashMap<>();
		bindings.put(GlobalShortcutAction.TOGGLE_RECORDING, superAlt("r"));
		bindings.put(GlobalShortcutAction.TOGGLE_PAUSE, superAlt("p"));
		bindings.put(GlobalShortcutAction.CANCEL_RECORDING, null);
		bindings.put(GlobalShortcutAction.TOGGLE_MEETING, superAlt("m"));
		return new GlobalShortcutsConfig(true, bindings);
	}

	/** Port of the {@code superAlt} helper in {@code src/lib/globalShortcuts.ts}. */
	private static GlobalShortcutBinding superAlt(String key) {
		return new GlobalShortcutBinding(key, true, false, true, false);
	}

	private static Map<GlobalShortcutAction, String> labels() {
		Map<GlobalShortcutAction, String> labels = new LinkedHashMap<>();
		labels.put(GlobalShortcutAction.TOGGLE_RECORDING, "Start / stop recording");
		labels.put(GlobalShortcutAction.TOGGLE_PAUSE, "Pause / resume");
		labels.put(GlobalShortcutAction.CANCEL_RECORDING, "Cancel recording");
		labels.put(GlobalShortcutAction.TOGGLE_MEETING, "Start / stop meeting");
		return Map.copyOf(labels);
	}

	/** Tri-state of {@code normalizeBinding}: undefined means "keep the default". */
	private record BindingOrUndefined(boolean defined, @Nullable GlobalShortcutBinding binding) {
		private static final BindingOrUndefined UNDEFINED = new BindingOrUndefined(false, null);
	}
}
