package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rules model shared by the main-side engine and the settings page (plan §5.15). Port of
 * {@code src/types/automations.ts}: normalisation, limits, labels, templates and the JSON shape of
 * {@code userData/automations.json}.
 *
 * <p>Everything here is pure: unknown triggers/actions/conditions are dropped, counts are clamped
 * and unknown fields never reach this model.
 */
public final class Automations {

	public static final int MAX_AUTOMATION_RULES = 50;
	public static final int MAX_ACTIONS_PER_RULE = 20;
	public static final int MAX_CONDITIONS_PER_RULE = 10;
	public static final int MAX_NAME_LENGTH = 120;
	public static final int MAX_FOLDER_LENGTH = 4096;
	public static final int MAX_ARGUMENTS = 32;

	/** Port of {@code AUTOMATION_PLACEHOLDERS}. */
	public static final List<String> AUTOMATION_PLACEHOLDERS = List.of(
			"{path}", "{dir}", "{name}", "{ext}", "{date}", "{time}", "{kind}", "{format}", "{notesPath}");

	/** Port of {@code AUTOMATION_TRIGGER_LABELS}; keyed in trigger order. */
	public static final Map<AutomationTrigger, String> AUTOMATION_TRIGGER_LABELS = triggerLabels();

	/** Port of {@code AUTOMATION_ACTION_LABELS}; keyed in action order. */
	public static final Map<AutomationActionType, String> AUTOMATION_ACTION_LABELS = actionLabels();

	/** Port of {@code AUTOMATION_CONDITION_LABELS}; keyed in condition order. */
	public static final Map<AutomationConditionType, String> AUTOMATION_CONDITION_LABELS = conditionLabels();

	/** Port of {@code AUTOMATION_TEMPLATES}: the four ready-made rules of the settings page. */
	public static final List<AutomationTemplate> AUTOMATION_TEMPLATES = templates();

	/** Port of {@code ALL_ACTION_TYPES} in the TS file. */
	private static final List<AutomationActionType> ALL_ACTION_TYPES = List.of(
			AutomationActionType.COPY_PATH,
			AutomationActionType.REVEAL,
			AutomationActionType.OPEN,
			AutomationActionType.NOTIFY,
			AutomationActionType.COPY_TO_FOLDER,
			AutomationActionType.MOVE_TO_FOLDER,
			AutomationActionType.RUN_COMMAND);

	private static final Pattern JS_FLOAT = Pattern.compile(
			"[+-]?(?:Infinity|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)");

	private Automations() {}

	public static String automationTriggerLabel(AutomationTrigger trigger) {
		return AUTOMATION_TRIGGER_LABELS.getOrDefault(trigger, trigger == null ? "" : trigger.wire());
	}

	public static String automationActionLabel(AutomationActionType action) {
		return AUTOMATION_ACTION_LABELS.getOrDefault(action, action == null ? "" : action.wire());
	}

	public static boolean isAutomationTrigger(String value) {
		return AutomationTrigger.isTrigger(value);
	}

	public static boolean isAutomationExportFormat(String value) {
		return AutomationExportFormat.isFormat(value);
	}

	/** Moving files is only safe for exports: a recording has sidecars and projects point at it. */
	public static boolean actionAllowedForTrigger(AutomationActionType action, AutomationTrigger trigger) {
		if (action == AutomationActionType.MOVE_TO_FOLDER) {
			return trigger == AutomationTrigger.EXPORT_FINISHED;
		}
		return ALL_ACTION_TYPES.contains(action);
	}

	public static boolean triggerAllowsConditionsOfType(AutomationConditionType type, AutomationTrigger trigger) {
		if (type == AutomationConditionType.FORMAT_IS) {
			return trigger == AutomationTrigger.EXPORT_FINISHED;
		}
		return true;
	}

