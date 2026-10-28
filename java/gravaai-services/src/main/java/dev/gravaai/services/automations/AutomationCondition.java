package dev.gravaai.services.automations;

/**
 * A rule condition (port of the discriminated {@code AutomationCondition} union): an inclusive
 * duration bound in seconds or an export format.
 */
public sealed interface AutomationCondition
		permits AutomationCondition.DurationAtLeast, AutomationCondition.DurationAtMost, AutomationCondition.FormatIs {

	AutomationConditionType type();

	record DurationAtLeast(double seconds) implements AutomationCondition {
		@Override
		public AutomationConditionType type() {
			return AutomationConditionType.DURATION_AT_LEAST;
		}
	}

	record DurationAtMost(double seconds) implements AutomationCondition {
		@Override
		public AutomationConditionType type() {
			return AutomationConditionType.DURATION_AT_MOST;
		}
	}

	record FormatIs(AutomationExportFormat format) implements AutomationCondition {
		@Override
		public AutomationConditionType type() {
			return AutomationConditionType.FORMAT_IS;
		}
	}
}
