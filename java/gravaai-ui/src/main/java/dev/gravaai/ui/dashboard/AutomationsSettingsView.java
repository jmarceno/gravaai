package dev.gravaai.ui.dashboard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.automations.AutomationAction;
import dev.gravaai.services.automations.AutomationActionType;
import dev.gravaai.services.automations.AutomationCondition;
import dev.gravaai.services.automations.AutomationConditionType;
import dev.gravaai.services.automations.AutomationExportFormat;
import dev.gravaai.services.automations.AutomationFailure;
import dev.gravaai.services.automations.AutomationRule;
import dev.gravaai.services.automations.AutomationRunResult;
import dev.gravaai.services.automations.AutomationSkip;
import dev.gravaai.services.automations.AutomationTemplate;
import dev.gravaai.services.automations.AutomationTrigger;
import dev.gravaai.services.automations.Automations;
import dev.gravaai.services.automations.AutomationsFile;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Card;
import dev.gravaai.ui.kit.Dialog;
import dev.gravaai.ui.kit.DropdownMenu;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.Select;
import dev.gravaai.ui.kit.Switch;
import dev.gravaai.ui.kit.Toast;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Settings -> Automations (port of {@code AutomationsSettings.tsx}).
 *
 * <p>Lists the rules of {@code userData/automations.json}: trigger, ordered conditions and actions,
 * enable switch, "Run now on a file...", templates and the delete confirmation. Edits are saved with
 * the same 400 ms debounce as the React page (and flushed before a run); the engine and file store
 * belong to {@code services.automations} and are reached through {@link AutomationsController}.
 */
public final class AutomationsSettingsView extends VBox {

	/** Port of {@code SAVE_DEBOUNCE_MS}. */
	private static final int SAVE_DEBOUNCE_MS = 400;

	private static final List<AutomationActionType> ACTION_TYPES = List.of(
			AutomationActionType.COPY_PATH,
			AutomationActionType.REVEAL,
			AutomationActionType.OPEN,
			AutomationActionType.NOTIFY,
			AutomationActionType.COPY_TO_FOLDER,
			AutomationActionType.MOVE_TO_FOLDER,
			AutomationActionType.RUN_COMMAND);

	private static final List<AutomationConditionType> CONDITION_TYPES = List.of(
			AutomationConditionType.DURATION_AT_LEAST,
			AutomationConditionType.DURATION_AT_MOST,
			AutomationConditionType.FORMAT_IS);

	private final AutomationsController controller;
	private final VBox content = new VBox(20);
	private final PauseTransition saveTimer = new PauseTransition(Duration.millis(SAVE_DEBOUNCE_MS));

	private AutomationsFile file;
	private AutomationsFile unsaved;
	private boolean loading = true;
	private String error;
	private boolean saving;

	public AutomationsSettingsView(AutomationsController controller) {
		this.controller = controller;
		getStyleClass().add("automations-settings");
		setSpacing(20);
		getChildren().add(content);
		saveTimer.setOnFinished(event -> flushSave());
		CompletableFuture.runAsync(() -> {
			try {
				AutomationsFile loaded = controller.getAutomations();
				Platform.runLater(() -> {
					file = loaded;
					loading = false;
					rebuild();
				});
			} catch (RuntimeException failure) {
				Platform.runLater(() -> {
					loading = false;
					error = message(failure);
					rebuild();
				});
			}
		});
	}

	/** Flushes an edit still inside the debounce window (used when leaving the page). */
	public void flushPendingSave() {
		if (unsaved != null && file != null) {
			AutomationsFile snapshot = file;
			unsaved = null;
			save(snapshot, false);
		}
	}

	/* ---------- state ---------- */

	private void commit(AutomationsFile next, boolean structural) {
		file = next;
		unsaved = next;
		error = null;
		if (structural) {
			rebuild();
		}
		saveTimer.playFromStart();
	}

	private CompletableFuture<Boolean> flushSave() {
		AutomationsFile snapshot = file;
		if (snapshot == null) {
			return CompletableFuture.completedFuture(true);
		}
		saveTimer.stop();
		unsaved = null;
		return save(snapshot, true);
	}