	public static @Nullable AutomationRule normalizeRule(JsonNode value, Supplier<String> createRuleId) {
		if (value == null || !value.isObject()) {
			return null;
		}
		JsonNode triggerNode = value.get("trigger");
		String triggerWire = triggerNode != null && triggerNode.isString() ? triggerNode.stringValue() : null;
		AutomationTrigger trigger = AutomationTrigger.fromWire(triggerWire);
		if (trigger == null) {
			return null;
		}

		JsonNode rawConditions = value.get("conditions");
		List<AutomationCondition> conditions = new ArrayList<>();
		if (rawConditions != null && rawConditions.isArray()) {
			for (JsonNode entry : rawConditions) {
				AutomationCondition condition = normalizeCondition(entry);
				if (condition == null) {
					continue;
				}
				if (!triggerAllowsConditionsOfType(condition.type(), trigger)) {
					continue;
				}
				if (conditions.size() >= MAX_CONDITIONS_PER_RULE) {
					break;
				}
				conditions.add(condition);
			}
		}

		JsonNode rawActions = value.get("actions");
		List<AutomationAction> actions = new ArrayList<>();
		if (rawActions != null && rawActions.isArray()) {
			for (JsonNode entry : rawActions) {
				AutomationAction action = normalizeAction(entry);
				if (action == null) {
					continue;
				}
				if (!actionAllowedForTrigger(action.type(), trigger)) {
					continue;
				}
				if (actions.size() >= MAX_ACTIONS_PER_RULE) {
					break;
				}
				actions.add(action);
			}
		}

		JsonNode idNode = value.get("id");
		String id = idNode != null && idNode.isString() && !idNode.stringValue().trim().isEmpty()
				? idNode.stringValue().trim()
				: createRuleId.get();
		String name = asString(value.get("name"), MAX_NAME_LENGTH).trim();
		JsonNode enabledNode = value.get("enabled");
		boolean enabled = enabledNode != null && enabledNode.isBoolean() && enabledNode.booleanValue();
		return new AutomationRule(id, name.isEmpty() ? "Untitled automation" : name, enabled, trigger, conditions, actions);
	}

	/**
	 * Port of {@code normalizeAutomationsFile}: drops unknown triggers/actions/conditions and
	 * regenerates missing ids.
	 */
	public static AutomationsFile normalizeAutomationsFile(JsonNode value) {
		return normalizeAutomationsFile(value, automationsRuleId());
	}

	public static AutomationsFile normalizeAutomationsFile(JsonNode value,
			Supplier<String> createRuleId) {
		JsonNode record = value != null && value.isObject() ? value : null;
		JsonNode rawRules = record == null ? null : record.get("rules");
		List<AutomationRule> rules = new ArrayList<>();
		java.util.Set<String> seenIds = new java.util.HashSet<>();
		if (rawRules != null && rawRules.isArray()) {
			for (JsonNode entry : rawRules) {
				if (rules.size() >= MAX_AUTOMATION_RULES) {
					break;
				}
				AutomationRule rule = normalizeRule(entry, createRuleId);
				if (rule == null) {
					continue;
				}
				if (seenIds.contains(rule.id())) {
					rule = new AutomationRule(
							createRuleId.get(), rule.name(), rule.enabled(), rule.trigger(), rule.conditions(), rule.actions());
				}
				seenIds.add(rule.id());
				rules.add(rule);
			}
		}
		return new AutomationsFile(1, rules);
	}

	public static AutomationsFile createEmptyAutomationsFile() {
		return new AutomationsFile(1, List.of());
	}

	/** Port of {@code automationRuleIssues}: problems the settings UI should surface. */
	public static List<String> automationRuleIssues(AutomationRule rule) {
		List<String> issues = new ArrayList<>();
		for (AutomationAction action : rule.actions()) {
			if (!actionAllowedForTrigger(action.type(), rule.trigger())) {
				issues.add("Moving files is only available for exports.");
			}
			if (action instanceof AutomationAction.RunCommand command && command.program().trim().isEmpty()) {
				issues.add("The run command needs a program.");
			}
			if ((action instanceof AutomationAction.CopyToFolder copy && copy.folder().trim().isEmpty())
					|| (action instanceof AutomationAction.MoveToFolder move && move.folder().trim().isEmpty())) {
				issues.add("Choose a folder for the copy or move action.");
			}
		}
		if (rule.actions().isEmpty()) {
			issues.add("Add at least one action.");
		}
		return issues;
	}

