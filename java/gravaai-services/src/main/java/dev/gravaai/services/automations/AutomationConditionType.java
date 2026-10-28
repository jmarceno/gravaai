package dev.gravaai.services.automations;

/** The three condition types of {@code AutomationCondition} (port of the TS union's {@code type}). */
public enum AutomationConditionType {
	DURATION_AT_LEAST("durationAtLeast"),
	DURATION_AT_MOST("durationAtMost"),
	FORMAT_IS("formatIs");

	private final String wire;

	AutomationConditionType(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}
}
