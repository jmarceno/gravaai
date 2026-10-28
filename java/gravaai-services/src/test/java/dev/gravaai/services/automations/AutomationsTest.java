package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Port of {@code src/types/automations.test.ts}.
 */
class AutomationsTest {

	private static JsonNode json(String text) {
		return Json.MAPPER.readTree(text);
	}

	@Test
	@DisplayName("normalizeAutomationsFile returns an empty file for garbage input")
	void emptyForGarbage() {
		assertEquals(new AutomationsFile(1, List.of()), Automations.normalizeAutomationsFile(null));
		assertEquals(new AutomationsFile(1, List.of()), Automations.normalizeAutomationsFile(json("42")));
		assertEquals(
				new AutomationsFile(1, List.of()), Automations.normalizeAutomationsFile(json("{\"rules\":\"nope\"}")));
		assertEquals(new AutomationsFile(1, List.of()), Automations.createEmptyAutomationsFile());
	}

	@Test
	@DisplayName("normalizeAutomationsFile drops rules with unknown triggers and keeps known ones")
	void dropsUnknownTriggers() {
		AutomationsFile file = Automations.normalizeAutomationsFile(json("""
				{
				  "version": 99,
				  "rules": [
				    {"id": "a", "name": "Keep", "enabled": true, "trigger": "recording-saved",
				     "actions": [{"type": "reveal"}]},
				    {"id": "b", "name": "Drop", "trigger": "webhook", "actions": [{"type": "reveal"}]}
				  ]
				}
				"""));
		assertEquals(1, file.rules().size());
		assertEquals("a", file.rules().get(0).id());
	}

	@Test
	@DisplayName("normalizeAutomationsFile drops unknown actions and conditions")
	void dropsUnknownActionsAndConditions() {
		AutomationsFile file = Automations.normalizeAutomationsFile(json("""
				{
				  "rules": [
				    {
				      "id": "a",
				      "name": "Mixed",
				      "trigger": "recording-saved",
				      "conditions": [{"type": "durationAtLeast", "seconds": 30}, {"type": "magic"}],
				      "actions": [{"type": "copy-path"}, {"type": "upload"}, {"type": "notify"}]
				    }
				  ]
				}
				"""));
		assertEquals(
				List.of(new AutomationCondition.DurationAtLeast(30)),
				file.rules().get(0).conditions());
		assertEquals(
				List.of(new AutomationAction.CopyPath(), new AutomationAction.Notify("", "")),
				file.rules().get(0).actions());
	}

	@Test
	@DisplayName("normalizeAutomationsFile coerces numbers, clamps negatives and strings")
	void coercesNumbers() {
		AutomationsFile file = Automations.normalizeAutomationsFile(json("""
				{
				  "rules": [
				    {
				      "id": "a",
				      "name": "Numbers",
				      "trigger": "recording-saved",
				      "conditions": [
				        {"type": "durationAtLeast", "seconds": "-5"},
				        {"type": "durationAtMost", "seconds": "12.5"}
				      ],
				      "actions": []
				    }
				  ]
				}
				"""));
		assertEquals(
				List.of(
						new AutomationCondition.DurationAtLeast(0),
						new AutomationCondition.DurationAtMost(12.5)),
				file.rules().get(0).conditions());
	}

	@Test
	@DisplayName("normalizeAutomationsFile keeps move-to-folder only for export rules and drops formatIs elsewhere")
	void moveToFolderOnlyForExports() {
		AutomationsFile file = Automations.normalizeAutomationsFile(json("""
				{
				  "rules": [
				    {
				      "id": "recording",
				      "name": "Recording",
				      "trigger": "meeting-saved",
				      "conditions": [{"type": "formatIs", "format": "gif"}],
				      "actions": [
				        {"type": "move-to-folder", "folder": "~/Videos"},
				        {"type": "copy-to-folder", "folder": "~/Videos"}
				      ]
				    },
				    {
				      "id": "export",
				      "name": "Export",
				      "trigger": "export-finished",
				      "conditions": [{"type": "formatIs", "format": "gif"}],
				      "actions": [{"type": "move-to-folder", "folder": "~/Videos"}]
				    }
				  ]
				}
				"""));
		assertEquals(List.of(), file.rules().get(0).conditions());
		assertEquals(
				List.of(new AutomationAction.CopyToFolder("~/Videos", null)), file.rules().get(0).actions());
		assertEquals(
				List.of(new AutomationCondition.FormatIs(AutomationExportFormat.GIF)),
				file.rules().get(1).conditions());
		assertEquals(1, file.rules().get(1).actions().size());
	}

