package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Port of {@code electron/automations/engine.test.ts}.
 */
class AutomationEngineTest {

	private static final Instant FIXED =
			LocalDateTime.of(2026, 10, 24, 13, 45, 6).atZone(ZoneId.systemDefault()).toInstant();

	private record Ran(AutomationAction action, String path) {}

	private static final class Harness {
		final List<Ran> ran = new ArrayList<>();
		final List<String[]> notifications = new ArrayList<>();
		final List<String> logs = new ArrayList<>();
		Supplier<List<AutomationRule>> loadRules;
		BiFunction<AutomationAction, AutomationContext, AutomationActionOutcome> runAction;
		AutomationDurationProbe probe;
		Supplier<Instant> now = () -> FIXED;

		Harness(List<AutomationRule> rules) {
			this.loadRules = () -> rules;
			this.runAction = (action, context) -> {
				ran.add(new Ran(action, context.path()));
				return new AutomationActionOutcome(context.path());
			};
		}

		AutomationEngine.Deps deps() {
			return new AutomationEngine.Deps(
					loadRules,
					runAction,
					probe,
					(title, body) -> notifications.add(new String[] {title, body}),
					logs::add,
					now);
		}
	}

	private static AutomationRule rule(
			String id,
			String name,
			boolean enabled,
			AutomationTrigger trigger,
			List<AutomationCondition> conditions,
			List<AutomationAction> actions) {
		return new AutomationRule(id, name, enabled, trigger, conditions, actions);
	}

	private static AutomationRule copyPathRule() {
		return rule("rule-1", "Copy path", true, AutomationTrigger.RECORDING_SAVED, List.of(),
				List.of(new AutomationAction.CopyPath()));
	}

	private static AutomationEvent recordingEvent(String path) {
		return new AutomationEvent(
				AutomationTrigger.RECORDING_SAVED, path, AutomationMediaKind.SCREEN, null, null, null, false);
	}

	@Test
	@DisplayName("runs only enabled rules for the event trigger")
	void runsOnlyEnabledRules() {
		List<AutomationRule> rules = List.of(
				copyPathRule(),
				rule("b", "Disabled", false, AutomationTrigger.RECORDING_SAVED, List.of(), List.of(new AutomationAction.CopyPath())),
				rule("c", "Export", true, AutomationTrigger.EXPORT_FINISHED, List.of(), List.of(new AutomationAction.CopyPath())));
		Harness harness = new Harness(rules);

		AutomationRunResult result = AutomationEngine.runAutomations(recordingEvent("/tmp/demo.mp4"), harness.deps());

		assertEquals(List.of("rule-1"), result.matchedRuleIds());
		assertEquals(1, harness.ran.size());
		assertEquals(List.of(), result.failures());
	}

	@Test
	@DisplayName("runs actions in order and passes template context")
	void runsActionsInOrder() {
		List<AutomationRule> rules = List.of(rule(
				"rule-1", "Copy path", true, AutomationTrigger.RECORDING_SAVED, List.of(),
				List.of(new AutomationAction.Notify("Saved {name}", "{path}"), new AutomationAction.Reveal())));
		Harness harness = new Harness(rules);

		AutomationEngine.runAutomations(recordingEvent("/tmp/demo.mp4"), harness.deps());

		assertEquals(
				List.of(AutomationActionType.NOTIFY, AutomationActionType.REVEAL),
				harness.ran.stream().map(entry -> entry.action().type()).toList());
		assertEquals("/tmp/demo.mp4", harness.ran.get(0).path());
	}

	@Test
	@DisplayName("uses the path returned by copy/move actions for later actions")
	void usesReturnedPath() {
		List<AutomationRule> rules = List.of(rule(
				"rule-1", "Copy path", true, AutomationTrigger.EXPORT_FINISHED, List.of(),
				List.of(
						new AutomationAction.MoveToFolder("/tmp/exports", "{date} {name}"),
						new AutomationAction.Reveal())));
		Harness harness = new Harness(rules);
		harness.runAction = (action, context) -> {
			harness.ran.add(new Ran(action, context.path()));
			return action.type() == AutomationActionType.MOVE_TO_FOLDER
					? new AutomationActionOutcome("/tmp/exports/moved.mp4")
					: new AutomationActionOutcome(context.path());
		};

		AutomationEngine.runAutomations(
				new AutomationEvent(
						AutomationTrigger.EXPORT_FINISHED, "/tmp/out.mp4", null, AutomationExportFormat.MP4, null, null, false),
				harness.deps());

		assertEquals("/tmp/exports/moved.mp4", harness.ran.get(1).path());
	}

