package dev.gravaai.services.automations;

/** Port of {@code AutomationSkip}: a rule that did not run because a condition was not met. */
public record AutomationSkip(String ruleId, String ruleName, String reason) {}