	private CompletableFuture<Boolean> save(AutomationsFile snapshot, boolean notify) {
		saving = true;
		refreshDirtyHint(false);
		return CompletableFuture.supplyAsync(() -> controller.saveAutomations(Automations.toJson(snapshot)))
				.thenApplyAsync(result -> {
					saving = false;
					if (result.isOk()) {
						file = result.orElse(snapshot);
						error = null;
					} else {
						error = result.errorMessage();
						if (notify) {
							Toast.show(this, error).variant(Toast.Variant.DANGER);
						}
					}
					if (notify) {
						rebuild();
					} else {
						refreshDirtyHint(false);
					}
					return result.isOk();
				}, Platform::runLater);
	}

	private void refreshDirtyHint(boolean visible) {
		Node hint = content.lookup(".automations-settings__saving");
		if (hint != null) {
			hint.setVisible(visible);
			hint.setManaged(visible);
		}
	}

	private void setRules(List<AutomationRule> rules) {
		commit(new AutomationsFile(1, rules), true);
	}

	private void updateRule(AutomationRule rule, boolean structural) {
		if (file == null) {
			return;
		}
		List<AutomationRule> rules = new ArrayList<>();
		for (AutomationRule entry : file.rules()) {
			rules.add(entry.id().equals(rule.id()) ? rule : entry);
		}
		commit(new AutomationsFile(1, rules), structural);
	}

	/* ---------- page rebuild (structural changes only) ---------- */

	private void rebuild() {
		content.getChildren().clear();
		if (loading) {
			Label loadingLabel = new Label("Loading automations…");
			loadingLabel.getStyleClass().add("automations-settings__hint");
			content.getChildren().add(loadingLabel);
			return;
		}
		content.getChildren().add(buildHeader());
		if (error != null && !error.isBlank()) {
			content.getChildren().add(buildError());
		}
		List<AutomationRule> rules = file == null ? List.of() : file.rules();
		if (rules.isEmpty()) {
			content.getChildren().add(buildEmptyState());
		} else {
			VBox ruleList = new VBox(16);
			for (AutomationRule rule : rules) {
				ruleList.getChildren().add(new RuleCard(rule).node());
			}
			content.getChildren().add(ruleList);
		}
		if (saving) {
			Label hint = new Label("Saving…");
			hint.getStyleClass().addAll("automations-settings__hint", "automations-settings__saving");
			content.getChildren().add(hint);
		}
	}

	private Node buildHeader() {
		VBox texts = new VBox(4);
		Label title = new Label("Automations");
		title.getStyleClass().add("automations-settings__title");
		Label description = new Label(
				"Run local actions when a recording, export or meeting notes finish. Nothing is sent online.");
		description.getStyleClass().add("automations-settings__hint");
		description.setWrapText(true);
		texts.getChildren().addAll(title, description);
		texts.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(texts, Priority.ALWAYS);

		Button templates = new Button("Add from template", Button.Variant.SECONDARY, Button.Size.SM);
		templates.setGraphic(Icon.of(Icons.MagicWand, 14));
		templates.setGraphicTextGap(6);
		templates.setMinWidth(Region.USE_PREF_SIZE);
		DropdownMenu templateMenu = new DropdownMenu(templates);
		for (AutomationTemplate template : Automations.AUTOMATION_TEMPLATES) {
			templateMenu.item(template.name(), () -> addTemplate(template));
		}

		Button newRule = new Button("New rule", Button.Variant.DEFAULT, Button.Size.SM);
		newRule.setGraphic(Icon.of(Icons.Plus, 14));
		newRule.setGraphicTextGap(6);
		newRule.setMinWidth(Region.USE_PREF_SIZE);
		newRule.setOnAction(event -> addRule());

		HBox actions = new HBox(8, templates, newRule);
		actions.setAlignment(Pos.CENTER_RIGHT);
		actions.setMinWidth(Region.USE_PREF_SIZE);
		HBox header = new HBox(12, texts, actions);
		header.setAlignment(Pos.TOP_LEFT);
		return header;
	}

	private Node buildError() {
		Label label = new Label(error);
		label.getStyleClass().add("automations-settings__error");
		label.setWrapText(true);
		label.setGraphic(Icon.of(Icons.WarningCircleIcon, 16));
		label.setContentDisplay(ContentDisplay.LEFT);
		label.setGraphicTextGap(6);
		return label;
	}