	@Test
	@DisplayName("isolates a failing action, reports it once and keeps going")
	void isolatesFailingAction() {
		List<AutomationRule> rules = List.of(rule(
				"rule-1", "Broken rule", true, AutomationTrigger.RECORDING_SAVED, List.of(),
				List.of(
						new AutomationAction.CopyPath(),
						new AutomationAction.CopyToFolder("/root/forbidden", null),
						new AutomationAction.Reveal())));
		Harness harness = new Harness(rules);
		harness.runAction = (action, context) -> {
			harness.ran.add(new Ran(action, context.path()));
			if (action.type() == AutomationActionType.COPY_TO_FOLDER) {
				throw new IllegalStateException("EACCES: permission denied");
			}
			return new AutomationActionOutcome(context.path());
		};

		AutomationRunResult result =
				AutomationEngine.runAutomations(recordingEvent("/tmp/demo.mp4"), harness.deps());

		assertEquals(2, result.ranActions());
		assertEquals(
				List.of(new AutomationFailure(
						"rule-1", "Broken rule", AutomationActionType.COPY_TO_FOLDER, "EACCES: permission denied")),
				result.failures());
		assertEquals(1, harness.notifications.size());
		assertEquals("Automation failed", harness.notifications.get(0)[0]);
		assertEquals("Broken rule: EACCES: permission denied", harness.notifications.get(0)[1]);
		assertTrue(harness.logs.get(0).contains("Broken rule: copy-to-folder failed"), harness.logs.get(0));
		assertEquals(
				List.of(AutomationActionType.COPY_PATH, AutomationActionType.COPY_TO_FOLDER, AutomationActionType.REVEAL),
				harness.ran.stream().map(entry -> entry.action().type()).toList());
	}

	@Test
	@DisplayName("refuses move-to-folder outside export rules and never calls the runner")
	void refusesMoveOutsideExports() {
		List<AutomationRule> rules = List.of(rule(
				"rule-1", "Copy path", true, AutomationTrigger.RECORDING_SAVED, List.of(),
				List.of(new AutomationAction.MoveToFolder("/tmp/x", null), new AutomationAction.Reveal())));
		Harness harness = new Harness(rules);

		AutomationRunResult result =
				AutomationEngine.runAutomations(recordingEvent("/tmp/demo.mp4"), harness.deps());

		assertEquals(List.of(AutomationActionType.REVEAL), harness.ran.stream().map(entry -> entry.action().type()).toList());
		assertTrue(result.failures().get(0).message().contains("not available for recording-saved rules"));
		assertEquals(1, harness.notifications.size());
	}

	@Test
	@DisplayName("checks duration conditions and skips when the duration is unknown")
	void checksDurationConditions() {
		List<AutomationRule> rules = List.of(rule(
				"long", "Long recordings only", true, AutomationTrigger.RECORDING_SAVED,
				List.of(new AutomationCondition.DurationAtLeast(60)), List.of(new AutomationAction.CopyPath())));
		Harness harness = new Harness(rules);

		AutomationRunResult shortResult = AutomationEngine.runAutomations(
				recordingEvent("/tmp/demo.mp4").withDuration(10.0), harness.deps());
		assertEquals(List.of(), shortResult.matchedRuleIds());
		assertTrue(shortResult.skipped().get(0).reason().contains("under 60s"), shortResult.skipped().get(0).reason());
		assertEquals(0, harness.ran.size());

		AutomationRunResult longResult = AutomationEngine.runAutomations(
				recordingEvent("/tmp/demo.mp4").withDuration(70.0), harness.deps());
		assertEquals(List.of("long"), longResult.matchedRuleIds());
		assertEquals(1, harness.ran.size());

		AutomationRunResult unknown = AutomationEngine.runAutomations(
				recordingEvent("/tmp/demo.mp4").withDuration(null), harness.deps());
		assertEquals(List.of(), unknown.matchedRuleIds());
		assertEquals("duration is unknown", unknown.skipped().get(0).reason());
	}

