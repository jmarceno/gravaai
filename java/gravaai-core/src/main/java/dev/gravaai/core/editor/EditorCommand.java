package dev.gravaai.core.editor;

/** One editor operation (plan §5.11: "one class per use*Commands operation"). */
@FunctionalInterface
public interface EditorCommand {

	/**
	 * Applies the operation to the store.
	 *
	 * @return true when the editor state changed and should be recorded as an undo step
	 */
	boolean apply(EditorStore store);
}