	@Test
	@DisplayName("normalizeAutomationsFile generates missing and duplicate ids")
	void generatesIds() {
		AtomicInteger counter = new AtomicInteger();
		AutomationsFile file = Automations.normalizeAutomationsFile(
				json("""
						{
						  "rules": [
						    {"name": "No id", "trigger": "recording-saved", "actions": [{"type": "copy-path"}]},
						    {"id": "same", "name": "One", "trigger": "recording-saved",
						     "actions": [{"type": "copy-path"}]},
						    {"id": "same", "name": "Two", "trigger": "recording-saved",
						     "actions": [{"type": "copy-path"}]}
						  ]
						}
						"""),
				() -> "generated-" + counter.incrementAndGet());
		assertEquals("generated-1", file.rules().get(0).id());
		assertEquals("same", file.rules().get(1).id());
		assertEquals("generated-2", file.rules().get(2).id());
	}

	@Test
	@DisplayName("normalizeAutomationsFile limits rules, actions and arguments")
	void limits() {
		StringBuilder rules = new StringBuilder("{\"rules\":[");
		for (int index = 0; index < 60; index++) {
			if (index > 0) {
				rules.append(',');
			}
			rules.append("{\"id\":\"r").append(index).append("\",\"name\":\"Rule ").append(index)
					.append("\",\"trigger\":\"recording-saved\",\"actions\":[");
			for (int action = 0; action < 25; action++) {
				if (action > 0) {
					rules.append(',');
				}
				rules.append("{\"type\":\"reveal\"}");
			}
			rules.append("]}");
		}
		rules.append("]}");
		AutomationsFile manyRules = Automations.normalizeAutomationsFile(json(rules.toString()));
		assertEquals(50, manyRules.rules().size());
		assertEquals(20, manyRules.rules().get(0).actions().size());

		StringBuilder args = new StringBuilder();
		for (int index = 0; index < 40; index++) {
			if (index > 0) {
				args.append(',');
			}
			args.append('"').append(index).append('"');
		}
		AutomationsFile manyArgs = Automations.normalizeAutomationsFile(json(
				"{\"rules\":[{\"id\":\"a\",\"name\":\"Args\",\"trigger\":\"export-finished\","
						+ "\"actions\":[{\"type\":\"run-command\",\"program\":\"echo\",\"args\":["
						+ args + "]}]}]}"));
		assertEquals(32, ((AutomationAction.RunCommand) manyArgs.rules().get(0).actions().get(0)).args().size());
	}

	@Test
	@DisplayName("normalizeAutomationsFile defaults the name and enabled flag")
	void defaultsNameAndEnabled() {
		AutomationsFile file = Automations.normalizeAutomationsFile(
				json("{\"rules\":[{\"id\":\"a\",\"trigger\":\"recording-saved\",\"actions\":[]}]}"));
		assertEquals("Untitled automation", file.rules().get(0).name());
		assertFalse(file.rules().get(0).enabled());
	}

	@Test
	@DisplayName("normalizeAutomationsFile keeps a run-command template with an empty program")
	void keepsEmptyProgramTemplate() {
		AutomationRule template = Automations.AUTOMATION_TEMPLATES.get(3).rule();
		AutomationsFile wrapped = Automations.normalizeAutomationsFile(
				Automations.toJson(new AutomationsFile(1, List.of(new AutomationRule(
						"template", template.name(), template.enabled(), template.trigger(),
						template.conditions(), template.actions())))));
		assertEquals(
				new AutomationAction.RunCommand("", List.of("{path}")),
				wrapped.rules().get(0).actions().get(0));
	}

