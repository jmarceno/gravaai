package dev.gravaai.services.automations;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The automation engine (port of {@code electron/automations/engine.ts}).
 *
 * <p>It never throws: failures are isolated per action and reported once per rule through the
 * injected feedback hooks, so a broken rule can never break recording, exporting or meeting
 * notes. Every dependency is injected (plan §5.3) so the service and the tests control the rule
 * store, the action runner, the duration probe, the clock and the failure feedback.
 */
public final class AutomationEngine {

	private static final System.Logger LOG = System.getLogger("gravaai.automations");

	private AutomationEngine() {}

	/**
	 * Engine dependencies; the TS defaults that dynamically import other modules become constructor
	 * arguments here. Null {@code notifyFailure}/{@code log}/{@code now} get logging/clock
	 * defaults; {@code loadRules}/{@code runAction} are required.
	 */
	public record Deps(
			Supplier<List<AutomationRule>> loadRules,
			BiFunction<AutomationAction, AutomationContext, AutomationActionOutcome> runAction,
			AutomationDurationProbe probeDurationSec,
			BiConsumer<String, String> notifyFailure,
			Consumer<String> log,
			Supplier<Instant> now) {

		public Deps {
			Objects.requireNonNull(loadRules, "loadRules");
			Objects.requireNonNull(runAction, "runAction");
			if (notifyFailure == null) {
				notifyFailure = (title, body) -> LOG.log(System.Logger.Level.WARNING, "[automations] " + title + ": " + body);
			}
			if (log == null) {
				log = AutomationEngine::defaultLog;
			}
			if (now == null) {
				now = Instant::now;
			}
		}
	}

	private static void defaultLog(String message) {
		LOG.log(System.Logger.Level.WARNING, message);
	}

	/** Runs every enabled rule matching the event. Never throws (port of {@code runAutomations}). */
	public static AutomationRunResult runAutomations(AutomationEvent event, Deps deps) {
		ResultBuilder result = new ResultBuilder(event.trigger());
		List<AutomationRule> rules;
		try {
			rules = deps.loadRules().get();
		} catch (RuntimeException error) {
			deps.log().accept("[automations] Could not load rules: " + describe(error));
			return result.build();
		}
		List<AutomationRule> matching = new ArrayList<>();
		if (rules != null) {
			for (AutomationRule rule : rules) {
				if (rule != null && rule.enabled() && rule.trigger() == event.trigger()) {
					matching.add(rule);
				}
			}
		}
		if (matching.isEmpty()) {
			return result.build();
		}
		return runMatchingRules(matching, event, deps, result);
	}

	/**
	 * Runs one rule regardless of its enabled flag, for the "Run now on a file…" button (port of
	 * {@code runAutomationRuleNow}). The duration probe is used only if the rule asks for it.
	 */
	public static AutomationRunResult runAutomationRuleNow(String ruleId, AutomationEvent event, Deps deps) {
		ResultBuilder result = new ResultBuilder(event.trigger());
		List<AutomationRule> rules;
		try {
			rules = deps.loadRules().get();
		} catch (RuntimeException error) {
			deps.log().accept("[automations] Could not load rules: " + describe(error));
			return result.build();
		}
		AutomationRule rule = null;
		if (rules != null) {
			for (AutomationRule entry : rules) {
				if (entry != null && entry.id().equals(ruleId)) {
					rule = entry;
					break;
				}
			}
		}
		if (rule == null) {
			result.skipped.add(new AutomationSkip(ruleId, ruleId, "rule not found"));
			return result.build();
		}
		return runMatchingRules(List.of(rule), event.withTrigger(rule.trigger()), deps, result);
	}

	private static AutomationRunResult runMatchingRules(
			List<AutomationRule> matching, AutomationEvent event, Deps deps, ResultBuilder result) {
		Double durationSec = resolveDurationSec(event, matching, deps);
		AutomationContext context = new AutomationContext(
				event.trigger(),
				event.path(),
				event.kind(),
				event.format(),
				event.notesPath(),
				durationSec,
				deps.now().get());
		for (AutomationRule rule : matching) {
			String reason = firstUnmetCondition(rule, context);
			if (reason != null) {
				result.skipped.add(new AutomationSkip(rule.id(), rule.name(), reason));
				continue;
			}
			result.matchedRuleIds.add(rule.id());
			executeRule(rule, context, deps, result);
		}
		return result.build();
	}

