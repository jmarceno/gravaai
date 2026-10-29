package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One action of a rule (port of the discriminated {@code AutomationAction} union).
 *
 * <p>{@code fileName} is null when the copy/move action should keep the source name; the TS union
 * has the field absent in that case.
 */
public sealed interface AutomationAction
		permits AutomationAction.CopyPath,
				AutomationAction.Reveal,
				AutomationAction.Open,
				AutomationAction.Notify,
				AutomationAction.CopyToFolder,
				AutomationAction.MoveToFolder,
				AutomationAction.RunCommand {

	AutomationActionType type();

	record CopyPath() implements AutomationAction {
		@Override
		public AutomationActionType type() {
			return AutomationActionType.COPY_PATH;
		}
	}

	record Reveal() implements AutomationAction {
		@Override
		public AutomationActionType type() {
			return AutomationActionType.REVEAL;
		}
	}

	record Open() implements AutomationAction {
		@Override
		public AutomationActionType type() {
			return AutomationActionType.OPEN;
		}
	}

	record Notify(String title, String body) implements AutomationAction {
		public Notify {
			title = title == null ? "" : title;
			body = body == null ? "" : body;
		}

		@Override
		public AutomationActionType type() {
			return AutomationActionType.NOTIFY;
		}
	}

	record CopyToFolder(String folder, @Nullable String fileName) implements AutomationAction {
		public CopyToFolder {
			folder = folder == null ? "" : folder;
		}

		@Override
		public AutomationActionType type() {
			return AutomationActionType.COPY_TO_FOLDER;
		}
	}

	record MoveToFolder(String folder, @Nullable String fileName) implements AutomationAction {
		public MoveToFolder {
			folder = folder == null ? "" : folder;
		}

		@Override
		public AutomationActionType type() {
			return AutomationActionType.MOVE_TO_FOLDER;
		}
	}

	record RunCommand(String program, List<String> args) implements AutomationAction {
		public RunCommand {
			program = program == null ? "" : program;
			args = args == null ? List.of() : List.copyOf(args);
		}

		@Override
		public AutomationActionType type() {
			return AutomationActionType.RUN_COMMAND;
		}
	}
}
