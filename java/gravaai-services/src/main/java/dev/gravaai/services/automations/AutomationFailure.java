package dev.gravaai.services.automations;

/** Port of {@code AutomationFailure}: one failed action, reported once per rule. */
public record AutomationFailure(
		String ruleId, String ruleName, AutomationActionType action, String message) {}
