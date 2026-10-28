package dev.gravaai.services.automations;

import java.util.List;

/** Port of {@code AutomationRule}: one rule of {@code userData/automations.json}. */
public record AutomationRule(
		String id,
		String name,
		boolean enabled,
		AutomationTrigger trigger,
		List<AutomationCondition> conditions,
		List<AutomationAction> actions) {

	public AutomationRule {
		id = id == null ? "" : id;
		name = name == null ? "" : name;
		conditions = conditions == null ? List.of() : List.copyOf(conditions);
		actions = actions == null ? List.of() : List.copyOf(actions);
	}
}