	/**
	 * Port of {@code findDisallowedAction} in {@code electron/ipc/register/automations.ts}: rules
	 * the engine would silently ignore are rejected at save time.
	 */
	public static @Nullable String findDisallowedAction(JsonNode value) {
		if (value == null || !value.isObject()) {
			return null;
		}
		JsonNode rules = value.get("rules");
		if (rules == null || !rules.isArray()) {
			return null;
		}
		for (JsonNode entry : rules) {
			if (entry == null || !entry.isObject()) {
				continue;
			}
			JsonNode triggerNode = entry.get("trigger");
			String triggerWire = triggerNode != null && triggerNode.isString() ? triggerNode.stringValue() : null;
			AutomationTrigger trigger = AutomationTrigger.fromWire(triggerWire);
			if (trigger == null) {
				continue;
			}
			JsonNode actions = entry.get("actions");
			if (actions == null || !actions.isArray()) {
				continue;
			}
			for (JsonNode rawAction : actions) {
				if (rawAction == null || !rawAction.isObject()) {
					continue;
				}
				JsonNode typeNode = rawAction.get("type");
				if (typeNode == null || !typeNode.isString()) {
					continue;
				}
				AutomationActionType type = AutomationActionType.fromWire(typeNode.stringValue());
				if (type == null) {
					continue;
				}
				if (!actionAllowedForTrigger(type, trigger)) {
					return "\"" + type.wire() + "\" is not available for " + trigger.wire() + " rules";
				}
			}
		}
		return null;
	}

	/** The stored JSON shape of a file (port of the object {@code JSON.stringify} receives). */
	public static ObjectNode toJson(AutomationsFile file) {
		JsonNodeFactory nodes = Json.nodeFactory();
		ObjectNode root = nodes.objectNode();
		root.put("version", file.version());
		ArrayNode rules = nodes.arrayNode();
		for (AutomationRule rule : file.rules()) {
			ObjectNode ruleNode = nodes.objectNode();
			ruleNode.put("id", rule.id());
			ruleNode.put("name", rule.name());
			ruleNode.put("enabled", rule.enabled());
			ruleNode.put("trigger", rule.trigger().wire());
			ArrayNode conditions = nodes.arrayNode();
			for (AutomationCondition condition : rule.conditions()) {
				ObjectNode conditionNode = nodes.objectNode();
				conditionNode.put("type", condition.type().wire());
				if (condition instanceof AutomationCondition.DurationAtLeast atLeast) {
					putSeconds(conditionNode, atLeast.seconds());
				} else if (condition instanceof AutomationCondition.DurationAtMost atMost) {
					putSeconds(conditionNode, atMost.seconds());
				} else if (condition instanceof AutomationCondition.FormatIs formatIs) {
					conditionNode.put("format", formatIs.format().wire());
				}
				conditions.add(conditionNode);
			}
			ruleNode.set("conditions", conditions);
			ArrayNode actions = nodes.arrayNode();
			for (AutomationAction action : rule.actions()) {
				actions.add(actionJson(action, nodes));
			}
			ruleNode.set("actions", actions);
			rules.add(ruleNode);
		}
		root.set("rules", rules);
		return root;
	}

	private static ObjectNode actionJson(AutomationAction action, JsonNodeFactory nodes) {
		ObjectNode node = nodes.objectNode();
		node.put("type", action.type().wire());
		if (action instanceof AutomationAction.Notify notify) {
			node.put("title", notify.title());
			node.put("body", notify.body());
		} else if (action instanceof AutomationAction.CopyToFolder copy) {
			node.put("folder", copy.folder());
			if (copy.fileName() != null) {
				node.put("fileName", copy.fileName());
			}
		} else if (action instanceof AutomationAction.MoveToFolder move) {
			node.put("folder", move.folder());
			if (move.fileName() != null) {
				node.put("fileName", move.fileName());
			}
		} else if (action instanceof AutomationAction.RunCommand command) {
			node.put("program", command.program());
			ArrayNode args = nodes.arrayNode();
			for (String argument : command.args()) {
				args.add(argument);
			}
			node.set("args", args);
		}
		return node;
	}

	/** JSON.stringify writes integral seconds without a decimal point; put(long) keeps that. */
	private static void putSeconds(ObjectNode node, double seconds) {
		if (seconds == Math.rint(seconds) && Math.abs(seconds) <= 9.007199254740992E15) {
			node.put("seconds", (long) seconds);
		} else {
			node.put("seconds", seconds);
		}
	}