	@Test
	@DisplayName("probes the duration lazily, only for rules that ask for it")
	void probesDurationLazily() {
		AtomicInteger calls = new AtomicInteger();
		java.util.concurrent.atomic.AtomicReference<Path> probed = new java.util.concurrent.atomic.AtomicReference<>();
		AutomationDurationProbe probe = path -> {
			calls.incrementAndGet();
			probed.set(path);
			return 120.0;
		};
		Harness plain = new Harness(List.of(copyPathRule()));
		plain.probe = probe;

		AutomationEngine.runAutomations(recordingEvent("/tmp/demo.mp4"), plain.deps());
		assertEquals(0, calls.get());

		Harness withCondition = new Harness(List.of(rule(
				"duration", "Copy path", true, AutomationTrigger.RECORDING_SAVED,
				List.of(new AutomationCondition.DurationAtMost(60)), List.of(new AutomationAction.CopyPath()))));
		withCondition.probe = probe;
		AutomationRunResult result =
				AutomationEngine.runAutomations(recordingEvent("/tmp/demo.mp4"), withCondition.deps());
		assertEquals(1, calls.get());
		assertEquals(Path.of("/tmp/demo.mp4"), probed.get());
		assertEquals(List.of(), result.matchedRuleIds());
		assertTrue(result.skipped().get(0).reason().contains("over 60s"), result.skipped().get(0).reason());
	}

	@Test
	@DisplayName("checks the export format condition")
	void checksFormatCondition() {
		List<AutomationRule> rules = List.of(rule(
				"rule-1", "Copy path", true, AutomationTrigger.EXPORT_FINISHED,
				List.of(new AutomationCondition.FormatIs(AutomationExportFormat.GIF)),
				List.of(new AutomationAction.Reveal())));
		Harness harness = new Harness(rules);

		AutomationRunResult gif = AutomationEngine.runAutomations(
				new AutomationEvent(
						AutomationTrigger.EXPORT_FINISHED, "/tmp/out.gif", null, AutomationExportFormat.GIF, null, null, false),
				harness.deps());
		assertEquals(List.of("rule-1"), gif.matchedRuleIds());

		AutomationRunResult mp4 = AutomationEngine.runAutomations(
				new AutomationEvent(
						AutomationTrigger.EXPORT_FINISHED, "/tmp/out.mp4", null, AutomationExportFormat.MP4, null, null, false),
				harness.deps());
		assertEquals(List.of(), mp4.matchedRuleIds());
		assertEquals("format is mp4, not gif", mp4.skipped().get(0).reason());
		assertEquals(1, harness.ran.size());
	}

	@Test
	@DisplayName("never throws when the rule store fails")
	void neverThrowsWhenStoreFails() {
		Harness harness = new Harness(List.of());
		harness.loadRules = () -> {
			throw new IllegalStateException("disk unavailable");
		};

		AutomationRunResult result =
				AutomationEngine.runAutomations(recordingEvent("/tmp/demo.mp4"), harness.deps());

		assertEquals(List.of(), result.matchedRuleIds());
		assertTrue(harness.logs.get(0).contains("Could not load rules: disk unavailable"), harness.logs.get(0));
	}

	@Test
	@DisplayName("uses the injected clock for templates")
	void usesInjectedClock() {
		List<AutomationRule> rules = List.of(rule(
				"rule-1", "Copy path", true, AutomationTrigger.RECORDING_SAVED, List.of(),
				List.of(new AutomationAction.Notify("{date} {time}", ""))));
		Harness harness = new Harness(rules);
		List<Instant> received = new ArrayList<>();
		harness.runAction = (action, context) -> {
			received.add(context.at());
			return new AutomationActionOutcome(context.path());
		};

		AutomationEngine.runAutomations(recordingEvent("/tmp/demo.mp4"), harness.deps());

		assertEquals(List.of(FIXED), received);
	}

	@Test
	@DisplayName("runAutomationRuleNow runs a disabled rule and reports an unknown rule")
	void runsRuleNow() {
		Harness harness = new Harness(List.of(
				rule("disabled", "Disabled", false, AutomationTrigger.RECORDING_SAVED, List.of(),
						List.of(new AutomationAction.CopyPath())),
				copyPathRule()));

		AutomationRunResult result = AutomationEngine.runAutomationRuleNow(
				"disabled", recordingEvent("/tmp/demo.mp4"), harness.deps());
		assertEquals(List.of("disabled"), result.matchedRuleIds());
		assertEquals(1, harness.ran.size());

		AutomationRunResult missing = AutomationEngine.runAutomationRuleNow(
				"nope", recordingEvent("/tmp/demo.mp4"), harness.deps());
		assertEquals("rule not found", missing.skipped().get(0).reason());
	}
}