	private Node buildEmptyState() {
		VBox empty = new VBox(8);
		empty.getStyleClass().add("automations-settings__empty");
		empty.setAlignment(Pos.CENTER);
		Icon wand = Icon.of(Icons.MagicWand, 20);
		VBox iconHost = new VBox(wand);
		iconHost.getStyleClass().add("automations-settings__empty-icon");
		iconHost.setAlignment(Pos.CENTER);
		Label title = new Label("No automations yet.");
		title.getStyleClass().add("automations-settings__empty-title");
		Label hint = new Label(
				"Copy, move, open or notify when a recording or export is ready. Start from a template "
						+ "or build your own rule.");
		hint.getStyleClass().add("automations-settings__hint");
		hint.setWrapText(true);
		hint.setMaxWidth(420);
		empty.getChildren().addAll(iconHost, title, hint);
		return empty;
	}

	/* ---------- rule operations ---------- */

	private void addRule() {
		AutomationRule rule = new AutomationRule(newRuleId(), "New automation", false,
				AutomationTrigger.RECORDING_SAVED, List.of(), List.of(new AutomationAction.CopyPath()));
		List<AutomationRule> rules = new ArrayList<>(file == null ? List.of() : file.rules());
		rules.add(rule);
		setRules(rules);
	}

	private void addTemplate(AutomationTemplate template) {
		AutomationRule source = template.rule();
		AutomationRule rule = new AutomationRule(newRuleId(), source.name(), source.enabled(),
				source.trigger(), source.conditions(), source.actions());
		List<AutomationRule> rules = new ArrayList<>(file == null ? List.of() : file.rules());
		rules.add(rule);
		setRules(rules);
	}

	private void removeRule(AutomationRule rule) {
		List<AutomationRule> rules = new ArrayList<>();
		for (AutomationRule entry : file.rules()) {
			if (!entry.id().equals(rule.id())) {
				rules.add(entry);
			}
		}
		setRules(rules);
	}

	private void confirmDeleteRule(AutomationRule rule) {
		Dialog dialog = new Dialog("Delete \u201c" + (rule.name().isBlank() ? "Untitled" : rule.name()) + "\u201d?");
		dialog.width(420);
		dialog.description("This automation stops running and its trigger, conditions and actions are "
				+ "removed. This cannot be undone.");
		Button cancel = new Button("Cancel", Button.Variant.GHOST, Button.Size.SM);
		cancel.setOnAction(event -> dialog.close());
		Button delete = new Button("Delete", Button.Variant.DESTRUCTIVE, Button.Size.SM);
		delete.setOnAction(event -> {
			dialog.close();
			removeRule(rule);
		});
		dialog.footer(cancel, delete);
		dialog.show(this);
	}

	private void chooseFolder(AutomationRule rule, int actionIndex, AutomationAction action) {
		if (!(action instanceof AutomationAction.CopyToFolder copy)) {
			if (!(action instanceof AutomationAction.MoveToFolder move)) {
				return;
			}
			Path chosen = controller.chooseFolder(move.folder());
			if (chosen == null) {
				return;
			}
			replaceAction(rule, actionIndex,
					new AutomationAction.MoveToFolder(chosen.toString(), move.fileName()));
			return;
		}
		Path chosen = controller.chooseFolder(copy.folder());
		if (chosen == null) {
			return;
		}
		replaceAction(rule, actionIndex,
				new AutomationAction.CopyToFolder(chosen.toString(), copy.fileName()));
	}

	private void replaceAction(AutomationRule rule, int index, AutomationAction action) {
		List<AutomationAction> actions = new ArrayList<>(rule.actions());
		actions.set(index, action);
		updateRule(withActions(rule, actions), true);
	}

	private static AutomationRule withActions(AutomationRule rule, List<AutomationAction> actions) {
		return new AutomationRule(rule.id(), rule.name(), rule.enabled(), rule.trigger(),
				rule.conditions(), actions);
	}

	private static AutomationRule withConditions(AutomationRule rule, List<AutomationCondition> conditions) {
		return new AutomationRule(rule.id(), rule.name(), rule.enabled(), rule.trigger(), conditions,
				rule.actions());
	}

