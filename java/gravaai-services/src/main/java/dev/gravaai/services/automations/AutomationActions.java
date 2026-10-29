package dev.gravaai.services.automations;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Port of {@code electron/automations/actions.ts}: runs one action against the current context.
 *
 * <p>Throwing reports a failure to the engine, which logs it, notifies once and keeps going with
 * the next action.
 */
public final class AutomationActions {

	private AutomationActions() {}

	/** Runs one action; checked failures are wrapped so the engine can isolate them per action. */
	public static AutomationActionOutcome runAutomationAction(
			AutomationAction action, AutomationContext context, AutomationHost host, Path homedir) {
		try {
			return execute(action, context, host, homedir);
		} catch (RuntimeException error) {
			throw error;
		} catch (Exception error) {
			String message = error.getMessage() != null ? error.getMessage() : error.toString();
			throw new AutomationActionException(message, error);
		}
	}

	private static AutomationActionOutcome execute(
			AutomationAction action, AutomationContext context, AutomationHost host, Path homedir) throws Exception {
		String currentPath = context.path();
		return switch (action) {
			case AutomationAction.CopyPath ignored -> {
				host.copyToClipboard(currentPath);
				yield new AutomationActionOutcome(currentPath);
			}
			case AutomationAction.Reveal ignored -> {
				host.revealInFolder(currentPath);
				yield new AutomationActionOutcome(currentPath);
			}
			case AutomationAction.Open ignored -> {
				host.openPath(currentPath);
				yield new AutomationActionOutcome(currentPath);
			}
			case AutomationAction.Notify notify -> {
				String title = AutomationText.expandAutomationTemplate(notify.title(), context).trim();
				String body = AutomationText.expandAutomationTemplate(notify.body(), context).trim();
				if (!host.isNotificationSupported()) {
					throw new IllegalStateException("Desktop notifications are not supported on this system.");
				}
				host.notify(title.isEmpty() ? "Gravaai" : title, body);
				yield new AutomationActionOutcome(currentPath);
			}
			case AutomationAction.CopyToFolder copy -> {
				String target = FileActions.copyFileToFolder(
						new FileActions.FileActionOptions(context, copy.folder(), copy.fileName(), homedir));
				yield new AutomationActionOutcome(target);
			}
			case AutomationAction.MoveToFolder move -> {
				String target = FileActions.moveFileToFolder(
						new FileActions.FileActionOptions(context, move.folder(), move.fileName(), homedir));
				yield new AutomationActionOutcome(target);
			}
			case AutomationAction.RunCommand command -> {
				List<String> arguments = new ArrayList<>();
				for (String argument : command.args()) {
					arguments.add(AutomationText.expandAutomationTemplate(argument, context));
				}
				FileActions.runDetachedCommand(new FileActions.RunDetachedCommandOptions(
						AutomationText.expandAutomationTemplate(command.program(), context),
						arguments,
						dirname(currentPath),
						homedir));
				yield new AutomationActionOutcome(currentPath);
			}
		};
	}

	/** Failure feedback for the engine: one notification per rule run (port of
	 * {@code showAutomationFailureNotification}). */
	public static void showAutomationFailureNotification(
			String title, String body, AutomationHost host, Consumer<String> log) {
		if (!host.isNotificationSupported()) {
			log.accept("[automations] " + title + ": " + body);
			return;
		}
		host.notify(title, body);
	}

	/** Node's {@code path.dirname}: the parent directory, "." for a bare file name. */
	private static Path dirname(String filePath) {
		Path path = Path.of(filePath.isEmpty() ? "." : filePath);
		Path parent = path.getParent();
		return parent != null ? parent : Path.of(".");
	}
}
