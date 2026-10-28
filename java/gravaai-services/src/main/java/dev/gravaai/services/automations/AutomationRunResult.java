package dev.gravaai.services.automations;

import java.util.List;

/** Port of {@code AutomationRunResult}: the outcome of one automation event. */
public record AutomationRunResult(
		AutomationTrigger trigger,
		List<String> matchedRuleIds,
		int ranActions,
		List<AutomationFailure> failures,
		List<AutomationSkip> skipped) {

	public AutomationRunResult {
		matchedRuleIds = matchedRuleIds == null ? List.of() : List.copyOf(matchedRuleIds);
		failures = failures == null ? List.of() : List.copyOf(failures);
		skipped = skipped == null ? List.of() : List.copyOf(skipped);
	}
}