	@Test
	@DisplayName("actionAllowedForTrigger allows move-to-folder only for exports")
	void actionAllowedForTrigger() {
		assertTrue(Automations.actionAllowedForTrigger(AutomationActionType.MOVE_TO_FOLDER, AutomationTrigger.EXPORT_FINISHED));
		assertFalse(Automations.actionAllowedForTrigger(AutomationActionType.MOVE_TO_FOLDER, AutomationTrigger.RECORDING_SAVED));
		assertFalse(Automations.actionAllowedForTrigger(AutomationActionType.MOVE_TO_FOLDER, AutomationTrigger.MEETING_SAVED));
		assertFalse(Automations.actionAllowedForTrigger(
				AutomationActionType.MOVE_TO_FOLDER, AutomationTrigger.MEETING_NOTES_READY));
		for (AutomationActionType action : List.of(
				AutomationActionType.COPY_PATH,
				AutomationActionType.REVEAL,
				AutomationActionType.OPEN,
				AutomationActionType.NOTIFY,
				AutomationActionType.COPY_TO_FOLDER,
				AutomationActionType.RUN_COMMAND)) {
			assertTrue(Automations.actionAllowedForTrigger(action, AutomationTrigger.RECORDING_SAVED), action.wire());
		}
	}

	@Test
	@DisplayName("AUTOMATION_TEMPLATES ships four usable templates with unique ids")
	void templatesAreUsable() {
		assertEquals(4, Automations.AUTOMATION_TEMPLATES.size());
		Map<String, Boolean> ids = new LinkedHashMap<>();
		for (AutomationTemplate template : Automations.AUTOMATION_TEMPLATES) {
			ids.put(template.id(), Boolean.TRUE);
			assertFalse(template.rule().actions().isEmpty(), template.id());
			for (AutomationAction action : template.rule().actions()) {
				assertTrue(
						Automations.actionAllowedForTrigger(action.type(), template.rule().trigger()),
						template.id());
			}
		}
		assertEquals(4, ids.size());
		AutomationTemplate script = Automations.AUTOMATION_TEMPLATES.stream()
				.filter(template -> template.id().equals("run-command-after-export"))
				.findFirst()
				.orElseThrow();
		assertFalse(script.rule().enabled());
	}

	@Test
	@DisplayName("automationRuleIssues reports issues the UI should surface")
	void ruleIssues() {
		assertTrue(Automations.automationRuleIssues(Automations.AUTOMATION_TEMPLATES.get(3).rule())
				.contains("The run command needs a program."));
		assertTrue(Automations.automationRuleIssues(new AutomationRule(
						"x", "Empty", true, AutomationTrigger.RECORDING_SAVED, List.of(), List.of()))
				.contains("Add at least one action."));
		assertTrue(Automations.automationRuleIssues(new AutomationRule(
						"x",
						"Folder",
						true,
						AutomationTrigger.EXPORT_FINISHED,
						List.of(),
						List.of(new AutomationAction.CopyToFolder("  ", null))))
				.contains("Choose a folder for the copy or move action."));
	}

	@Test
	@DisplayName("findDisallowedAction rejects move-to-folder outside export rules")
	void findDisallowedAction() {
		ObjectNode bad = Json.nodeFactory().objectNode();
		var rules = bad.putArray("rules");
		var rule = rules.addObject();
		rule.put("trigger", "recording-saved");
		rule.putArray("actions").addObject().put("type", "move-to-folder").put("folder", "/tmp/x");
		assertEquals(
				"\"move-to-folder\" is not available for recording-saved rules",
				Automations.findDisallowedAction(bad));

		ObjectNode good = Json.nodeFactory().objectNode();
		var goodRules = good.putArray("rules");
		var goodRule = goodRules.addObject();
		goodRule.put("trigger", "export-finished");
		goodRule.putArray("actions").addObject().put("type", "move-to-folder").put("folder", "/tmp/x");
		assertNull(Automations.findDisallowedAction(good));
		assertNull(Automations.findDisallowedAction(json("42")));
		assertNull(Automations.findDisallowedAction(json("{\"rules\":\"nope\"}")));
	}

	@Test
	@DisplayName("label maps cover every enum value")
	void labels() {
		for (AutomationTrigger trigger : AutomationTrigger.values()) {
			assertTrue(Automations.AUTOMATION_TRIGGER_LABELS.containsKey(trigger), trigger.wire());
		}
		for (AutomationActionType action : AutomationActionType.values()) {
			assertTrue(Automations.AUTOMATION_ACTION_LABELS.containsKey(action), action.wire());
		}
		for (AutomationConditionType condition : AutomationConditionType.values()) {
			assertTrue(Automations.AUTOMATION_CONDITION_LABELS.containsKey(condition), condition.wire());
		}
	}
}