	private static @Nullable AutomationCondition normalizeCondition(JsonNode value) {
		if (value == null || !value.isObject()) {
			return null;
		}
		JsonNode typeNode = value.get("type");
		String type = typeNode != null && typeNode.isString() ? typeNode.stringValue() : null;
		if ("durationAtLeast".equals(type) || "durationAtMost".equals(type)) {
			double seconds = normalizeSeconds(value.get("seconds"));
			return "durationAtLeast".equals(type)
					? new AutomationCondition.DurationAtLeast(seconds)
					: new AutomationCondition.DurationAtMost(seconds);
		}
		if ("formatIs".equals(type)) {
			JsonNode formatNode = value.get("format");
			String formatWire = formatNode != null && formatNode.isString() ? formatNode.stringValue() : null;
			AutomationExportFormat format = AutomationExportFormat.fromWire(formatWire);
			return format == null ? null : new AutomationCondition.FormatIs(format);
		}
		return null;
	}

	private static @Nullable AutomationAction normalizeAction(JsonNode value) {
		if (value == null || !value.isObject()) {
			return null;
		}
		JsonNode typeNode = value.get("type");
		String typeWire = typeNode != null && typeNode.isString() ? typeNode.stringValue() : null;
		AutomationActionType type = AutomationActionType.fromWire(typeWire);
		if (type == null) {
			return null;
		}
		return switch (type) {
			case COPY_PATH -> new AutomationAction.CopyPath();
			case REVEAL -> new AutomationAction.Reveal();
			case OPEN -> new AutomationAction.Open();
			case NOTIFY -> new AutomationAction.Notify(
					asString(value.get("title"), MAX_NAME_LENGTH), asString(value.get("body"), 2000));
			case COPY_TO_FOLDER -> new AutomationAction.CopyToFolder(
					asString(value.get("folder"), MAX_FOLDER_LENGTH), optionalString(value.get("fileName")));
			case MOVE_TO_FOLDER -> new AutomationAction.MoveToFolder(
					asString(value.get("folder"), MAX_FOLDER_LENGTH), optionalString(value.get("fileName")));
			case RUN_COMMAND -> new AutomationAction.RunCommand(
					asString(value.get("program"), MAX_FOLDER_LENGTH), stringArray(value.get("args")));
		};
	}

	private static List<String> stringArray(JsonNode value) {
		if (value == null || !value.isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode entry : value) {
			if (values.size() >= MAX_ARGUMENTS) {
				break;
			}
			values.add(asString(entry, MAX_FOLDER_LENGTH));
		}
		return values;
	}

	/** Port of {@code asString}: only real strings survive, sliced to the limit. */
	private static String asString(JsonNode value, int maxLength) {
		if (value == null || !value.isString()) {
			return "";
		}
		String text = value.stringValue();
		return text.length() <= maxLength ? text : text.substring(0, maxLength);
	}

	/** Port of the TS {@code ...(fileName ? { fileName } : {})} spread. */
	private static @Nullable String optionalString(JsonNode value) {
		String text = asString(value, MAX_NAME_LENGTH);
		return text.isEmpty() ? null : text;
	}

	/** Port of {@code normalizeSeconds}: numbers or numeric strings, negative/NaN clamp to 0. */
	private static double normalizeSeconds(JsonNode value) {
		double parsed;
		if (value != null && value.isNumber()) {
			parsed = value.doubleValue();
		} else {
			parsed = parseJsFloat(jsString(value).trim());
		}
		if (!Double.isFinite(parsed) || parsed < 0) {
			return 0;
		}
		return Math.round(parsed * 1000) / 1000.0;
	}

	/** JavaScript {@code String(value)} for the value shapes a JSON rule can contain. */
	private static String jsString(JsonNode value) {
		if (value == null || value.isNull()) {
			return "";
		}
		if (value.isString()) {
			return value.stringValue();
		}
		if (value.isBoolean()) {
			return value.booleanValue() ? "true" : "false";
		}
		if (value.isNumber()) {
			return value.toString();
		}
		return value.toString();
	}