	private void runRule(AutomationRule rule) {
		flushSave().thenAccept(saved -> {
			if (!saved) {
				return;
			}
			Platform.runLater(() -> {
				Path sample = controller.chooseSampleFile();
				if (sample == null) {
					return;
				}
				CompletableFuture.supplyAsync(() ->
						controller.runAutomationOnFile(rule.id(), sample.toString(), null))
						.thenAcceptAsync(this::handleRunResult, Platform::runLater);
			});
		});
	}

	private void handleRunResult(Result<AutomationRunResult> result) {
		if (result.isErr()) {
			Toast.show(this, result.errorMessage()).variant(Toast.Variant.DANGER);
			return;
		}
		AutomationRunResult run = result.orElse(null);
		if (run == null) {
			return;
		}
		if (!run.failures().isEmpty()) {
			AutomationFailure failure = run.failures().get(0);
			Toast.show(this, failure.ruleName() + ": " + failure.message())
					.variant(Toast.Variant.DANGER);
			return;
		}
		if (run.ranActions() == 0 && !run.skipped().isEmpty()) {
			AutomationSkip skip = run.skipped().get(0);
			Toast.show(this, "Skipped: " + skip.reason()).variant(Toast.Variant.DANGER);
			return;
		}
		String plural = run.ranActions() == 1 ? "" : "s";
		Toast.show(this, "Ran " + run.ranActions() + " action" + plural)
				.variant(Toast.Variant.SUCCESS);
	}

	/* ---------- rule card ---------- */

	private final class RuleCard {

		private final AutomationRule rule;
		private final Card card = new Card();

		RuleCard(AutomationRule rule) {
			this.rule = rule;
			card.getStyleClass().add("automations-settings__rule");
			card.getChildren().addAll(buildHeader(), buildBody());
		}

		Node node() {
			return card;
		}

		private Node buildHeader() {
			Switch enabled = new Switch("", rule.enabled());
			enabled.setAccessibleText("Enable " + rule.name());
			enabled.onCheckedChange(checked -> updateRule(
					new AutomationRule(rule.id(), rule.name(), checked, rule.trigger(),
							rule.conditions(), rule.actions()), false));

			Input name = new Input();
			name.setText(rule.name());
			name.getStyleClass().add("automations-settings__name");
			name.setPrefHeight(32);
			name.setMaxWidth(Double.MAX_VALUE);
			HBox.setHgrow(name, Priority.ALWAYS);
			name.textProperty().addListener((observable, oldValue, value) -> updateRule(
					new AutomationRule(rule.id(), value, rule.enabled(), rule.trigger(),
							rule.conditions(), rule.actions()), false));

			Button run = new Button("Run now on a file…", Button.Variant.GHOST, Button.Size.SM);
			run.setGraphic(Icon.of(Icons.Play, 12, "fill"));
			run.setGraphicTextGap(6);
			run.setOnAction(event -> runRule(rule));

			Button delete = new Button("", Button.Variant.GHOST, Button.Size.ICON);
			delete.setGraphic(Icon.of(Icons.Trash, 14));
			delete.setAccessibleText("Delete automation");
			delete.setOnAction(event -> confirmDeleteRule(rule));

			HBox header = new HBox(8, enabled, name, run, delete);
			header.getStyleClass().add("automations-settings__rule-header");
			header.setAlignment(Pos.CENTER_LEFT);
			return header;
		}

		private Node buildBody() {
			VBox body = new VBox(16);
			body.getStyleClass().add("automations-settings__rule-body");
			body.getChildren().addAll(buildTriggerRow(), buildConditions(), buildActions(),
					buildPlaceholders());
			List<String> issues = Automations.automationRuleIssues(rule);
			if (!issues.isEmpty()) {
				VBox issueList = new VBox(4);
				issueList.getStyleClass().add("automations-settings__issues");
				for (String issue : issues) {
					Label item = new Label(issue);
					item.setGraphic(Icon.of(Icons.WarningCircleIcon, 14));
					item.setContentDisplay(ContentDisplay.LEFT);
					item.setGraphicTextGap(6);
					item.setWrapText(true);
					item.getStyleClass().add("automations-settings__issue");
					issueList.getChildren().add(item);
				}
				body.getChildren().add(issueList);
			}
			return body;
		}

