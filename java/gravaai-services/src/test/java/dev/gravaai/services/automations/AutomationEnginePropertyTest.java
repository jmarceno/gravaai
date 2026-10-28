package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Replaces the fast-check style guarantees of the TS engine: whatever the event and whatever the
 * rule, the engine never throws. A bounded, fast generator keeps the suite quick.
 */
class AutomationEnginePropertyTest {

	@Provide
	Arbitrary<AutomationEvent> events() {
		Arbitrary<AutomationTrigger> triggers = Arbitraries.of(AutomationTrigger.values());
		Arbitrary<String> paths = Arbitraries.of("/tmp/a.mp4", "/tmp/fail.mp4", "", "relative b.gif", "/tmp/out.GIF");
		Arbitrary<AutomationMediaKind> kinds = Arbitraries
				.of(AutomationMediaKind.SCREEN, AutomationMediaKind.MEETING)
				.injectNull(0.3);
		Arbitrary<AutomationExportFormat> formats = Arbitraries
				.of(AutomationExportFormat.MP4, AutomationExportFormat.GIF)
				.injectNull(0.3);
		Arbitrary<String> notes = Arbitraries.of("/tmp/n.md", "").injectNull(0.3);
		Arbitrary<Double> durations = Arbitraries.of(-5.0, 0.0, 10.0, 30.0).injectNull(0.3);
		Arbitrary<Boolean> provided = Arbitraries.of(true, false);
		return Combinators.combine(triggers, paths, kinds, formats, notes, durations, provided)
				.as((trigger, path, kind, format, notesPath, duration, durationProvided) -> new AutomationEvent(
						trigger,
						path,
						kind,
						format,
						notesPath == null || notesPath.isEmpty() ? null : notesPath,
						durationProvided ? duration : null,
						durationProvided));
	}

	@Provide
	Arbitrary<AutomationCondition> conditions() {
		Arbitrary<AutomationConditionType> types = Arbitraries.of(AutomationConditionType.values());
		Arbitrary<Double> seconds = Arbitraries.doubles().between(0, 120);
		Arbitrary<AutomationExportFormat> formats = Arbitraries.of(AutomationExportFormat.values());
		return Combinators.combine(types, seconds, formats).as((type, value, format) -> switch (type) {
			case DURATION_AT_LEAST -> new AutomationCondition.DurationAtLeast(value);
			case DURATION_AT_MOST -> new AutomationCondition.DurationAtMost(value);
			case FORMAT_IS -> new AutomationCondition.FormatIs(format);
		});
	}

	@Provide
	Arbitrary<AutomationAction> actions() {
		Arbitrary<AutomationActionType> types = Arbitraries.of(AutomationActionType.values());
		Arbitrary<String> text = Arbitraries.strings().alpha().ofMaxLength(8);
		return Combinators.combine(types, text).as((type, value) -> switch (type) {
			case COPY_PATH -> new AutomationAction.CopyPath();
			case REVEAL -> new AutomationAction.Reveal();
			case OPEN -> new AutomationAction.Open();
			case NOTIFY -> new AutomationAction.Notify(value, "{path}");
			case COPY_TO_FOLDER -> new AutomationAction.CopyToFolder("/tmp/out-" + value, null);
			case MOVE_TO_FOLDER -> new AutomationAction.MoveToFolder("/tmp/out", null);
			case RUN_COMMAND -> new AutomationAction.RunCommand("/bin/true", List.of(value));
		});
	}

	@Provide
	Arbitrary<AutomationRule> rules() {
		Arbitrary<AutomationTrigger> triggers = Arbitraries.of(AutomationTrigger.values());
		Arbitrary<List<AutomationCondition>> conditions = conditions().list().ofMaxSize(2);
		Arbitrary<List<AutomationAction>> actions = actions().list().ofMaxSize(3);
		Arbitrary<Boolean> enabled = Arbitraries.of(true, false);
		Arbitrary<String> names = Arbitraries.strings().alpha().ofMaxLength(10);
		return Combinators.combine(triggers, conditions, actions, enabled, names)
				.as((trigger, ruleConditions, ruleActions, isEnabled, name) -> new AutomationRule(
						"rule-" + name, name.isEmpty() ? "Rule" : name, isEnabled, trigger, ruleConditions, ruleActions));
	}

	@Property(tries = 150)
	@Label("runAutomations and runAutomationRuleNow never throw for arbitrary rules and events")
	void neverThrows(@ForAll("events") AutomationEvent event, @ForAll("rules") AutomationRule rule) {
		AutomationEngine.Deps deps = new AutomationEngine.Deps(
				() -> List.of(rule),
				(action, context) -> {
					if (action.type() == AutomationActionType.COPY_TO_FOLDER
							|| action.type() == AutomationActionType.MOVE_TO_FOLDER
							|| action.type() == AutomationActionType.RUN_COMMAND) {
						throw new IllegalStateException("action failed");
					}
					return new AutomationActionOutcome(context.path());
				},
				path -> {
					if (path.toString().contains("fail")) {
						throw new IOException("probe failed");
					}
					return 30.0;
				},
				(title, body) -> {
					// The engine reports once per rule; collecting is not needed for this property.
				},
				message -> {},
				() -> Instant.EPOCH);

		assertDoesNotThrow(() -> AutomationEngine.runAutomations(event, deps));
		assertDoesNotThrow(() -> AutomationEngine.runAutomationRuleNow(rule.id(), event, deps));
	}

	@Property(tries = 100)
	@Label("a failing rule store and a failing probe never make the engine throw")
	void failingDependenciesNeverThrow(@ForAll("events") AutomationEvent event) {
		AutomationEngine.Deps failingStore = new AutomationEngine.Deps(
				() -> {
					throw new IllegalStateException("store failed");
				},
				(action, context) -> new AutomationActionOutcome(context.path()),
				path -> {
					throw new IOException("probe failed");
				},
				(title, body) -> {},
				message -> {},
				() -> Instant.EPOCH);
		assertDoesNotThrow(() -> AutomationEngine.runAutomations(event, failingStore));
	}
}