	/** Port of {@code Number.parseFloat}: parses the longest numeric prefix, NaN when there is none. */
	private static double parseJsFloat(String text) {
		Matcher matcher = JS_FLOAT.matcher(text);
		if (!matcher.lookingAt()) {
			return Double.NaN;
		}
		String number = matcher.group();
		return switch (number) {
			case "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY;
			case "-Infinity" -> Double.NEGATIVE_INFINITY;
			default -> Double.parseDouble(number);
		};
	}

	private static Supplier<String> automationsRuleId() {
		return () -> UUID.randomUUID().toString();
	}

	private static Map<AutomationTrigger, String> triggerLabels() {
		Map<AutomationTrigger, String> labels = new LinkedHashMap<>();
		labels.put(AutomationTrigger.RECORDING_SAVED, "After a screen recording is saved");
		labels.put(AutomationTrigger.MEETING_SAVED, "After a meeting recording is saved");
		labels.put(AutomationTrigger.MEETING_NOTES_READY, "When meeting notes are ready");
		labels.put(AutomationTrigger.EXPORT_FINISHED, "After an export finishes");
		return Map.copyOf(labels);
	}

	private static Map<AutomationActionType, String> actionLabels() {
		Map<AutomationActionType, String> labels = new LinkedHashMap<>();
		labels.put(AutomationActionType.COPY_PATH, "Copy the path");
		labels.put(AutomationActionType.REVEAL, "Show in folder");
		labels.put(AutomationActionType.OPEN, "Open the file");
		labels.put(AutomationActionType.NOTIFY, "Show a notification");
		labels.put(AutomationActionType.COPY_TO_FOLDER, "Copy to a folder");
		labels.put(AutomationActionType.MOVE_TO_FOLDER, "Move to a folder");
		labels.put(AutomationActionType.RUN_COMMAND, "Run a command");
		return Map.copyOf(labels);
	}

	private static Map<AutomationConditionType, String> conditionLabels() {
		Map<AutomationConditionType, String> labels = new LinkedHashMap<>();
		labels.put(AutomationConditionType.DURATION_AT_LEAST, "Duration is at least");
		labels.put(AutomationConditionType.DURATION_AT_MOST, "Duration is at most");
		labels.put(AutomationConditionType.FORMAT_IS, "Format is");
		return Map.copyOf(labels);
	}

	private static List<AutomationTemplate> templates() {
		List<AutomationTemplate> templates = new ArrayList<>();
		templates.add(new AutomationTemplate(
				"copy-recording-path",
				"Copy the path of a new recording",
				"After a screen recording is saved, put its file path on the clipboard.",
				new AutomationRule(
						"",
						"Copy recording path",
						true,
						AutomationTrigger.RECORDING_SAVED,
						List.of(),
						List.of(new AutomationAction.CopyPath()))));
		templates.add(new AutomationTemplate(
				"move-export-and-reveal",
				"Move exports to a folder and show them",
				"After an export finishes, move it to ~/Videos/Exports named by date and show it in the folder.",
				new AutomationRule(
						"",
						"Collect exports",
						true,
						AutomationTrigger.EXPORT_FINISHED,
						List.of(),
						List.of(
								new AutomationAction.MoveToFolder("~/Videos/Exports", "{date} {name}"),
								new AutomationAction.Reveal()))));
		templates.add(new AutomationTemplate(
				"notify-notes-ready",
				"Notify when meeting notes are ready",
				"When a meeting job writes notes, show a desktop notification.",
				new AutomationRule(
						"",
						"Meeting notes ready",
						true,
						AutomationTrigger.MEETING_NOTES_READY,
						List.of(),
						List.of(new AutomationAction.Notify("Meeting notes ready", "Notes for {name} are ready.")))));
		templates.add(new AutomationTemplate(
				"run-command-after-export",
				"Run a command after an export",
				"After an export finishes, run a program with the file path as an argument. Fill in the program, then enable the rule.",
				new AutomationRule(
						"",
						"Run a script after export",
						false,
						AutomationTrigger.EXPORT_FINISHED,
						List.of(),
						List.of(new AutomationAction.RunCommand("", List.of("{path}"))))));
		return List.copyOf(templates);
	}
}