		private Node buildTriggerRow() {
			Label label = new Label("Trigger");
			label.getStyleClass().add("automations-settings__row-label");
			label.setMinWidth(80);
			Select trigger = new Select();
			trigger.setPrefWidth(288);
			for (AutomationTrigger value : AutomationTrigger.values()) {
				trigger.item(value.wire(), Automations.automationTriggerLabel(value));
			}
			trigger.value(rule.trigger().wire());
			trigger.onValueChange(wire -> {
				AutomationTrigger next = AutomationTrigger.fromWire(wire);
				if (next == null || next == rule.trigger()) {
					return;
				}
				List<AutomationAction> actions = rule.actions().stream()
						.filter(action -> Automations.actionAllowedForTrigger(action.type(), next))
						.toList();
				List<AutomationCondition> conditions = rule.conditions().stream()
						.filter(condition -> Automations.triggerAllowsConditionsOfType(
								condition.type(), next))
						.toList();
				updateRule(new AutomationRule(rule.id(), rule.name(), rule.enabled(), next,
						conditions, actions), true);
			});
			HBox row = new HBox(12, label, trigger);
			row.setAlignment(Pos.CENTER_LEFT);
			FlowPane wrap = new FlowPane(12, 6, label, trigger);
			wrap.setAlignment(Pos.CENTER_LEFT);
			return wrap;
		}

		private Node buildConditions() {
			VBox section = new VBox(8);
			section.getStyleClass().add("automations-settings__section");
			HBox header = sectionHeader("Conditions", "All must match", "Add condition",
					() -> {
						List<AutomationCondition> conditions = new ArrayList<>(rule.conditions());
						conditions.add(defaultCondition(AutomationConditionType.DURATION_AT_LEAST));
						updateRule(withConditions(rule, conditions), true);
					});
			section.getChildren().add(header);
			if (rule.conditions().isEmpty()) {
				Label empty = new Label("None. The rule runs every time.");
				empty.getStyleClass().add("automations-settings__hint");
				section.getChildren().add(empty);
			}
			for (int index = 0; index < rule.conditions().size(); index++) {
				int conditionIndex = index;
				section.getChildren().add(conditionRow(rule.conditions().get(index), conditionIndex));
			}
			return section;
		}

		private Node conditionRow(AutomationCondition condition, int index) {
			HBox row = new HBox(8);
			row.getStyleClass().add("automations-settings__condition");
			row.setAlignment(Pos.CENTER_LEFT);

			Select type = new Select();
			type.setPrefWidth(192);
			for (AutomationConditionType candidate : CONDITION_TYPES) {
				if (candidate == AutomationConditionType.FORMAT_IS
						&& rule.trigger() != AutomationTrigger.EXPORT_FINISHED) {
					continue;
				}
				type.item(candidate.wire(), Automations.AUTOMATION_CONDITION_LABELS.get(candidate));
			}
			type.value(condition.type().wire());
			type.onValueChange(wire -> {
				AutomationConditionType next = conditionType(wire);
				if (next == null || next == condition.type()) {
					return;
				}
				List<AutomationCondition> conditions = new ArrayList<>(rule.conditions());
				conditions.set(index, defaultCondition(next));
				updateRule(withConditions(rule, conditions), true);
			});
			row.getChildren().add(type);

			if (condition instanceof AutomationCondition.FormatIs format) {
				Select formatSelect = new Select();
				formatSelect.setPrefWidth(112);
				formatSelect.item("mp4", "MP4");
				formatSelect.item("gif", "GIF");
				formatSelect.value(format.format().wire());
				formatSelect.onValueChange(wire -> {
					AutomationExportFormat next = AutomationExportFormat.fromWire(wire);
					if (next == null || next == format.format()) {
						return;
					}
					List<AutomationCondition> conditions = new ArrayList<>(rule.conditions());
					conditions.set(index, new AutomationCondition.FormatIs(next));
					updateRule(withConditions(rule, conditions), true);
				});
				row.getChildren().add(formatSelect);
			} else {
				double seconds = condition instanceof AutomationCondition.DurationAtLeast atLeast
						? atLeast.seconds()
						: ((AutomationCondition.DurationAtMost) condition).seconds();
				Input value = new Input();
				value.setText(formatSeconds(seconds));
				value.setPrefWidth(80);
				value.textProperty().addListener((observable, oldValue, text) -> {
					double parsed = parseSeconds(text);
					List<AutomationCondition> conditions = new ArrayList<>(rule.conditions());
					conditions.set(index, condition instanceof AutomationCondition.DurationAtLeast
							? new AutomationCondition.DurationAtLeast(parsed)
							: new AutomationCondition.DurationAtMost(parsed));
					updateRule(withConditions(rule, conditions), false);
				});
				Label unit = new Label("seconds");
				unit.getStyleClass().add("automations-settings__hint");
				row.getChildren().addAll(value, unit);
			}

			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			Button remove = new Button("", Button.Variant.GHOST, Button.Size.ICON);
			remove.setGraphic(Icon.of(Icons.X, 14));
			remove.setAccessibleText("Remove condition");
			remove.setOnAction(event -> {
				List<AutomationCondition> conditions = new ArrayList<>(rule.conditions());
				conditions.remove(index);
				updateRule(withConditions(rule, conditions), true);
			});
			row.getChildren().addAll(spacer, remove);
			return row;
		}

