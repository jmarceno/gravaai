package dev.gravaai.core.editor;

import java.util.List;

/**
 * A composite {@link EditorCommand}: applies every child command in order and reports changed when
 * any child changed, so a whole multi-selection edit (property change, move or delete) records as
 * exactly one undo step.
 */
public final class BatchCommand implements EditorCommand {

	private final List<EditorCommand> commands;

	public BatchCommand(List<EditorCommand> commands) {
		this.commands = List.copyOf(commands);
	}

	@Override
	public boolean apply(EditorStore store) {
		boolean changed = false;
		for (EditorCommand command : commands) {
			changed |= command.apply(store);
		}
		return changed;
	}
}
