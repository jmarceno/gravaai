package dev.gravaai.services.automations;

import java.util.List;

/** Port of {@code AutomationsFile}: the contents of {@code userData/automations.json}. */
public record AutomationsFile(int version, List<AutomationRule> rules) {

	public AutomationsFile {
		rules = rules == null ? List.of() : List.copyOf(rules);
	}
}