		private Node buildActions() {
			VBox section = new VBox(8);
			section.getStyleClass().add("automations-settings__section");
			section.getChildren().add(sectionHeader("Actions", "Run in order", "Add action", () -> {
				List<AutomationAction> actions = new ArrayList<>(rule.actions());
				actions.add(defaultAction(AutomationActionType.COPY_PATH));
				updateRule(withActions(rule, actions), true);
			}));
			if (rule.actions().isEmpty()) {
				Label empty = new Label("No actions yet. The rule will only be matched.");
				empty.getStyleClass().add("automations-settings__hint");
				section.getChildren().add(empty);
			}
			for (int index = 0; index < rule.actions().size(); index++) {
				section.getChildren().add(actionRow(rule.actions().get(index), index));
			}
			return section;
		}

		private Node actionRow(AutomationAction action, int index) {
			VBox box = new VBox(8);
			box.getStyleClass().add("automations-settings__action");

			HBox header = new HBox(8);
			header.setAlignment(Pos.CENTER_LEFT);
			Label number = new Label(String.valueOf(index + 1));
			number.getStyleClass().add("automations-settings__badge");
			number.setAlignment(Pos.CENTER);
			Select type = new Select();
			type.setPrefWidth(208);
			for (AutomationActionType candidate : ACTION_TYPES) {
				if (!Automations.actionAllowedForTrigger(candidate, rule.trigger())) {
					continue;
				}
				type.item(candidate.wire(), Automations.AUTOMATION_ACTION_LABELS.get(candidate));
			}
			type.value(action.type().wire());
			type.onValueChange(wire -> {
				AutomationActionType next = AutomationActionType.fromWire(wire);
				if (next == null || next == action.type()) {
					return;
				}
				replaceAction(rule, index, defaultAction(next));
			});
			header.getChildren().addAll(number, type);

			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			Button up = iconButton(Icons.CaretUpIcon, "Move action up", index > 0,
					() -> moveAction(index, -1));
			Button down = iconButton(Icons.CaretDown, "Move action down",
					index < rule.actions().size() - 1, () -> moveAction(index, 1));
			Button remove = iconButton(Icons.X, "Remove action", true,
					() -> {
						List<AutomationAction> actions = new ArrayList<>(rule.actions());
						actions.remove(index);
						updateRule(withActions(rule, actions), true);
					});
			header.getChildren().addAll(spacer, up, down, remove);
			box.getChildren().add(header);

			Node details = actionDetails(action, index);
			if (details != null) {
				box.getChildren().add(details);
			}
			return box;
		}

