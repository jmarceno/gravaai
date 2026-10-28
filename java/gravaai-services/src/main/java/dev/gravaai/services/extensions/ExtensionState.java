package dev.gravaai.services.extensions;

/** Lifecycle state of one installed extension (plan §5.18: load/enable/disable/dispose). */
public enum ExtensionState {
	/** The directory and manifest were found; nothing was executed. */
	DISCOVERED,
	/** The module was loaded and {@code activate(api)} returned successfully. */
	ACTIVE,
	/** The extension was deactivated and its context disposed by the user. */
	DISABLED,
	/** Loading or activation failed; {@code ExtensionInfo.error()} says why. */
	FAILED
}