	private static void executeRule(
			AutomationRule rule, AutomationContext context, Deps deps, ResultBuilder result) {
		AutomationContext current = context;
		boolean reportedFailure = false;
		for (AutomationAction action : rule.actions()) {
			if (!Automations.actionAllowedForTrigger(action.type(), rule.trigger())) {
				String message =
						"\"" + action.type().wire() + "\" is not available for " + rule.trigger().wire() + " rules";
				deps.log().accept("[automations] " + rule.name() + ": " + message);
				result.failures.add(new AutomationFailure(rule.id(), rule.name(), action.type(), message));
				if (!reportedFailure) {
					deps.notifyFailure().accept("Automation failed", rule.name() + ": " + message);
					reportedFailure = true;
				}
				continue;
			}
			try {
				AutomationActionOutcome outcome = deps.runAction().apply(action, current);
				result.ranActions += 1;
				if (outcome != null
						&& outcome.path() != null
						&& !outcome.path().isEmpty()
						&& !outcome.path().equals(current.path())) {
					current = current.withPath(outcome.path());
				}
			} catch (RuntimeException error) {
				String message = describe(error);
				deps.log().accept(
						"[automations] " + rule.name() + ": " + action.type().wire() + " failed: " + message);
				result.failures.add(new AutomationFailure(rule.id(), rule.name(), action.type(), message));
				if (!reportedFailure) {
					deps.notifyFailure().accept("Automation failed", rule.name() + ": " + message);
					reportedFailure = true;
				}
			}
		}
	}

	private static boolean hasDurationCondition(AutomationRule rule) {
		for (AutomationCondition condition : rule.conditions()) {
			if (condition.type() == AutomationConditionType.DURATION_AT_LEAST
					|| condition.type() == AutomationConditionType.DURATION_AT_MOST) {
				return true;
			}
		}
		return false;
	}

	private static String conditionMatches(AutomationCondition condition, AutomationContext context) {
		if (condition instanceof AutomationCondition.FormatIs formatIs) {
			if (context.format() != formatIs.format()) {
				return context.format() != null
						? "format is " + context.format().wire() + ", not " + formatIs.format().wire()
						: "format is unknown, not " + formatIs.format().wire();
			}
			return null;
		}
		Double duration = context.durationSec();
		if (duration == null) {
			return "duration is unknown";
		}
		if (condition instanceof AutomationCondition.DurationAtLeast atLeast && duration < atLeast.seconds()) {
			return "duration " + jsNumber(duration) + "s is under " + jsNumber(atLeast.seconds()) + "s";
		}
		if (condition instanceof AutomationCondition.DurationAtMost atMost && duration > atMost.seconds()) {
			return "duration " + jsNumber(duration) + "s is over " + jsNumber(atMost.seconds()) + "s";
		}
		return null;
	}

	private static String firstUnmetCondition(AutomationRule rule, AutomationContext context) {
		for (AutomationCondition condition : rule.conditions()) {
			String reason = conditionMatches(condition, context);
			if (reason != null) {
				return reason;
			}
		}
		return null;
	}

	private static Double resolveDurationSec(
			AutomationEvent event, List<AutomationRule> rules, Deps deps) {
		if (event.durationSecProvided()) {
			return event.durationSec();
		}
		boolean needed = false;
		for (AutomationRule rule : rules) {
			if (hasDurationCondition(rule)) {
				needed = true;
				break;
			}
		}
		if (!needed) {
			return null;
		}
		AutomationDurationProbe probe = deps.probeDurationSec();
		if (probe == null) {
			return null;
		}
		try {
			return probe.probeDurationSec(Path.of(event.path()));
		} catch (Exception error) {
			deps.log().accept(
					"[automations] Could not read the duration of " + event.path() + ": " + describe(error));
			return null;
		}
	}

	/** JavaScript number stringification: integral values have no ".0". */
	private static String jsNumber(double value) {
		if (value == Math.rint(value) && Math.abs(value) <= 9.007199254740992E15) {
			return Long.toString((long) value);
		}
		return Double.toString(value);
	}

	private static String describe(Throwable error) {
		return error.getMessage() != null ? error.getMessage() : error.toString();
	}

	/** Mutable accumulator for one run; the public result is an immutable record. */
	private static final class ResultBuilder {
		private final AutomationTrigger trigger;
		private final List<String> matchedRuleIds = new ArrayList<>();
		private int ranActions;
		private final List<AutomationFailure> failures = new ArrayList<>();
		private final List<AutomationSkip> skipped = new ArrayList<>();

		ResultBuilder(AutomationTrigger trigger) {
			this.trigger = trigger;
		}

		AutomationRunResult build() {
			return new AutomationRunResult(trigger, matchedRuleIds, ranActions, failures, skipped);
		}
	}
}