		private Node actionDetails(AutomationAction action, int index) {
			HBox row = new HBox(8);
			row.getStyleClass().add("automations-settings__action-details");
			row.setAlignment(Pos.CENTER_LEFT);
			if (action instanceof AutomationAction.Notify notify) {
				Input title = new Input();
				title.setText(notify.title());
				title.setPromptText("Title");
				title.setPrefWidth(192);
				title.textProperty().addListener((observable, oldValue, value) ->
						replaceActionQuietly(rule, index,
								new AutomationAction.Notify(value, notify.body())));
				Input body = new Input();
				body.setText(notify.body());
				body.setPromptText("Message");
				body.setMaxWidth(Double.MAX_VALUE);
				HBox.setHgrow(body, Priority.ALWAYS);
				body.textProperty().addListener((observable, oldValue, value) ->
						replaceActionQuietly(rule, index,
								new AutomationAction.Notify(notify.title(), value)));
				row.getChildren().addAll(title, body);
				return row;
			}
			if (action instanceof AutomationAction.CopyToFolder copy) {
				return folderFields(row, index, copy.folder(), copy.fileName(), true);
			}
			if (action instanceof AutomationAction.MoveToFolder move) {
				return folderFields(row, index, move.folder(), move.fileName(), false);
			}
			if (action instanceof AutomationAction.RunCommand command) {
				VBox column = new VBox(8);
				Input program = new Input();
				program.setText(command.program());
				program.setPromptText("/usr/bin/notify-send");
				program.textProperty().addListener((observable, oldValue, value) ->
						replaceActionQuietly(rule, index,
								new AutomationAction.RunCommand(value, command.args())));
				column.getChildren().add(program);
				for (int argumentIndex = 0; argumentIndex < command.args().size(); argumentIndex++) {
					int target = argumentIndex;
					HBox argumentRow = new HBox(4);
					argumentRow.setAlignment(Pos.CENTER_LEFT);
					Input argument = new Input();
					argument.setText(command.args().get(argumentIndex));
					argument.setPromptText("Argument");
					HBox.setHgrow(argument, Priority.ALWAYS);
					argument.textProperty().addListener((observable, oldValue, value) -> {
						List<String> args = new ArrayList<>(command.args());
						args.set(target, value);
						replaceActionQuietly(rule, index,
								new AutomationAction.RunCommand(command.program(), args));
					});
					Button remove = iconButton(Icons.X, "Remove argument " + (argumentIndex + 1), true,
							() -> {
								List<String> args = new ArrayList<>(command.args());
								args.remove(target);
								replaceAction(rule, index,
										new AutomationAction.RunCommand(command.program(), args));
							});
					argumentRow.getChildren().addAll(argument, remove);
					column.getChildren().add(argumentRow);
				}
				Button addArgument = new Button("Argument", Button.Variant.GHOST, Button.Size.SM);
				addArgument.setGraphic(Icon.of(Icons.Plus, 14));
				addArgument.setGraphicTextGap(6);
				addArgument.setOnAction(event -> {
					List<String> args = new ArrayList<>(command.args());
					args.add("");
					replaceAction(rule, index,
							new AutomationAction.RunCommand(command.program(), args));
				});
				column.getChildren().add(addArgument);
				return column;
			}
			return null;
		}

		private Node folderFields(HBox row, int index, String folder, String fileName,
				boolean copyAction) {
			Input folderInput = new Input();
			folderInput.setText(folder);
			folderInput.setPromptText("~/Videos/Exports");
			HBox.setHgrow(folderInput, Priority.ALWAYS);
			folderInput.setMaxWidth(Double.MAX_VALUE);
			folderInput.textProperty().addListener((observable, oldValue, value) ->
					replaceFolderQuietly(rule, index, value, fileName, copyAction));
			Button choose = new Button("Choose…", Button.Variant.SECONDARY, Button.Size.SM);
			choose.setOnAction(event -> chooseFolder(rule, index,
					copyAction
							? new AutomationAction.CopyToFolder(folderInput.getText(), fileName)
							: new AutomationAction.MoveToFolder(folderInput.getText(), fileName)));
			Input nameTemplate = new Input();
			nameTemplate.setText(fileName == null ? "" : fileName);
			nameTemplate.setPromptText("{name}");
			nameTemplate.setPrefWidth(160);
			nameTemplate.textProperty().addListener((observable, oldValue, value) ->
					replaceFolderQuietly(rule, index, folderInput.getText(),
							value.isBlank() ? null : value, copyAction));
			row.getChildren().addAll(folderInput, choose, nameTemplate);
			return row;
		}

		private void replaceFolderQuietly(AutomationRule target, int index, String folder,
				String fileName, boolean copyAction) {
			replaceActionQuietly(target, index, copyAction
					? new AutomationAction.CopyToFolder(folder, fileName)
					: new AutomationAction.MoveToFolder(folder, fileName));
		}

		private void moveAction(int index, int delta) {
			int target = index + delta;
			if (target < 0 || target >= rule.actions().size()) {
				return;
			}
			List<AutomationAction> actions = new ArrayList<>(rule.actions());
			AutomationAction moved = actions.remove(index);
			actions.add(target, moved);
			updateRule(withActions(rule, actions), true);
		}

