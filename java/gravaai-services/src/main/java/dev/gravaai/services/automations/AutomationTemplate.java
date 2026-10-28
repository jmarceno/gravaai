package dev.gravaai.services.automations;

/**
 * Port of the {@code AutomationTemplate} interface: a ready-made rule shown by the settings page.
 * {@code rule.id()} is empty, exactly like the TS {@code Omit<AutomationRule, "id">}.
 */
public record AutomationTemplate(String id, String name, String description, AutomationRule rule) {}