		private Node buildPlaceholders() {
			FlowPane row = new FlowPane(6, 4);
			row.getStyleClass().add("automations-settings__placeholders");
			row.setAlignment(Pos.CENTER_LEFT);
			Label label = new Label("Placeholders");
			label.getStyleClass().add("automations-settings__hint");
			row.getChildren().add(label);
			for (String placeholder : Automations.AUTOMATION_PLACEHOLDERS) {
				Label chip = new Label(placeholder);
				chip.getStyleClass().add("automations-settings__placeholder");
				row.getChildren().add(chip);
			}
			return row;
		}

		private HBox sectionHeader(String title, String hint, String addLabel, Runnable onAdd) {
			Label heading = new Label(title);
			heading.getStyleClass().add("automations-settings__section-title");
			Label hintLabel = new Label(hint);
			hintLabel.getStyleClass().add("automations-settings__hint");
			Button add = new Button("Add", Button.Variant.GHOST, Button.Size.SM);
			add.setGraphic(Icon.of(Icons.Plus, 14));
			add.setGraphicTextGap(6);
			add.setAccessibleText(addLabel);
			add.setOnAction(event -> onAdd.run());
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			HBox header = new HBox(6, heading, hintLabel, spacer, add);
			header.setAlignment(Pos.CENTER_LEFT);
			return header;
		}
	}

	/* ---------- helpers ---------- */

	private void replaceActionQuietly(AutomationRule rule, int index, AutomationAction action) {
		List<AutomationAction> actions = new ArrayList<>(rule.actions());
		actions.set(index, action);
		updateRule(withActions(rule, actions), false);
	}

	private void moveAction(AutomationRule rule, int index, int delta) {
		int target = index + delta;
		if (target < 0 || target >= rule.actions().size()) {
			return;
		}
		List<AutomationAction> actions = new ArrayList<>(rule.actions());
		AutomationAction moved = actions.remove(index);
		actions.add(target, moved);
		updateRule(withActions(rule, actions), true);
	}

	private Button iconButton(String iconName, String accessibleText, boolean enabled,
			Runnable action) {
		Button button = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		button.setGraphic(Icon.of(iconName, 14));
		button.setAccessibleText(accessibleText);
		button.setDisable(!enabled);
		button.setOnAction(event -> action.run());
		return button;
	}

	private static String newRuleId() {
		return UUID.randomUUID().toString();
	}

	private static String formatSeconds(double seconds) {
		return seconds == Math.rint(seconds)
				? String.valueOf((long) seconds)
				: String.valueOf(seconds);
	}

	private static double parseSeconds(String text) {
		try {
			double value = Double.parseDouble(text == null ? "" : text.trim());
			return Double.isFinite(value) && value >= 0 ? value : 0;
		} catch (NumberFormatException error) {
			return 0;
		}
	}

	private static AutomationConditionType conditionType(String wire) {
		for (AutomationConditionType type : AutomationConditionType.values()) {
			if (type.wire().equals(wire)) {
				return type;
			}
		}
		return null;
	}

	/** Port of {@code defaultAction}. */
	static AutomationAction defaultAction(AutomationActionType type) {
		return switch (type) {
			case NOTIFY -> new AutomationAction.Notify("Gravaai", "{name}");
			case COPY_TO_FOLDER -> new AutomationAction.CopyToFolder("~/Videos", "{name}");
			case MOVE_TO_FOLDER -> new AutomationAction.MoveToFolder("~/Videos", "{name}");
			case RUN_COMMAND -> new AutomationAction.RunCommand("", List.of("{path}"));
			case REVEAL -> new AutomationAction.Reveal();
			case OPEN -> new AutomationAction.Open();
			case COPY_PATH -> new AutomationAction.CopyPath();
		};
	}

	/** Port of {@code defaultCondition}. */
	static AutomationCondition defaultCondition(AutomationConditionType type) {
		if (type == AutomationConditionType.FORMAT_IS) {
			return new AutomationCondition.FormatIs(AutomationExportFormat.MP4);
		}
		if (type == AutomationConditionType.DURATION_AT_MOST) {
			return new AutomationCondition.DurationAtMost(60);
		}
		return new AutomationCondition.DurationAtLeast(60);
	}

	private static String message(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
	}
}
